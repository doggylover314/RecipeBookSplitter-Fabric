#!/usr/bin/env python3
"""Checks what one E2E scenario produced. Usage: check.py <scenario>   (reads E2E_WORK/<scenario>, see run_e2e.sh)

The server log is cut into phases at every "Tester[...] logged in" line and compared with what the protocol client
(client.py) received in the same phase. Exit status 0 if every assertion holds.

scenario.json "kind" selects the assertions:
  split          the 9.2 MB book is split: digests against the client's packets, replace flags, budget (E1-E7, L*)
  bundlechunks   as split, with bundleChunks on: the chunks of each book arrive inside one bundle (E8c, E8d)
  baseline       no mod: the connection ends in "Packet too big/large"
  bundle         a recipe book packet inside a bundle is split in place (E8, E8b, E8e)
  undeliverable  entries the connection cannot send are left out, or sent anyway (E9, E9b, E9s)
  perf           Tester's repeated books while another connection is pinged (X*); no timing thresholds

This module is also the library of check_poly.py and check_via.py (log regexes, Report, find_sequences, ...).
"""
import hashlib
import json
import os
import re
import statistics
import struct
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

WORK = Path(os.environ.get("E2E_WORK") or Path(__file__).resolve().parent / "work")

LOGIN_RE = re.compile(r"Tester\[.*\] logged in")
LOADED_RE = re.compile(r"\[RecipeBookSplitter\] loaded: (.*)")
DIGEST_RE = re.compile(r"\[RecipeBookSplitter\] digest player=Tester entries=(\d+) replace=(\w+) bytes=(\d+) chunks=(\d+) sha256=(\w+)( dropped=(\d+))?( bundle=true)?")
# Also matches the 1.0.0 line (no "in a bundle", no "written in", no encode-once suffix).
SPLIT_RE = re.compile(r"\[RecipeBookSplitter\] Tester: split ([\d.]+) MiB recipe book packet( in a bundle)? \(([\d,]+) bytes, ([\d,]+) entries, replace=(\w+)\) into (\d+) chunks( in one bundle)? \(largest ([\d,]+) bytes, limit ([\d,]+) bytes, (\d+) ms(?:, written in (\d+) ms)?\)(?:; measured bytes reused for (\d+) of (\d+) packets|; (\d+) of (\d+) packets verified against a normal encode)?")
ALONE_RE = re.compile(r"\[RecipeBookSplitter\] Tester: recipe display entry #(\d+) \(display id (\d+), recipe [^)]*\) is ([\d,]+) bytes on its own, more than maxChunkBytes \(([\d,]+)\); sending it in a chunk by itself(.*)")
DROPPED_RE = re.compile(r"ERROR\]: \[RecipeBookSplitter\] Tester: left out recipe display entry #(\d+) \(display id (\d+), recipe [^)]*\): it is ([\d,]+) bytes on its own and this connection cannot send it \(([^)]*)\)")
SENT_ANYWAY_RE = re.compile(r"ERROR\]: \[RecipeBookSplitter\] Tester: recipe display entry #(\d+) \(display id (\d+), recipe [^)]*\) is ([\d,]+) bytes on its own and this connection cannot send it \(([^)]*)\); sending it anyway")
OVERSIZED_RE = re.compile(r"\[RecipeBookSplitter\] oversized clientbound packet clientbound/minecraft:recipe_book_add for Tester: [\d.]+ MiB \(([\d,]+) bytes\)")
BASELINE_ERROR_RE = re.compile(r"Tester lost connection:.*(Packet too big|Packet too large)")
CLIENT_BASELINE_RE = re.compile(r"Packet too (big|large)")
RBS_ERROR_RE = re.compile(r"/ERROR\]: \[RecipeBookSplitter\]")
RBS_WARN_RE = re.compile(r"/WARN\]: \[RecipeBookSplitter\]")
ENCODE_OFF_RE = re.compile(r"\[RecipeBookSplitter\] encode once is off \(-Drecipebooksplitter\.encodeOnce=false\)")
VERIFY_ON_RE = re.compile(r"\[RecipeBookSplitter\] verifyEncodeOnce is on")

DEFAULT_CONFIG = ('{\n  "maxChunkBytes": 1048576,\n  "logSplits": true,\n  "logOversizedPackets": false,\n'
                  '  "undeliverableEntries": "drop",\n  "bundleChunks": false\n}\n')
FRAME_LIMIT = 2097151
UNCOMPRESSED_LIMIT = 8388608


