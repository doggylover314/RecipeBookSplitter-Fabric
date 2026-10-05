#!/usr/bin/env python3
"""Minimal offline-mode Minecraft 1.21.11 (protocol 774) client that records every clientbound recipe_book_add packet.

It logs in, answers keep-alives and pings, and then just listens for --seconds. For each recipe_book_add packet it
records the frame size (what is on the wire, possibly compressed), the data size (packet id plus payload after
decompression, the number the server's limits apply to), the entry count, the replace flag and a SHA-256 of the
entry bytes. Consecutive recipe_book_add packets are grouped into "runs" with a SHA-256 over all their entry bytes.
The entry bytes themselves are appended to <out>.bin as [u32 big-endian length][bytes] records.

Result JSON (--out): recipe_book_add (per packet), runs, disconnect (reason, or null), violations (frames over
2,097,151 bytes or data over 8,388,608 bytes), max_frame, packets.

Packet ids were derived from the registration order in GameProtocols / ConfigurationProtocols / LoginProtocols.
"""
import argparse
import hashlib
import json
import re
import socket
import struct
import time
import uuid
import zlib

PROTOCOL_VERSION = 774  # 1.21.11
FRAME_MAX = 2097151
UNCOMPRESSED_MAX = 8388608

# login (clientbound)
L_DISCONNECT, L_FINISHED, L_COMPRESSION, L_QUERY = 0x00, 0x02, 0x03, 0x04
# configuration (clientbound)
C_DISCONNECT, C_FINISH, C_KEEPALIVE, C_PING, C_SELECT_PACKS = 0x02, 0x03, 0x04, 0x05, 0x0E
# play (clientbound)
P_DISCONNECT, P_KEEPALIVE, P_PING, P_RECIPE_ADD = 0x20, 0x2B, 0x3B, 0x48
# serverbound replies: login ack / query response, config finish / keepalive / pong / known packs, play keepalive / pong
S_LOGIN_QUERY_RESPONSE, S_LOGIN_ACK = 0x02, 0x03
S_CONFIG_FINISH, S_CONFIG_KEEPALIVE, S_CONFIG_PONG, S_CONFIG_KNOWN_PACKS = 0x03, 0x04, 0x05, 0x07
S_PLAY_KEEPALIVE, S_PLAY_PONG = 0x1B, 0x2C


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


def readable(data, limit=400):
    """Printable ASCII of a binary payload; enough to read a disconnect reason out of its NBT."""
    return re.sub(rb"[^\x20-\x7e]+", b" ", data).decode("ascii").strip()[:limit]


class Connection:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port))
        self.sock.settimeout(1.0)
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
        self.sock.sendall(varint(len(body)) + body)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--name", default="Tester")
    parser.add_argument("--seconds", type=float, default=30, help="how long to stay connected")
    parser.add_argument("--out", required=True, help="result JSON path; the entry bytes go to <out>.bin")
    args = parser.parse_args()

    result = {"recipe_book_add": [], "runs": [], "disconnect": None, "violations": [], "max_frame": 0, "packets": 0}
    run = None  # [packets, entries, first_replace, sha256]

    def close_run():
        nonlocal run
        if run is not None:
            result["runs"].append({"packets": run[0], "entries": run[1], "first_replace": run[2],
                                   "sha256": run[3].hexdigest()})
            run = None

    conn = Connection(args.host, args.port)
    conn.send(0x00, varint(PROTOCOL_VERSION) + mc_string(args.host) + struct.pack(">H", args.port) + varint(2))
    conn.send(0x00, mc_string(args.name) + uuid.uuid3(uuid.NAMESPACE_DNS, "OfflinePlayer:" + args.name).bytes)

    state = "login"
    previous_was_recipe_add = False
    deadline = time.time() + args.seconds
    with open(args.out + ".bin", "wb") as bin_file:
        while time.time() < deadline:
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
                elif packet_id == C_DISCONNECT:
                    result["disconnect"] = "config: " + readable(data)
                    break
            else:
                if packet_id == P_KEEPALIVE:
                    conn.send(S_PLAY_KEEPALIVE, data[:8])
                elif packet_id == P_PING:
                    conn.send(S_PLAY_PONG, data[:4])
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
                        "entry_bytes": len(entry_bytes)})
                    print(f"recipe_book_add frame={frame_len} data={data_len} count={count} replace={replace}", flush=True)
    close_run()

    with open(args.out, "w") as f:
        json.dump(result, f, indent=1)
    print(json.dumps({k: v for k, v in result.items() if k != "recipe_book_add"}), flush=True)


if __name__ == "__main__":
    main()
