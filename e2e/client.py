#!/usr/bin/env python3
"""Minimal offline-mode Minecraft client that records every clientbound recipe_book_add packet. It speaks protocol 774
(1.21.11, the server's own) or, for the ViaFabric scenarios, a newer one: 775 / 776 (26.1 / 26.2) through ViaVersion.

It logs in, answers keep-alives and pings, and then just listens for --seconds. For each recipe_book_add packet it
records the frame size (what is on the wire, possibly compressed), the data size (packet id plus payload after
decompression, the number the server's limits apply to), the entry count, the replace flag, the bundle it arrived in
(or null) and a SHA-256 of the entry bytes. Consecutive recipe_book_add packets are grouped into "runs" with a SHA-256
over all their entry bytes. The entry bytes themselves are appended to <out>.bin as [u32 big-endian length][bytes]
records.

Result JSON (--out): recipe_book_add (per packet), runs, sequence (every bundle delimiter, recipe_book_add and
recipe_book_remove packet in order, with the bundle index), bundles (how many bundles), disconnect (reason, or null),
violations (frames over 2,097,151 bytes or data over 8,388,608 bytes), max_frame, packets, and with
--ping-interval-ms the round trips: pings [[send time, rtt ms]], pings_unanswered.

Optional behaviours: --locale LANG sends client information with that language in the configuration phase (the
default client sends none, so the server assumes en_us); --switch-locale LANG sends it again --switch-after seconds
after the play phase starts, as a player changing the language in the options does (protocol 774 only);
--ping-interval-ms N sends a ping request every N ms in the play phase (protocol 774 only): the server answers on its
Netty thread, so the round trip shows how long that thread was busy.

Packet ids, protocol 774: derived from the registration order in GameProtocols / ConfigurationProtocols /
LoginProtocols; the bundle delimiter is id 0 (registered first) and recipe_book_remove follows recipe_book_add.
Protocols 775 and 776: taken from ViaVersion's own packet enums (ordinal = packet id; the method was checked by
reproducing the 774 ids from ClientboundPackets1_21_11): ClientboundPackets26_1 / ServerboundPackets26_1 for play,
ClientboundConfigurationPackets1_21_9 / ServerboundConfigurationPackets1_21_9 for configuration, ClientboundLoginPackets /
ServerboundLoginPackets for login. Protocol 776 (26.2) has no packet enum of its own in ViaVersion 5.10.0, so it uses
the 26.1 ids. Protocol 777 (26.3) comes from ViaVersion 5.12.1 (ClientboundPackets26_3, ClientboundConfigurationPackets26_3);
it only ever ran against a locally patched ViaFabric build, never against the pinned one, which has no 26.3 support.

The recipe_book_add entries are NOT in the 1.21.11 wire format for protocols 775 and later (ViaVersion rewrites them),
so the entry hash is only comparable between packets of the same protocol. Only the leading entry count (VarInt) and
the trailing replace flag are parsed, which is the layout in all of them.
"""
import argparse
import hashlib
import json
import re
import signal
import socket
import struct
import time
import uuid
import zlib

FRAME_MAX = 2097151
UNCOMPRESSED_MAX = 8388608

# login (clientbound); configuration ids are the same in 774, 775 and 776
L_DISCONNECT, L_FINISHED, L_COMPRESSION, L_QUERY = 0x00, 0x02, 0x03, 0x04
C_DISCONNECT, C_FINISH, C_KEEPALIVE, C_PING = 0x02, 0x03, 0x04, 0x05
# serverbound replies: login ack / query response, config client information / finish / keepalive / pong / known packs
S_LOGIN_QUERY_RESPONSE, S_LOGIN_ACK = 0x02, 0x03
S_CONFIG_CLIENT_INFORMATION, S_CONFIG_FINISH, S_CONFIG_KEEPALIVE, S_CONFIG_PONG, S_CONFIG_KNOWN_PACKS = 0x00, 0x03, 0x04, 0x05, 0x07
# play, protocol 774 only: serverbound client information and ping request, clientbound pong response
S_PLAY_CLIENT_INFORMATION, S_PLAY_PING_REQUEST, P_PONG_RESPONSE = 0x0D, 0x25, 0x3C
P_BUNDLE_DELIMITER = 0x00

# per protocol: clientbound configuration select_known_packs, play disconnect / keep alive / ping / recipe_book_add,
# serverbound play keep alive / pong
PLAY_IDS = {
    774: dict(name="1.21.11", select_packs=0x0E, disconnect=0x20, keepalive=0x2B, ping=0x3B, recipe_add=0x48, s_keepalive=0x1B, s_pong=0x2C),
    775: dict(name="26.1", select_packs=14, disconnect=32, keepalive=44, ping=61, recipe_add=74, s_keepalive=28, s_pong=45),
    776: dict(name="26.2", select_packs=14, disconnect=32, keepalive=44, ping=61, recipe_add=74, s_keepalive=28, s_pong=45),
    777: dict(name="26.3", select_packs=15, disconnect=32, keepalive=45, ping=62, recipe_add=75, s_keepalive=28, s_pong=45),
}


