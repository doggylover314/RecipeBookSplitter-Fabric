#!/usr/bin/env python3
"""Checks what one E2E scenario produced. Usage: check.py <scenario>   (reads e2e/work/<scenario>, see run_e2e.sh)

The server log is cut into phases at every "Tester[...] logged in" line and compared with what the protocol client
(client.py) received in the same phase. Exit status 0 if every assertion holds.
"""
import hashlib
import json
import os
import re
import struct
import sys
from pathlib import Path

WORK = Path(os.environ.get("E2E_WORK") or Path(__file__).resolve().parent / "work")

LOGIN_RE = re.compile(r"Tester\[.*\] logged in")
LOADED_RE = re.compile(r"\[RecipeBookSplitter\] loaded: (.*)")
DIGEST_RE = re.compile(r"\[RecipeBookSplitter\] digest player=Tester entries=(\d+) replace=(\w+) bytes=(\d+) chunks=(\d+) sha256=(\w+)")
SPLIT_RE = re.compile(r"\[RecipeBookSplitter\] Tester: split ([\d.]+) MiB recipe book packet \(([\d,]+) bytes, ([\d,]+) entries, replace=(\w+)\) into (\d+) chunks \(largest ([\d,]+) bytes, limit ([\d,]+) bytes")
ALONE_RE = re.compile(r"\[RecipeBookSplitter\] Tester: recipe display entry #(\d+) \(display id (\d+)\) is ([\d,]+) bytes on its own, more than maxChunkBytes \(([\d,]+)\); sending it in a chunk by itself \(([\d,]+) bytes\)(.*)")
OVERSIZED_RE = re.compile(r"\[RecipeBookSplitter\] oversized clientbound packet clientbound/minecraft:recipe_book_add for Tester: [\d.]+ MiB \(([\d,]+) bytes\)")
BASELINE_ERROR_RE = re.compile(r"Tester lost connection:.*(Packet too big|Packet too large)")
RBS_ERROR_RE = re.compile(r"/ERROR\]: \[RecipeBookSplitter\]")

DEFAULT_CONFIG = '{\n  "maxChunkBytes": 1048576,\n  "logSplits": true,\n  "logOversizedPackets": false\n}\n'
FRAME_LIMIT = 2097151


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


def split_phases(lines):
    starts = [i for i, line in enumerate(lines) if LOGIN_RE.search(line)]
    ends = starts[1:] + [len(lines)]
    return [lines[s:e] for s, e in zip(starts, ends)]


def parse_server_phase(lines):
    phase = {"lines": lines, "digests": [], "splits": [], "alone": [], "oversized": []}
    for line in lines:
        if m := DIGEST_RE.search(line):
            phase["digests"].append({"entries": int(m[1]), "replace": m[2] == "true", "bytes": int(m[3]),
                                     "chunks": int(m[4]), "sha256": m[5]})
        if m := SPLIT_RE.search(line):
            phase["splits"].append({"bytes": number(m[2]), "entries": number(m[3]), "replace": m[4] == "true",
                                    "chunks": int(m[5]), "largest": number(m[6]), "limit": number(m[7])})
        if m := ALONE_RE.search(line):
            phase["alone"].append({"bytes": number(m[3]), "chunk_bytes": number(m[5]), "note": m[6]})
        if m := OVERSIZED_RE.search(line):
            phase["oversized"].append(number(m[1]))
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


def find_sequences(client, digest):
    """(first packet index, packet count) of every sequence of the client's recipe_book_add packets that carries
    exactly the entries of this server digest."""
    packets = client["recipe_book_add"]
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


def check_split_phase(report, label, server, client, scenario):
    """Assertions for one phase of a scenario in which the mod is active."""
    max_chunk = scenario["max_chunk_bytes"]
    packets = client["recipe_book_add"]

    report.check(client["disconnect"] is None, f"{label}: client was not disconnected ({client['disconnect']})")
    report.check(not client["violations"], f"{label}: no frame over {FRAME_LIMIT:,} or data over 8,388,608 bytes {client['violations'][:2]}")
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

    # Packet sizes stay within the budget, except entries the mod reported as too big for it on their own.
    exempt = {a["chunk_bytes"] for a in server["alone"]}
    too_big = [p for p in packets if p["data"] > max_chunk and not (p["count"] == 1 and p["data"] in exempt)]
    report.check(not too_big, f"{label}: every packet is at most {max_chunk:,} bytes (except reported single oversized entries) {[p['data'] for p in too_big[:3]]}")

    # The split lines and the digest lines describe the same packets.
    for split in server["splits"]:
        report.check(any(d["entries"] == split["entries"] and d["bytes"] == split["bytes"] and d["chunks"] == split["chunks"]
                         and d["replace"] == split["replace"] for d in server["digests"]),
                     f"{label}: split log line ({split['entries']:,} entries, {split['chunks']} chunks) agrees with a digest line")
        report.check(split["limit"] == max_chunk, f"{label}: split log line reports limit {split['limit']:,}")