class Report:
    def __init__(self):
        self.failures = 0

    def check(self, ok, message):
        print(("  PASS  " if ok else "  FAIL  ") + message)
        if not ok:
            self.failures += 1
        return ok

    def note(self, message):
        print("        " + message)


def number(text):
    return int(text.replace(",", ""))


def mib(n):
    """The mod's Sizes.mib: Java's %.1f rounds half up, Python's rounds half to even (262,144 is "0.3 MiB" in Java)."""
    return str((Decimal(n) / Decimal(1048576)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def loaded_line(scenario):
    """The mod's 'loaded:' line for a scenario written with write_config."""
    budget = scenario["max_chunk_bytes"]
    return (f"maxChunkBytes={budget:,} ({mib(budget)} MiB), logSplits=true, logOversizedPackets={str(scenario['log_oversized']).lower()}, "
            f"undeliverableEntries={scenario['undeliverable_entries']}, bundleChunks={str(scenario['bundle_chunks']).lower()}")


def parse_phase(spec):
    """'[<protocol|newer>/]<action>[:<arg>]' as (protocol or None, action, arg or None)."""
    m = re.fullmatch(r"(?:(\d+|newer)/)?([a-z_]+)(?::(.*))?", spec, re.S)
    if not m:
        raise ValueError(f"bad phase spec {spec!r}")
    return m[1], m[2], m[3]


def split_phases(lines):
    starts = [i for i, line in enumerate(lines) if LOGIN_RE.search(line)]
    ends = starts[1:] + [len(lines)]
    return [lines[s:e] for s, e in zip(starts, ends)]


def parse_server_phase(lines):
    phase = {"lines": lines, "digests": [], "splits": [], "alone": [], "dropped": [], "sent_anyway": [], "oversized": [], "lost": []}
    for line in lines:
        if m := DIGEST_RE.search(line):
            phase["digests"].append({"entries": int(m[1]), "replace": m[2] == "true", "bytes": int(m[3]),
                                     "chunks": int(m[4]), "sha256": m[5], "dropped": int(m[7] or 0), "bundle": m[8] is not None})
        if m := SPLIT_RE.search(line):
            phase["splits"].append({"bytes": number(m[3]), "entries": number(m[4]), "replace": m[5] == "true",
                                    "chunks": int(m[6]), "largest": number(m[8]), "limit": number(m[9]),
                                    "ms": int(m[10]), "write_ms": int(m[11]) if m[11] else None,
                                    "in_bundle": m[2] is not None, "one_bundle": m[7] is not None,
                                    "reused": (int(m[12]), int(m[13])) if m[12] else None,
                                    "verified": (int(m[14]), int(m[15])) if m[14] else None})
        if m := ALONE_RE.search(line):
            phase["alone"].append({"display_id": int(m[2]), "bytes": number(m[3]), "note": m[5]})
        if m := DROPPED_RE.search(line):
            phase["dropped"].append({"display_id": int(m[2]), "bytes": number(m[3]), "reason": m[4]})
        if m := SENT_ANYWAY_RE.search(line):
            phase["sent_anyway"].append({"display_id": int(m[2]), "bytes": number(m[3]), "reason": m[4]})
        if m := OVERSIZED_RE.search(line):
            phase["oversized"].append(number(m[1]))
        if m := re.search(r"Tester lost connection: (.*)", line):
            phase["lost"].append(m[1].strip())
    return phase


def load_client(path):
    if not path.exists():
        return None
    client = json.loads(path.read_text())
    client["bin"] = None
    bin_path = Path(str(path) + ".bin")
    if bin_path.exists():
        records, data, k = [], bin_path.read_bytes(), 0
        while k + 4 <= len(data):
            (length,) = struct.unpack(">I", data[k:k + 4])
            records.append(data[k + 4:k + 4 + length])
            k += 4 + length
        client["bin"] = records
    return client


def without_empty_packets(client):
    """The client record without its empty recipe_book_add packets. Polymer's own splitter (split_recipe_book_packet_amount)
    sends an empty replace=true packet before its chunks; left in, it would be taken for the start of the first chunk."""
    keep = [k for k, p in enumerate(client["recipe_book_add"]) if p["count"] > 0]
    if len(keep) == len(client["recipe_book_add"]):
        return client
    out = dict(client)
    out["recipe_book_add"] = [client["recipe_book_add"][k] for k in keep]
    out["bin"] = [client["bin"][k] for k in keep] if client["bin"] is not None and len(client["bin"]) == len(client["recipe_book_add"]) else None
    out["runs"] = []  # the runs were cut with the empty packets in them; the entry bytes alone still identify a sequence
    return out


def find_sequences(client, digest):
    """(first packet index, packet count) of every sequence of the client's recipe_book_add packets that carries exactly
    the entries of this server digest. The indices refer to client["recipe_book_add"]; empty packets (Polymer's leading
    replace=true packet) are left out when matching, and a sequence spans any that lie inside it."""
    packets = client["recipe_book_add"]
    keep = [k for k, p in enumerate(packets) if p["count"] > 0]
    if len(keep) != len(packets):
        return [(keep[first], keep[first + count - 1] - keep[first] + 1)
                for first, count in find_sequences(without_empty_packets(client), digest)]
    found, first = [], 0
    # A contiguous run of recipe_book_add packets (what the client normally sees).
    for run in client["runs"]:
        if run["entries"] == digest["entries"] and run["sha256"] == digest["sha256"]:
            found.append((first, run["packets"]))
        first += run["packets"]
    if found or client["bin"] is None or len(client["bin"]) != len(packets):
        return found
    # A proxy may have put other packets in between: consume packets in order until the entry counts add up and
    # compare the hash of their concatenated entry bytes.
    for start in range(len(packets)):
        total, sha = 0, hashlib.sha256()
        for k in range(start, len(packets)):
            total += packets[k]["count"]
            sha.update(client["bin"][k])
            if total >= digest["entries"]:
                if total == digest["entries"] and sha.hexdigest() == digest["sha256"]:
                    found.append((start, k - start + 1))
                break
    return found


def check_encode_once(report, label, server, scenario):
    """Every split line carries the encode-once suffix the scenario expects (reused, verified or none)."""
    expected = scenario.get("encode_once", "any")
    if expected == "any":
        return
    for split in server["splits"]:
        what = f"{label}: split line ({split['entries']:,} entries, {split['chunks']} chunks)"
        if expected == "reused":
            report.check(split["reused"] == (split["chunks"], split["chunks"]),
                         f"{what}: measured bytes reused for all {split['chunks']} packets ({split['reused']})")
        elif expected == "verified":
            report.check(split["verified"] == (split["chunks"], split["chunks"]),
                         f"{what}: all {split['chunks']} packets verified against a normal encode ({split['verified']})")
        else:
            report.check(split["reused"] is None and split["verified"] is None, f"{what}: no encode-once suffix (encodeOnce is off)")


def check_split_phase(report, label, server, client, scenario):
    """Assertions for one phase of a scenario in which the mod is active."""
    max_chunk = scenario["max_chunk_bytes"]
    client = without_empty_packets(client)
    packets = client["recipe_book_add"]

    report.check(client["disconnect"] is None, f"{label}: client was not disconnected ({client['disconnect']})")
    report.check(not client["violations"], f"{label}: no frame over {FRAME_LIMIT:,} or data over {UNCOMPRESSED_LIMIT:,} bytes {client['violations'][:2]}")
    report.note(f"{label}: {len(packets)} recipe_book_add packets, max data "
                f"{max((p['data'] for p in packets), default=0):,} bytes, max frame {client['max_frame']:,} bytes")

    # Every server digest is matched by its own client sequence with the right shape. Identical packets (the join and
    # a later /reload send the same recipe book) have identical digests, so each client sequence is used only once.
    used = set()
    for digest in server["digests"]:
        what = f"{label}: digest of {digest['entries']:,} entries (replace={digest['replace']}, {digest['chunks']} chunks)"
        matches = [m for m in find_sequences(client, digest) if m[0] not in used]
        if not report.check(bool(matches), f"{what} matches a client sequence of packets"):
            continue
        first, count = matches[0]
        used.add(first)
        sequence = packets[first:first + count]
        report.check(count == digest["chunks"], f"{what}: client received {count} packets")
        report.check(sequence[0]["replace"] == digest["replace"] and not any(p["replace"] for p in sequence[1:]),
                     f"{what}: replace flag only on the first packet, as the original")
        if digest["chunks"] == 1:
            report.check(sequence[0]["data"] == digest["bytes"], f"{what}: unsplit packet is {digest['bytes']:,} bytes on the client too")
        if scenario["kind"] == "bundlechunks" and digest["chunks"] > 1:
            bundles = {p["bundle"] for p in sequence}
            inside = [item["kind"] for item in client["sequence"] if bundles != {None} and item["bundle"] in bundles and item["kind"] != "delimiter"]
            report.check(digest["bundle"] and len(bundles) == 1 and None not in bundles and inside == ["add"] * count,
                         f"{what}: the chunks arrived inside one bundle that holds nothing else (digest bundle={digest['bundle']}, bundles {sorted(bundles, key=str)}, content {inside[:3]}...)")

    # Packet sizes stay within the budget, except entries the mod reported as too big for it on their own.
    exempt = {a["bytes"] for a in server["alone"]}
    too_big = [p for p in packets if p["data"] > max_chunk and not (p["count"] == 1 and p["data"] in exempt)]
    report.check(not too_big, f"{label}: every packet is at most {max_chunk:,} bytes (except reported single oversized entries) {[p['data'] for p in too_big[:3]]}")

    # The split lines and the digest lines describe the same packets.
    for split in server["splits"]:
        report.check(any(d["entries"] == split["entries"] and d["bytes"] == split["bytes"] and d["chunks"] == split["chunks"]
                         and d["replace"] == split["replace"] for d in server["digests"]),
                     f"{label}: split log line ({split['entries']:,} entries, {split['chunks']} chunks) agrees with a digest line")
        report.check(split["limit"] == max_chunk, f"{label}: split log line reports limit {split['limit']:,}")
        if scenario["kind"] == "bundlechunks" and split["chunks"] > 1:
            report.check(split["one_bundle"], f"{label}: split log line says the {split['chunks']} chunks were sent in one bundle")
    check_encode_once(report, label, server, scenario)


def check_mod_log(report, scenario, lines):
    """The mod's own lines: loaded line, ERRORs, the encode-once startup lines."""
    loaded = [m[1] for line in lines if (m := LOADED_RE.search(line))]
    report.check(len(loaded) == 1, f"the mod logged its 'loaded:' line once ({loaded})")
    if scenario["config_check"] == "none" and scenario["kind"] != "perf":
        report.check(loaded == [loaded_line(scenario)], f"the loaded configuration is as written ({loaded_line(scenario)})")
    errors = [line for line in lines if RBS_ERROR_RE.search(line)]
    if scenario["config_check"] == "malformed":
        errors = [line for line in errors if "could not read config" not in line]
    if scenario["kind"] != "undeliverable":
        report.check(not errors, f"no ERROR from the mod {errors[:2]}")
    text = "\n".join(lines)
    encode_once = scenario.get("encode_once", "any")
    if encode_once != "any":
        report.check(bool(ENCODE_OFF_RE.search(text)) == (encode_once == "off"), f"the 'encode once is off' startup line is {'' if encode_once == 'off' else 'not '}logged")
        report.check(bool(VERIFY_ON_RE.search(text)) == (encode_once == "verified"), f"the 'verifyEncodeOnce is on' startup line is {'' if encode_once == 'verified' else 'not '}logged")
    if encode_once in ("reused", "verified"):
        # logged once when the bytes of a measurement cannot be kept: the hook around the codec call did not run, or
        # something else writes into the packet buffer in PacketEncoder.encode
        report.check("[RecipeBookSplitter] encode once is not used" not in text, "no 'encode once is not used' line (the measured bytes could be kept)")
    return loaded


def run_split_scenario(report, scenario, lines):
    specs = [parse_phase(s) for s in scenario["phases"]]
    phases = split_phases(lines)
    report.check(len(phases) >= len(specs), f"server log has {len(phases)} logins (expected {len(specs)})")
    clients = [load_client(WORK / scenario["name"] / f"phase{i}.json") for i in range(1, len(specs) + 1)]
    for i, client in enumerate(clients, 1):
        if not report.check(client is not None, f"phase {i}: client result file exists"):
            return
    servers = [parse_server_phase(p) for p in phases[:len(specs)]]

    known = None  # the recipes in a complete book, learned from the first phase (join and /recipe give)
    for i, ((_, action, _), server, client) in enumerate(zip(specs, servers, clients), 1):
        label = f"phase {i}"
        check_split_phase(report, label, server, client, scenario)
        if action in ("give", "relog", "reload"):
            splits = [s for s in server["splits"] if s["chunks"] >= 2]
            report.check(bool(splits), f"{label}: a 'split ... into N chunks' log line with N >= 2 exists")
        if action == "give" and known is None:
            known = sum(p["count"] for p in client["recipe_book_add"])
            report.note(f"{label} delivered {known:,} recipes in total")
        elif action in ("relog", "reload") and known:
            replace_digests = [d for d in server["digests"] if d["replace"]]
            report.check(bool(replace_digests) and all(d["entries"] == known for d in replace_digests),
                         f"{label}: every replace=true packet carries all {known:,} recipes ({[d['entries'] for d in replace_digests]})")
            replace_packets = [p for p in client["recipe_book_add"] if p["replace"]]
            wanted = 2 if action == "reload" else 1  # the join, plus the reload
            report.check(len(replace_packets) == wanted, f"{label}: the client received {len(replace_packets)} replace=true packet(s), expected {wanted}")

    huge = scenario["huge_entry_bytes"]
    if huge > 0:
        for i, server in enumerate(servers, 1):
            report.check(any(a["bytes"] >= huge and a["bytes"] > FRAME_LIMIT and "network compression lets this connection send it" in a["note"] for a in server["alone"]),
                         f"phase {i}: WARN for the single entry of >= {huge:,} bytes that is bigger than maxChunkBytes, sendable only because of network compression")
            report.check(any(size >= huge for size in server["oversized"]),
                         f"phase {i}: logOversizedPackets WARN for the encoded packet carrying that entry")


def run_baseline_scenario(report, scenario, lines):
    """Without the mod every phase ends in 'Packet too big/large'. The server's own 'lost connection' line is sometimes
    only 'Disconnected' when the client closes the socket first, so the client's disconnect text counts as proof too."""
    specs = scenario["phases"]
    phases = split_phases(lines)
    report.check(len(phases) >= len(specs), f"server log has {len(phases)} logins (expected {len(specs)})")
    for i, phase in enumerate(phases[:len(specs)], 1):
        client = load_client(WORK / scenario["name"] / f"phase{i}.json")
        reason = (client or {}).get("disconnect") or ""
        server_hit = any(BASELINE_ERROR_RE.search(line) for line in phase)
        client_hit = CLIENT_BASELINE_RE.search(reason) is not None
        report.check(server_hit or client_hit,
                     f"phase {i}: the connection ended in 'Packet too big/large' (server log: {server_hit}, client: {client_hit})")
        report.check(client is not None and client["disconnect"] is not None, f"phase {i}: client was disconnected ({reason[:140]})")
        report.check(not any(DIGEST_RE.search(line) for line in phase), f"phase {i}: no digest lines without the mod")


def bundles_of(client):
    """{bundle index: [kinds of the packets inside it, delimiters excluded]} from the client's play sequence."""
    out = {}
    for item in client.get("sequence", []):
        if item["bundle"] is not None and item["kind"] != "delimiter":
            out.setdefault(item["bundle"], []).append(item["kind"])
    return out


def run_bundle_scenario(report, scenario, lines):
    """The test mod sends bundle [remove, add (3000 entries, 9.2 MB), remove]; the mod splits the add in place."""
    client = load_client(WORK / scenario["name"] / "phase1.json")
    if not report.check(client is not None, "client result file exists"):
        return
    server = parse_server_phase(lines)
    max_chunk = scenario["max_chunk_bytes"]
    report.check(client["disconnect"] is None, f"client was not disconnected ({client['disconnect']})")
    report.check(not client["violations"], f"no frame over {FRAME_LIMIT:,} or data over {UNCOMPRESSED_LIMIT:,} bytes {client['violations'][:2]}")
    bundles = bundles_of(client)
    report.note(f"bundles seen by the client: {len(bundles)}; {[(i, len(kinds)) for i, kinds in bundles.items()]}")
    with_adds = {i: kinds for i, kinds in bundles.items() if "add" in kinds}
    if not report.check(len(with_adds) == 1, f"exactly one bundle carries recipe_book_add packets ({len(with_adds)})"):
        return
    index, kinds = next(iter(with_adds.items()))
    adds = [p for p in client["recipe_book_add"] if p["bundle"] == index]
    report.check(kinds[0] == "remove" and kinds[-1] == "remove" and all(k == "add" for k in kinds[1:-1]),
                 f"bundle order is remove, add x {len(kinds) - 2}, remove ({kinds[:3]}...{kinds[-2:]})")
    report.check(len(adds) >= 2, f"the add was split inside the bundle into {len(adds)} packets")
    report.check(sum(p["count"] for p in adds) == 3000, f"the bundle's adds carry all 3,000 entries ({sum(p['count'] for p in adds)})")
    report.check(all(p["data"] <= max_chunk for p in adds), f"every add in the bundle is at most {max_chunk:,} bytes")
    report.check(not any(p["replace"] for p in adds), "replace=false on every chunk, as the original")
    digests = [d for d in server["digests"] if d["bundle"]]
    report.check(len(digests) == 1, f"one digest line with bundle=true ({len(digests)})")
    if digests:
        d = digests[0]
        report.check(bool(find_sequences(without_empty_packets(client), d)), "the bundle digest matches the client's run of adds (same entry bytes)")
        report.check(d["chunks"] == len(adds), f"digest chunks {d['chunks']} == adds received in the bundle {len(adds)}")
    splits = [s for s in server["splits"] if s["in_bundle"]]
    report.check(any(s["chunks"] == len(adds) for s in splits), "a 'split ... in a bundle ... into N chunks' log line")
    report.check(not any(s["one_bundle"] for s in server["splits"]), "no chunks were wrapped in a bundle of their own: the packet was split in place")
    check_encode_once(report, "bundle", {"splits": splits}, scenario)


def run_undeliverable_scenario(report, scenario, lines):
    client = load_client(WORK / scenario["name"] / "phase1.json")
    if not report.check(client is not None, "client result file exists"):
        return
    server = parse_server_phase(lines)
    text = "\n".join(lines)
    dropped, sent_anyway = server["dropped"], server["sent_anyway"]
    report.note(f"dropped: {dropped}; sent anyway: {sent_anyway}")
    adds = client["recipe_book_add"]
    report.note(f"client adds: {[(p['count'], p['data'], p['bundle']) for p in adds]}; disconnect: {client['disconnect']}")
    name = scenario["name"]
    if name == "E9":
        report.check(client["disconnect"] is None, "client was not disconnected")
        report.check(not client["violations"], f"no frame over the limits {client['violations'][:2]}")
        report.check([d["display_id"] for d in dropped] == [2000001, 3000002], "display ids 2000001 and 3000002 were left out, with an ERROR each")
        report.check(all("network compression is off" in d["reason"] for d in dropped), "the reason names the frame limit without compression")
        loose = [p for p in adds if p["bundle"] is None and p["count"] == 2]
        report.check(len(loose) == 1, "the loose packet arrived with its 2 small entries")
        in_bundle = bundles_of(client)
        report.check(any(kinds == ["remove", "add", "remove"] for kinds in in_bundle.values()), f"the bundle arrived as remove, add, remove ({in_bundle})")
        report.check(any(p["bundle"] is not None and p["count"] == 1 for p in adds), "the bundled add kept its small entry")
    elif name == "E9b":
        report.check(client["disconnect"] is None, "client was not disconnected")
        report.check(not client["violations"], f"no frame over the limits {client['violations'][:2]}")
        report.check(len(dropped) == 2, f"two entries left out ({len(dropped)})")
        if len(dropped) == 2:
            report.check("compresses to a" in dropped[0]["reason"], f"3 MB random entry: left out because its compressed frame is too big ({dropped[0]['reason']})")
            report.check("over 8,388,608 bytes" in dropped[1]["reason"], f"9 MB entry: left out because of the 8 MiB limit ({dropped[1]['reason']})")
        report.check(any(a["display_id"] == 2000001 and 4_500_000 <= a["bytes"] < 4_501_000 and "network compression lets this connection send it" in a["note"] for a in server["alone"]),
                     "4.5 MB compressible entry: sent alone with a WARN")
        big = [p for p in adds if p["data"] > 4_000_000]
        report.check(len(big) == 1 and big[0]["count"] == 1, f"the 4.5 MB entry arrived alone ({[(p['count'], p['data']) for p in big]})")
        # The join sends its own (small) recipe packets first; the three commands produce the last five packets:
        # [small, small] (huge dropped), [small] [4.5 MB] [small] (kept, split around it), [small, small] (9 MB dropped).
        tail = adds[-5:]
        report.check([p["count"] for p in tail] == [2, 1, 1, 1, 2] and tail[2]["data"] > 4_000_000,
                     f"the commands' packets arrived as [2], [1], [1 x 4.5 MB], [1], [2] ({[(p['count'], p['data']) for p in tail]})")
    elif name == "E9s":
        report.check(len(sent_anyway) == 1, "ERROR: sending it anyway because undeliverableEntries is send")
        report.check(client["disconnect"] is not None, f"client was disconnected ({client['disconnect']})")
        report.check(re.search(r"Tester lost connection:.*Packet too large", text) is not None or CLIENT_BASELINE_RE.search(client["disconnect"] or "") is not None,
                     "the connection ended in 'Packet too large' (server log or client)")


def run_perf_scenario(report, scenario, lines):
    """give_cycles: Tester takes and gets all recipes again and again while the Prober pings the server every 10 ms.
    The checks are functional; the numbers are printed for comparing jars (no timing thresholds)."""
    specs = [parse_phase(s) for s in scenario["phases"]]
    phases = split_phases(lines)
    report.check(len(phases) >= len(specs), f"server log has {len(phases)} logins (expected {len(specs)})")
    max_chunk = scenario["max_chunk_bytes"]
    clients = [load_client(WORK / scenario["name"] / f"phase{i}.json") for i in range(1, len(specs) + 1)]
    if not all(report.check(c is not None, f"phase {i}: client result file exists") for i, c in enumerate(clients, 1)):
        return
    servers = [parse_server_phase(p) for p in phases[:len(specs)]]
    prober = json.loads((WORK / scenario["name"] / "probe.json").read_text()) if (WORK / scenario["name"] / "probe.json").exists() else None
    report.check(prober is not None and prober["disconnect"] is None, f"the Prober stayed connected ({prober and prober['disconnect']})")
    dropped = [line for line in lines if re.search(r"Prober lost connection: (?!Disconnected$)", line)]
    report.check(not dropped, f"the server did not drop the Prober ({dropped[:1]})")  # the plain 'Disconnected' is the driver stopping it

    for i, ((_, action, arg), client, server) in enumerate(zip(specs, clients, servers), 1):
        report.check(client["disconnect"] is None and not client["violations"], f"phase {i}: Tester not disconnected, no frame over the limits ({client['disconnect']})")
        report.check(all(p["data"] <= max_chunk for p in client["recipe_book_add"]), f"phase {i}: every packet is at most {max_chunk:,} bytes")
        if action == "give_cycles":
            cycles = int(arg)
            books = [r for r in client["runs"] if r["entries"] > 1000]
            report.check(len(books) == cycles and len({r["entries"] for r in books}) == 1,
                         f"phase {i}: {len(books)} complete books of {{{', '.join(str(e) for e in sorted({r['entries'] for r in books}))}}} entries arrived ({cycles} gives)")
            splits = [s for s in server["splits"] if s["chunks"] >= 2]
            report.check(len(splits) >= cycles, f"phase {i}: {len(splits)} split lines for {cycles} gives")
            if splits:
                report.note(f"phase {i}: measure ms median {statistics.median(s['ms'] for s in splits)}, max {max(s['ms'] for s in splits)}"
                            + (f"; write ms median {statistics.median(s['write_ms'] for s in splits)}, max {max(s['write_ms'] for s in splits)}"
                               if all(s["write_ms"] is not None for s in splits) else " (this jar logs no write time)"))
        elif action == "relog":
            report.check(any(s["chunks"] >= 2 for s in server["splits"]), f"phase {i}: the initial book was split")

    if prober is None:
        return
    answered, unanswered = len(prober.get("pings", [])), prober.get("pings_unanswered", 0)
    report.check(answered >= 0.9 * (answered + unanswered), f"at least 90% of the Prober's pings were answered ({answered} of {answered + unanswered})")
    gives = []
    commands = WORK / scenario["name"] / "commands.log"
    if commands.exists():
        for line in commands.read_text().splitlines():
            stamp, _, command = line.partition(" ")
            if command == "recipe give Tester *":
                gives.append(float(stamp))
    worst = []
    for t in gives:
        window = [rtt for sent, rtt in prober["pings"] if t <= sent <= t + 5]
        worst.append(max(window) if window else float("nan"))
    if worst:
        ordered = sorted(w for w in worst if w == w)
        if ordered:
            p90 = ordered[min(len(ordered) - 1, int(0.9 * len(ordered)))]
            report.note(f"Prober's slowest ping within 5 s after each of {len(worst)} gives (ms): median {statistics.median(ordered):.1f}, "
                        f"p90 {p90:.1f}, max {ordered[-1]:.1f}")
        all_rtt = sorted(rtt for _, rtt in prober["pings"])
        report.note(f"all {len(all_rtt)} pings (ms): median {statistics.median(all_rtt):.1f}, p99 {all_rtt[int(0.99 * (len(all_rtt) - 1))]:.1f}, max {all_rtt[-1]:.1f}")


def run_config_checks(report, scenario, all_lines, config_path):
    check = scenario["config_check"]
    text = "\n".join(all_lines)
    initial_path = WORK / scenario["name"] / "config.initial"
    on_disk = config_path.read_text() if config_path.exists() else None
    loaded = next((m[1] for line in all_lines if (m := LOADED_RE.search(line))), "")
    unchanged = lambda: report.check(on_disk == initial_path.read_text(), "the config file was left as written")
    below = "(every chunk makes the client rebuild its recipe book)"
    above = "(a frame holds at most 2,097,151 bytes as sent, and ViaVersion translation was measured to grow a chunk by up to 63%)"
    if check == "created":
        report.check("[RecipeBookSplitter] created default config" in text, "default config file was created (log line)")
        report.check(on_disk == DEFAULT_CONFIG, "the created file has the documented default content")
        report.check(loaded == "maxChunkBytes=1,048,576 (1.0 MiB), logSplits=true, logOversizedPackets=false, undeliverableEntries=drop, bundleChunks=false",
                     f"loaded line shows the defaults ({loaded})")
    elif check == "clamp":
        report.check(f"WARN]: [RecipeBookSplitter] maxChunkBytes 10 is below the minimum 262144 {below}; using 262144" in text,
                     "WARN about clamping maxChunkBytes 10 to 262144")
        unchanged()
        report.check(loaded.startswith("maxChunkBytes=262,144 (0.3 MiB)"), f"loaded line shows 262,144 ({loaded})")
    elif check == "clamp-max":
        report.check(f"WARN]: [RecipeBookSplitter] maxChunkBytes 4000000 is above the maximum 1500000 {above}; using 1500000" in text,
                     "WARN about clamping maxChunkBytes 4000000 to 1500000")
        unchanged()
        report.check(loaded.startswith("maxChunkBytes=1,500,000 (1.4 MiB)"), f"loaded line shows 1,500,000 ({loaded})")
    elif check == "upgrade":
        report.check(f"WARN]: [RecipeBookSplitter] maxChunkBytes 2000000 is above the maximum 1500000 {above}; using 1500000" in text,
                     "WARN about clamping the 1.0.0 maximum 2000000 to 1500000")
        for key, default in (("undeliverableEntries", "drop"), ("bundleChunks", "false")):
            report.check(f"INFO]: [RecipeBookSplitter] '{key}' missing, using default {default}" in text, f"INFO that '{key}' is missing from the 1.0.0 file")
        unchanged()
        report.check(loaded == "maxChunkBytes=1,500,000 (1.4 MiB), logSplits=true, logOversizedPackets=false, undeliverableEntries=drop, bundleChunks=false",
                     f"loaded line shows 1,500,000 and the defaults of the new keys ({loaded})")
    elif check == "invalid":
        report.check("WARN]: [RecipeBookSplitter] 'undeliverableEntries' must be \"drop\" or \"send\", got \"SEND\"; using default \"drop\"" in text,
                     "WARN about the invalid undeliverableEntries")
        report.check("WARN]: [RecipeBookSplitter] 'bundleChunks' must be true or false, got \"yes\"; using default false" in text,
                     "WARN about the invalid bundleChunks")
        unchanged()
        report.check(loaded.endswith("undeliverableEntries=drop, bundleChunks=false"), f"loaded line shows drop and false ({loaded})")
    elif check == "malformed":
        report.check(re.search(r"ERROR\]: \[RecipeBookSplitter\] could not read config .*; using defaults\. The file was left unchanged\.", text) is not None,
                     "ERROR about the unreadable config, defaults used")
        report.check(on_disk == initial_path.read_text(), "the malformed config file was left unchanged")
        report.check(loaded.startswith("maxChunkBytes=1,048,576 "), f"loaded line shows the default budget ({loaded})")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    name = sys.argv[1]
    directory = WORK / name
    scenario = json.loads((directory / "scenario.json").read_text())
    lines = (directory / "server.log").read_text(errors="replace").splitlines()
    report = Report()
    print(f"== checking {name}: {scenario['description']}")

    kind = scenario["kind"]
    if kind == "baseline":
        run_baseline_scenario(report, scenario, lines)
    else:
        check_mod_log(report, scenario, lines)
        if kind in ("split", "bundlechunks"):
            run_split_scenario(report, scenario, lines)
        elif kind == "bundle":
            run_bundle_scenario(report, scenario, lines)
        elif kind == "undeliverable":
            run_undeliverable_scenario(report, scenario, lines)
        elif kind == "perf":
            run_perf_scenario(report, scenario, lines)
        else:
            sys.exit(f"check.py: unknown kind {kind!r}")
        if scenario["config_check"] != "none":
            run_config_checks(report, scenario, lines, directory / "server" / "config" / "recipebooksplitter.json")

    print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
    sys.exit(1 if report.failures else 0)


if __name__ == "__main__":
    main()