def varint(n):
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def read_varint(data, k=0):
    value = shift = 0
    while True:
        b = data[k]
        value |= (b & 0x7F) << shift
        shift += 7
        k += 1
        if not b & 0x80:
            return value, k


def mc_string(s):
    b = s.encode()
    return varint(len(b)) + b


def client_information(language):
    """ClientInformation.write: language, view distance 2, chat FULL, chat colors, no skin parts, main hand RIGHT,
    no text filtering, no listing, particles ALL (enums are written as VarInt ordinals)."""
    return (mc_string(language) + b"\x02" + varint(0) + b"\x01" + b"\x00" + varint(1) + b"\x00" + b"\x00" + varint(0))


def readable(data, limit=400):
    """Printable ASCII of a binary payload; enough to read a disconnect reason out of its NBT."""
    return re.sub(rb"[^\x20-\x7e]+", b" ", data).decode("ascii").strip()[:limit]


class Connection:
    def __init__(self, host, port, timeout):
        self.sock = socket.create_connection((host, port))
        self.timeout = timeout
        self.sock.settimeout(timeout)
        self.buf = bytearray()
        self.compression = -1

    def recv_packet(self):
        """Returns (packet id, payload, frame length, data length); raises socket.timeout if nothing arrives."""
        while True:
            try:
                length, n = read_varint(self.buf)
                if len(self.buf) >= n + length:
                    break
            except IndexError:
                pass
            chunk = self.sock.recv(1 << 20)
            if not chunk:
                raise EOFError("server closed the connection")
            self.buf += chunk
        frame = bytes(self.buf[n:n + length])
        del self.buf[:n + length]
        data = frame
        if self.compression >= 0:
            uncompressed, k = read_varint(frame)
            data = zlib.decompress(frame[k:]) if uncompressed else frame[k:]
        packet_id, k = read_varint(data)
        return packet_id, data[k:], length, len(data)

    def send(self, packet_id, payload=b""):
        body = varint(packet_id) + payload
        if self.compression >= 0:
            body = varint(0) + body
        # A short receive timeout must not cut a send in half.
        self.sock.settimeout(10)
        self.sock.sendall(varint(len(body)) + body)
        self.sock.settimeout(self.timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--name", default="Tester")
    parser.add_argument("--protocol", type=int, default=774, choices=sorted(PLAY_IDS),
                        help="protocol version to speak: 774 (1.21.11), 775 (26.1), 776 (26.2), 777 (26.3)")
    parser.add_argument("--seconds", type=float, default=30, help="how long to stay connected")
    parser.add_argument("--out", required=True, help="result JSON path; the entry bytes go to <out>.bin")
    parser.add_argument("--locale", help="language to announce in the configuration phase, e.g. de_de")
    parser.add_argument("--switch-locale", help="language to announce again --switch-after seconds into the play phase (774 only)")
    parser.add_argument("--switch-after", type=float, default=8)
    parser.add_argument("--ping-interval-ms", type=float, default=0, help="ping the server this often in the play phase (774 only)")
    args = parser.parse_args()
    if (args.switch_locale or args.ping_interval_ms) and args.protocol != 774:
        parser.error("--switch-locale and --ping-interval-ms need protocol 774")

    ids = PLAY_IDS[args.protocol]
    P_DISCONNECT, P_KEEPALIVE, P_PING, P_RECIPE_ADD = ids["disconnect"], ids["keepalive"], ids["ping"], ids["recipe_add"]
    P_RECIPE_REMOVE = P_RECIPE_ADD + 1
    S_PLAY_KEEPALIVE, S_PLAY_PONG = ids["s_keepalive"], ids["s_pong"]
    C_SELECT_PACKS = ids["select_packs"]
    kinds = {P_BUNDLE_DELIMITER: "delimiter", P_RECIPE_ADD: "add", P_RECIPE_REMOVE: "remove"}

    result = {"protocol": args.protocol, "client_version": ids["name"], "recipe_book_add": [], "runs": [], "disconnect": None,
              "violations": [], "max_frame": 0, "packets": 0, "sequence": [], "bundles": 0}
    run = None  # [packets, entries, first_replace, sha256]
    bundle = None  # index of the bundle we are inside, or None
    pings, outstanding = [], {}  # outstanding: ping id (monotonic ns) -> send time (epoch)
    ping_interval = args.ping_interval_ms / 1000.0
    next_ping = 0.0
    play_since = None
    locale_switched = False

    def close_run():
        nonlocal run
        if run is not None:
            result["runs"].append({"packets": run[0], "entries": run[1], "first_replace": run[2],
                                   "sha256": run[3].hexdigest()})
            run = None

    timeout = min(1.0, ping_interval) if ping_interval else 1.0
    conn = Connection(args.host, args.port, timeout)
    conn.send(0x00, varint(args.protocol) + mc_string(args.host) + struct.pack(">H", args.port) + varint(2))
    conn.send(0x00, mc_string(args.name) + uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + args.name).bytes)

    # SIGTERM ends the run like the deadline does, so the driver can stop a long-running prober and still get its result.
    stopped = []
    signal.signal(signal.SIGTERM, lambda *_: stopped.append(True))
    state = "login"
    previous_was_recipe_add = False
    deadline = time.time() + args.seconds
    with open(args.out + ".bin", "wb") as bin_file:
        while time.time() < deadline and not stopped:
            now = time.time()
            if state == "play":
                if ping_interval and now >= next_ping:
                    ping_id = time.monotonic_ns()
                    outstanding[ping_id] = now
                    conn.send(S_PLAY_PING_REQUEST, struct.pack(">q", ping_id))
                    next_ping = now + ping_interval
                if args.switch_locale and not locale_switched and now >= play_since + args.switch_after:
                    locale_switched = True
                    result["locale_switched_at"] = round(now, 3)
                    conn.send(S_PLAY_CLIENT_INFORMATION, client_information(args.switch_locale))
            try:
                packet_id, data, frame_len, data_len = conn.recv_packet()
            except socket.timeout:
                continue
            except Exception as e:  # EOFError, ConnectionResetError, zlib errors
                result["disconnect"] = result["disconnect"] or f"connection lost: {e}"
                break

            is_recipe_add = state == "play" and packet_id == P_RECIPE_ADD
            if not (is_recipe_add and previous_was_recipe_add):
                close_run()
            previous_was_recipe_add = is_recipe_add

            result["packets"] += 1
            result["max_frame"] = max(result["max_frame"], frame_len)
            if frame_len > FRAME_MAX or data_len > UNCOMPRESSED_MAX:
                result["violations"].append({"state": state, "id": packet_id, "frame": frame_len, "data": data_len})

            if state == "login":
                if packet_id == L_COMPRESSION:
                    conn.compression = read_varint(data)[0]
                elif packet_id == L_FINISHED:
                    conn.send(S_LOGIN_ACK)
                    state = "config"
                    if args.locale:
                        conn.send(S_CONFIG_CLIENT_INFORMATION, client_information(args.locale))
                elif packet_id == L_QUERY:  # custom query: answer "not understood"
                    conn.send(S_LOGIN_QUERY_RESPONSE, varint(read_varint(data)[0]) + b"\x00")
                elif packet_id == L_DISCONNECT:
                    result["disconnect"] = "login: " + readable(data)
                    break
            elif state == "config":
                if packet_id == C_SELECT_PACKS:
                    conn.send(S_CONFIG_KNOWN_PACKS, varint(0))
                elif packet_id == C_PING:
                    conn.send(S_CONFIG_PONG, data[:4])
                elif packet_id == C_KEEPALIVE:
                    conn.send(S_CONFIG_KEEPALIVE, data[:8])
                elif packet_id == C_FINISH:
                    conn.send(S_CONFIG_FINISH)
                    state = "play"
                    play_since = time.time()
                elif packet_id == C_DISCONNECT:
                    result["disconnect"] = "config: " + readable(data)
                    break
            else:
                if packet_id == P_BUNDLE_DELIMITER:
                    if bundle is None:
                        bundle = result["bundles"]
                        result["bundles"] += 1
                    else:
                        bundle = None
                if packet_id in kinds:
                    result["sequence"].append({"id": packet_id, "kind": kinds[packet_id], "bundle": bundle, "data": data_len})
                if packet_id == P_KEEPALIVE:
                    conn.send(S_PLAY_KEEPALIVE, data[:8])
                elif packet_id == P_PING:
                    conn.send(S_PLAY_PONG, data[:4])
                elif packet_id == P_PONG_RESPONSE and args.protocol == 774:
                    (ping_id,) = struct.unpack(">q", data[:8])
                    if ping_id in outstanding:
                        pings.append([round(outstanding.pop(ping_id), 3), round((time.monotonic_ns() - ping_id) / 1e6, 3)])
                elif packet_id == P_DISCONNECT:
                    result["disconnect"] = "play: " + readable(data)
                    break
                elif packet_id == P_RECIPE_ADD:
                    count, k = read_varint(data)
                    entry_bytes = data[k:-1]  # between the entry count and the trailing replace flag
                    replace = data[-1] == 1
                    if run is None:
                        run = [0, 0, replace, hashlib.sha256()]
                    run[0] += 1
                    run[1] += count
                    run[3].update(entry_bytes)
                    bin_file.write(struct.pack(">I", len(entry_bytes)) + entry_bytes)
                    result["recipe_book_add"].append({
                        "t": round(time.time(), 3), "frame": frame_len, "data": data_len, "count": count,
                        "replace": replace, "sha256": hashlib.sha256(entry_bytes).hexdigest(),
                        "entry_bytes": len(entry_bytes), "bundle": bundle})
                    print(f"recipe_book_add frame={frame_len} data={data_len} count={count} replace={replace}"
                          + (f" bundle={bundle}" if bundle is not None else ""), flush=True)
    close_run()
    if ping_interval:
        result["pings"] = pings
        result["pings_unanswered"] = len(outstanding)

    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps({k: v for k, v in result.items() if k not in ("recipe_book_add", "sequence", "pings")}), flush=True)


if __name__ == "__main__":
    main()