def run_split_scenario(report, scenario, lines):
    phases = split_phases(lines)
    report.check(len(phases) >= scenario["phases"], f"server log has {len(phases)} logins (expected {scenario['phases']})")
    clients = [load_client(WORK / scenario["name"] / f"phase{i}.json") for i in range(1, scenario["phases"] + 1)]
    for i, client in enumerate(clients, 1):
        if not report.check(client is not None, f"phase {i}: client result file exists"):
            return
    servers = [parse_server_phase(p) for p in phases[:scenario["phases"]]]

    # Phase 1: join and /recipe give. Everything the client was sent adds up to the full recipe book.
    expected_known = sum(p["count"] for p in clients[0]["recipe_book_add"])
    report.note(f"phase 1 delivered {expected_known:,} recipes in total")

    for i, (server, client) in enumerate(zip(servers, clients), 1):
        label = f"phase {i}"
        check_split_phase(report, label, server, client, scenario)
        if i <= 2:
            splits = [s for s in server["splits"] if s["chunks"] >= 2]
            report.check(bool(splits), f"{label}: a 'split ... into N chunks' log line with N >= 2 exists")
        if i >= 2:
            replace_digests = [d for d in server["digests"] if d["replace"]]
            report.check(bool(replace_digests) and all(d["entries"] == expected_known for d in replace_digests),
                         f"{label}: every replace=true packet carries all {expected_known:,} recipes "
                         f"({[d['entries'] for d in replace_digests]})")
            replace_packets = [p for p in client["recipe_book_add"] if p["replace"]]
            wanted = 2 if i == 3 else 1  # the join, plus the reload in phase 3
            report.check(len(replace_packets) == wanted, f"{label}: the client received {len(replace_packets)} replace=true packet(s), expected {wanted}")

    if scenario["huge_entry_bytes"] > 0:
        huge = scenario["huge_entry_bytes"]
        for i, server in enumerate(servers, 1):
            report.check(any(a["bytes"] >= huge and a["chunk_bytes"] > FRAME_LIMIT and "frame limit" in a["note"] for a in server["alone"]),
                         f"phase {i}: WARN for the single entry of >= {huge:,} bytes that is bigger than maxChunkBytes")
            report.check(any(size >= huge for size in server["oversized"]),
                         f"phase {i}: logOversizedPackets WARN for the encoded packet carrying that entry")


def run_baseline_scenario(report, scenario, lines):
    phases = split_phases(lines)
    report.check(len(phases) >= scenario["phases"], f"server log has {len(phases)} logins (expected {scenario['phases']})")
    for i, phase in enumerate(phases[:scenario["phases"]], 1):
        client = load_client(WORK / scenario["name"] / f"phase{i}.json")
        report.check(any(BASELINE_ERROR_RE.search(line) for line in phase),
                     f"phase {i}: server reports 'lost connection ... Packet too big/large' without the mod")
        report.check(client is not None and client["disconnect"] is not None,
                     f"phase {i}: client was disconnected ({client['disconnect'] if client else 'no result'})")
        report.check(not any(DIGEST_RE.search(line) for line in phase), f"phase {i}: no digest lines without the mod")


def run_config_checks(report, scenario, all_lines, config_path):
    check = scenario["config_check"]
    text = "\n".join(all_lines)
    initial_path = WORK / scenario["name"] / "config.initial"
    on_disk = config_path.read_text() if config_path.exists() else None
    loaded = next((m[1] for line in all_lines if (m := LOADED_RE.search(line))), "")
    if check == "created":
        report.check("[RecipeBookSplitter] created default config" in text, "default config file was created (log line)")
        report.check(on_disk == DEFAULT_CONFIG, "the created file has the documented default content")
        report.check(loaded == "maxChunkBytes=1,048,576 (1.0 MiB), logSplits=true, logOversizedPackets=false", f"loaded line shows the defaults ({loaded})")
    elif check == "clamp":
        report.check(re.search(r"WARN\]: \[RecipeBookSplitter\] maxChunkBytes 10 is below the minimum 65536; using 65536", text) is not None,
                     "WARN about clamping maxChunkBytes 10 to 65536")
        report.check(on_disk == initial_path.read_text(), "the config file was left as written")
        report.check(loaded.startswith("maxChunkBytes=65,536 "), f"loaded line shows 65,536 ({loaded})")
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

    if scenario["kind"] == "baseline":
        run_baseline_scenario(report, scenario, lines)
    else:
        mod_loaded = [m[1] for line in lines if (m := LOADED_RE.search(line))]
        report.check(len(mod_loaded) == 1, f"the mod logged its 'loaded:' line ({mod_loaded})")
        if scenario["config_check"] == "none":
            expected = f"maxChunkBytes=1,048,576 (1.0 MiB), logSplits=true, logOversizedPackets={str(scenario['log_oversized']).lower()}"
            report.check(mod_loaded == [expected], f"the loaded configuration is as written ({expected})")
        errors = [line for line in lines if RBS_ERROR_RE.search(line)]
        if scenario["config_check"] == "malformed":
            errors = [line for line in errors if "could not read config" not in line]
        report.check(not errors, f"no ERROR from the mod {errors[:2]}")
        run_split_scenario(report, scenario, lines)
        if scenario["config_check"] != "none":
            run_config_checks(report, scenario, lines, directory / "server" / "config" / "recipebooksplitter.json")

    print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
    sys.exit(1 if report.failures else 0)


if __name__ == "__main__":
    main()
