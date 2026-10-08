#!/usr/bin/env python3
"""Checks one Polymer e2e scenario. Usage: check_poly.py <scenario>   (reads E2E_WORK/<scenario>, see run_e2e.sh)

It reuses the kit's check.py for the generic assertions (the server's digest lines against what the protocol client
received, replace flags, packet sizes against the budget) and adds the Polymer-specific ones, which compare four views
of the same recipe book:

  bare   Entry.STREAM_CODEC on a fresh buffer outside any packet-tweaker context (/polytest measure, "bare_*")
  ctx    the same inside the player's PacketContext (/polytest measure, "ctx_*")
  mod    what the Recipe Book Splitter measured with the connection's real PacketEncoder (its digest log line)
  client what the protocol client received (entry bytes)

Exit status 0 if every assertion holds. Also writes analysis.json into the scenario directory.
"""
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402  (the kit's check.py; only imported, its main() is not run)

WORK = check.WORK
MEASURE_RE = re.compile(
    r"\[polytest\] measure player=(\S+) entries=(\d+) bare_entry_bytes=(\d+) bare_sha256=(\w+) ctx_entry_bytes=(\d+) "
    r"ctx_sha256=(\w+) bare_packet_payload=(\d+) identical=(\w+) bare_ms=(\d+) ctx_ms=(\d+) send=(\w+)")
PACKET_ID_BYTES = 1  # recipe_book_add is packet 0x48, a one-byte VarInt
REPLACE_BYTES = 1


def varint_size(n):
    size = 1
    while n >= 0x80:
        n >>= 7
        size += 1
    return size


def plan(sizes, fixed, budget):
    """The Recipe Book Splitter's greedy packing (ChunkPlanner.plan) on the given per-entry sizes: [(start, end)]."""
    chunks, start, total = [], 0, 0
    for i, size in enumerate(sizes):
        if i > start and fixed + varint_size(i - start + 1) + total + size > budget:
            chunks.append((start, i))
            start, total = i, 0
        total += size
    chunks.append((start, len(sizes)))
    return chunks


def chunk_bytes(sizes, fixed, chunk):
    return fixed + varint_size(chunk[1] - chunk[0]) + sum(sizes[chunk[0]:chunk[1]])


def load_measure(scenario_dir, n):
    path = scenario_dir / "server" / f"polytest-measure-{n}.json"
    return json.loads(path.read_text()) if path.exists() else None


def printable(data):
    return re.sub(rb"[^\x20-\x7e]+", b".", data).decode("ascii")


def kinds_of(scenario_dir):
    path = scenario_dir / "server" / "world" / "datapacks" / "polypack" / "kinds.json"
    return json.loads(path.read_text()) if path.exists() else {}


def stats(values):
    values = sorted(values)
    if not values:
        return {"n": 0}
    return {"n": len(values), "sum": sum(values), "min": values[0], "mean": round(sum(values) / len(values), 1),
            "max": values[-1], "p50": values[len(values) // 2]}


def by_kind(measure, kinds):
    """{kind: (bare sizes, ctx sizes)}; kind is the generator's recipe kind, otherwise the namespace of the recipe."""
    out = {}
    for _ns, recipe_id, bare, ctx in measure["rows"]:
        kind = kinds.get(recipe_id) or ("polytest-demo" if recipe_id.startswith("polydemo:") else recipe_id.split(":")[0])
        out.setdefault(kind, ([], []))
        out[kind][0].append(bare)
        out[kind][1].append(ctx)
    return out


def run_baseline(report, scenario, lines):
    """The kit's baseline check (a phase ends in 'Packet too big/large'), plus the sizes vanilla's encoder complained about."""
    check.run_baseline_scenario(report, scenario, lines)
    sizes = []
    for i in range(1, len(scenario["phases"]) + 1):
        client = check.load_client(WORK / scenario["name"] / f"phase{i}.json")
        if m := re.search(r"Packet too big \(is (\d+), should be less than 8388608\)", (client or {}).get("disconnect") or ""):
            sizes.append(int(m[1]))
    report.note(f"sizes vanilla's encoder complained about: {sizes}")


def run_polymer_split(report, scenario, servers, clients, phases, known, max_chunk, analysis):
    """Polymer's own splitter (split_recipe_book_packet_amount = N) sends chunks of N entries, preceded by an empty
    replace=true packet; each chunk then passes through this mod, which splits it further if it is over the budget."""
    n = scenario["polymer_split"]
    measures = [m for phase in phases[:3] for line in phase if (m := MEASURE_RE.search(line))]
    report.check(len(measures) == 2, f"/polytest measure ran twice ({len(measures)} log lines)")
    for i in (1, 2, 3):
        digests = [d for d in servers[i - 1]["digests"] if not d["replace"] or d["entries"] > 0]
        report.check(all(d["entries"] <= n for d in digests), f"phase {i}: every measured packet has at most {n} entries, Polymer's chunk size (max {max(d['entries'] for d in digests)})")
        report.check(not any(d["replace"] for d in digests), f"phase {i}: the mod saw no replace=true packet with entries; Polymer's own replace packet is empty")
        if i >= 2:
            wanted = known * (1 if i == 2 else 2)  # phase 3 has two books: the join and the one /polytest measure sent
            report.check(sum(d["entries"] for d in digests) == wanted, f"phase {i}: the measured packets together carry {wanted:,} recipes ({sum(d['entries'] for d in digests):,})")
        split_further = [d for d in digests if d["chunks"] > 1]
        report.check(bool(split_further), f"phase {i}: this mod split {len(split_further)} of Polymer's {len(digests)} chunks further (the others fitted and were passed on whole)")
        biggest = max(d["bytes"] for d in digests)
        report.note(f"phase {i}: Polymer's biggest count-based chunk is {biggest:,} bytes{' (over the 8,388,608-byte limit: sent alone it would disconnect the player)' if biggest > 8388608 else ''}")
        analysis[f"phase{i}_polymer_chunks"] = len(digests)
        analysis[f"phase{i}_mod_split_further"] = len(split_further)
        analysis[f"phase{i}_polymer_biggest_chunk_bytes"] = biggest
        analysis[f"phase{i}_client_packets"] = len(clients[i - 1]["recipe_book_add"])
    # Client view of phase 2: an empty replace packet first, then every recipe exactly once.
    packets = clients[1]["recipe_book_add"]
    report.check(packets and packets[0]["replace"] and packets[0]["count"] == 0, "phase 2: the client's first recipe packet is Polymer's empty replace=true packet")
    report.check(sum(p["count"] for p in packets) == known, f"phase 2: the client received {known:,} recipes in total ({sum(p['count'] for p in packets):,})")
    # Phase 3: the entries sent by /polytest measure send, in Polymer's chunks and ours, concatenate to what was sized.
    packets3 = clients[2]["recipe_book_add"]
    replace_at = [k for k, p in enumerate(packets3) if p["replace"]]
    if report.check(len(replace_at) == 2 and clients[2]["bin"] is not None and len(clients[2]["bin"]) == len(packets3), "phase 3: two empty-or-full replace packets (join, measure send) and the entry bytes were recorded"):
        tail = clients[2]["bin"][replace_at[1]:]
        sha = hashlib.sha256()
        for chunk in tail:
            sha.update(chunk)
        total = sum(p["count"] for p in packets3[replace_at[1]:])
        report.check(total == known and sha.hexdigest() == measures[1][6],
                     f"phase 3: the packets after the second replace packet carry {total:,} entries whose bytes hash to the in-context digest of /polytest measure")
    for i, client in enumerate(clients, 1):
        too_big = [p["data"] for p in client["recipe_book_add"] if p["data"] > max_chunk]
        report.check(not too_big, f"phase {i}: every packet is at most {max_chunk:,} bytes {too_big[:3]}")
    (WORK / scenario["name"] / "analysis.json").write_text(json.dumps(analysis, indent=1))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    name = sys.argv[1]
    directory = WORK / name
    scenario = json.loads((directory / "scenario.json").read_text())
    lines = (directory / "server.log").read_text(errors="replace").splitlines()
    report = check.Report()
    print(f"== checking {name}: {scenario['description']}")

    if scenario["kind"] == "baseline":
        run_baseline(report, scenario, lines)
        print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
        sys.exit(1 if report.failures else 0)

    max_chunk = scenario["max_chunk_bytes"]
    analysis = {"scenario": name, "max_chunk_bytes": max_chunk}

    check.check_mod_log(report, scenario, lines)
    report.check(any("[polytest] registered" in line for line in lines), "the rbs-polytest mod registered its Polymer items")

    phases = check.split_phases(lines)
    report.check(len(phases) >= 3, f"server log has {len(phases)} logins (expected 3)")
    third = check.parse_phase(scenario["phases"][2])[1]  # send (a book sent by /polytest measure), reload or locale (Polymer resends)
    resend = third in ("reload", "locale")
    clients = [check.load_client(WORK / name / f"phase{i}.json") for i in (1, 2, 3)]
    if not all(report.check(c is not None, f"phase {i}: client result file exists") for i, c in enumerate(clients, 1)):
        sys.exit(1)
    servers = [check.parse_server_phase(p) for p in phases[:3]]
    known = sum(p["count"] for p in clients[0]["recipe_book_add"])
    report.note(f"phase 1 delivered {known:,} recipes in total")
    analysis["recipes_known"] = known

    # Generic assertions per phase.
    for i, (server, client) in enumerate(zip(servers, clients), 1):
        label = f"phase {i}"
        check.check_split_phase(report, label, server, client, scenario)
        replace_packets = [p for p in client["recipe_book_add"] if p["replace"]]
        report.check(len(replace_packets) == {1: 1, 2: 1, 3: 2}[i],
                     f"{label}: the client received {len(replace_packets)} replace=true packet(s), expected {({1: 1, 2: 1, 3: 2})[i]}")
        if i >= 2 and not scenario.get("polymer_split"):
            replace_digests = [d for d in server["digests"] if d["replace"]]
            report.check(bool(replace_digests) and all(d["entries"] == known for d in replace_digests),
                         f"{label}: every replace=true packet carries all {known:,} recipes ({[d['entries'] for d in replace_digests]})")

    # Evidence that Polymer's rewriting is on the wire: its marker in the client's bytes.
    polymer_markers = {}
    for i, client in enumerate(clients, 1):
        blob = b"".join(client["bin"] or [])
        polymer_markers[i] = blob.count(b"$polymer:stack")
    analysis["polymer_stack_markers_in_client_bytes"] = polymer_markers
    report.note(f"client bytes containing the Polymer marker '$polymer:stack': {polymer_markers}")
    if not scenario["vanilla_twin"] and not scenario["real_mods"]:
        report.check(all(n > 1000 for n in polymer_markers.values()), "the client's recipe book bytes contain Polymer's rewritten item stacks in every phase")

    if scenario.get("polymer_split"):
        run_polymer_split(report, scenario, servers, clients, phases, known, max_chunk, analysis)
        print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
        sys.exit(1 if report.failures else 0)

    if resend:
        # Phase 3 is a relog plus a second complete book, from /reload or from Polymer's resend after the client changed its
        # language: two replace=true packets, both with every recipe; /polytest measure ran once.
        measures = [m for phase in phases[:3] for line in phase if (m := MEASURE_RE.search(line))]
        report.check(len(measures) == 1, f"/polytest measure ran once ({len(measures)} log lines)")
        replace_digests = [d for d in servers[2]["digests"] if d["replace"]]
        report.check(len(replace_digests) == 2 and all(d["entries"] == known for d in replace_digests),
                     f"phase 3: the join and the {'reload' if third == 'reload' else 'language change'} each sent all {known:,} recipes ({[d['entries'] for d in replace_digests]})")
        report.check(all(d["chunks"] > 1 for d in replace_digests), "phase 3: both were split")
        if third == "locale":
            report.check("locale_switched_at" in clients[2], "phase 3: the client announced its new language")
            if len(replace_digests) == 2:
                first, second = replace_digests
                same = first["sha256"] == second["sha256"]
                report.note(f"the book after the language change is {'byte-identical to' if same else 'different from'} the one sent at the join "
                            f"({second['bytes']:,} vs {first['bytes']:,} bytes)")
                analysis["language_change_book_identical"] = same
                analysis["language_change_bytes"] = [first["bytes"], second["bytes"]]
        (directory / "analysis.json").write_text(json.dumps(analysis, indent=1))
        print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
        sys.exit(1 if report.failures else 0)

    # What the measurement saw.
    measures = [m for phase in phases[:3] for line in phase if (m := MEASURE_RE.search(line))]
    report.check(len(measures) == 2, f"/polytest measure ran twice ({len(measures)} log lines)")
    files = [load_measure(directory, n) for n in (1, 2)]
    if len(measures) != 2 or None in files:
        print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
        sys.exit(1 if report.failures else 0)

    bound = scenario["polytest_bound"]
    real = bool(scenario.get("real_mods"))
    fixed = PACKET_ID_BYTES + REPLACE_BYTES
    for idx, (m, f) in enumerate(zip(measures, files)):
        entries, bare_total, bare_sha, ctx_total, ctx_sha = int(m[2]), int(m[3]), m[4], int(m[5]), m[6]
        label = f"measure #{idx + 1} ({'send' if m[11] == 'true' else 'no send'})"
        report.check(entries == known, f"{label}: sized all {known:,} recipes ({entries:,})")
        report.check(f["bare_total"] == bare_total and f["ctx_total"] == ctx_total, f"{label}: JSON file agrees with the log line")
        report.check(int(m[7]) == varint_size(entries) + bare_total + REPLACE_BYTES,
                     f"{label}: whole packet through its own codec = VarInt(count) + entries + replace flag ({int(m[7]):,})")
        if real:
            # Real mods: whatever they do is a finding, not an assertion.
            report.note(f"{label}: bare {bare_total:,} bytes ({bare_sha[:12]}), in-context {ctx_total:,} bytes ({ctx_sha[:12]}): "
                        f"{'identical' if bare_sha == ctx_sha else 'DIFFERENT'}")
        elif bound == 0:
            report.check(bare_sha == ctx_sha and bare_total == ctx_total,
                         f"{label}: bare codec output is byte-identical to the in-context one ({bare_total:,} bytes), static Polymer items")
        else:
            report.check(bare_sha != ctx_sha and bare_total < ctx_total,
                         f"{label}: bare codec output differs from the in-context one and is smaller ({bare_total:,} vs {ctx_total:,} bytes), player-bound items")

    # The mod's measurement against the context-aware one: same totals, and for the packet that /polytest measure sent,
    # the same digest.
    first, second = measures[0], measures[1]
    join_digests = [d for d in servers[1]["digests"] if d["replace"]]
    expected_total = fixed + varint_size(int(first[2])) + int(first[5])
    report.check(any(d["bytes"] == expected_total for d in join_digests),
                 f"phase 2: the mod measured the join packet at {expected_total:,} bytes = in-context sum + framing "
                 f"(mod: {[d['bytes'] for d in join_digests]})")
    sent_digests = [d for d in servers[2]["digests"] if d["replace"] and d["sha256"] == second[6]]
    report.check(len(sent_digests) == 1, "phase 3: the mod's digest of the packet that /polytest measure sent equals the in-context digest")
    if bound > 0 or (real and second[4] != second[6]):
        report.check(all(d["sha256"] != second[4] for d in servers[2]["digests"]), "phase 3: no mod digest equals the bare digest")
    if sent_digests:
        seqs = check.find_sequences(clients[2], sent_digests[0])
        report.check(bool(seqs), "phase 3: the client received exactly those entry bytes (digest matches a client packet sequence)")
        if seqs:
            first_packet, count = seqs[0]
            client_entry_bytes = sum(p["entry_bytes"] for p in clients[2]["recipe_book_add"][first_packet:first_packet + count])
            report.check(client_entry_bytes == int(second[5]),
                         f"phase 3: client entry bytes = in-context sum = {int(second[5]):,} (client {client_entry_bytes:,})")
            analysis["client_entry_bytes_phase3"] = client_entry_bytes
            analysis["client_packets_phase3"] = count
            analysis["client_max_data_phase3"] = max(p["data"] for p in clients[2]["recipe_book_add"][first_packet:first_packet + count])

    # Would a planner that trusted the bare numbers have kept to the budget?
    sizes_bare = [r[2] for r in files[1]["rows"]]
    sizes_ctx = [r[3] for r in files[1]["rows"]]
    naive = {}
    for budget in sorted({max_chunk, 262144, 1048576}):
        chunks = plan(sizes_bare, fixed, budget)
        true_sizes = [chunk_bytes(sizes_ctx, fixed, c) for c in chunks]
        single = [c for c in chunks if c[1] - c[0] == 1]
        over = [s for c, s in zip(chunks, true_sizes) if s > budget and c[1] - c[0] > 1]
        good_chunks = plan(sizes_ctx, fixed, budget)
        naive[budget] = {"naive_chunks": len(chunks), "naive_true_max": max(true_sizes), "naive_over_budget_multi_entry_chunks": len(over),
                         "context_aware_chunks": len(good_chunks),
                         "context_aware_max": max(chunk_bytes(sizes_ctx, fixed, c) for c in good_chunks)}
        report.note(f"budget {budget:,}: planning on bare sizes gives {len(chunks)} chunks, true largest {max(true_sizes):,} bytes, "
                    f"{len(over)} multi-entry chunk(s) over budget; planning on in-context sizes gives {len(good_chunks)} chunks")
    analysis["naive_planning"] = naive
    if real:
        pass  # reported above; for real mods the result is whatever it is
    elif bound == 0:
        report.check(all(v["naive_over_budget_multi_entry_chunks"] == 0 for v in naive.values()),
                     "planning on the bare sizes would have kept every multi-entry chunk within the budget (identical numbers)")
    else:
        report.check(naive[max_chunk]["naive_over_budget_multi_entry_chunks"] > 0,
                     f"planning on the bare sizes would have produced chunks over the {max_chunk:,}-byte budget")

    # Polymer effect per recipe kind, and against the vanilla twin if PV ran.
    kinds = kinds_of(directory)
    here = by_kind(files[1], kinds)
    twin_dir = WORK / "PV"
    twin_measure = load_measure(twin_dir, 2) if scenario["name"] != "PV" else None
    twin = by_kind(twin_measure, kinds_of(twin_dir)) if twin_measure else {}
    table = {}
    for kind, (bare, ctx) in sorted(here.items()):
        row = {"bare": stats(bare), "ctx": stats(ctx)}
        if kind in twin:
            row["vanilla_twin"] = stats(twin[kind][1])
            row["ratio_mean"] = round(row["ctx"]["mean"] / max(row["vanilla_twin"]["mean"], 1e-9), 1)
        table[kind] = row
    analysis["by_kind"] = table
    analysis["totals"] = {"bare": files[1]["bare_total"], "ctx": files[1]["ctx_total"], "entries": files[1]["entries"],
                          "vanilla_twin_total": twin_measure["ctx_total"] if twin_measure else None}
    for kind, row in table.items():
        extra = f", vanilla twin mean {row['vanilla_twin']['mean']} ({row['ratio_mean']}x)" if "vanilla_twin" in row else ""
        report.note(f"kind {kind:18s} n={row['ctx']['n']:5d} mean {row['ctx']['mean']:>9} max {row['ctx']['max']:>8} bytes{extra}")

    # A closer look at entries whose bare and in-context bytes differ.
    samples = []
    for diff in files[1].get("diffs", []):
        bare_bytes, ctx_bytes = base64.b64decode(diff["bare"]), base64.b64decode(diff["ctx"])
        prefix = 0
        while prefix < min(len(bare_bytes), len(ctx_bytes)) and bare_bytes[prefix] == ctx_bytes[prefix]:
            prefix += 1
        suffix = 0
        while suffix < min(len(bare_bytes), len(ctx_bytes)) - prefix and bare_bytes[-1 - suffix] == ctx_bytes[-1 - suffix]:
            suffix += 1
        samples.append({"id": diff["id"], "bare_len": len(bare_bytes), "ctx_len": len(ctx_bytes), "common_prefix": prefix, "common_suffix": suffix,
                        "bare_middle": printable(bare_bytes[prefix:len(bare_bytes) - suffix]), "ctx_middle": printable(ctx_bytes[prefix:len(ctx_bytes) - suffix])})
        report.note(f"differing entry {diff['id']}: bare {len(bare_bytes)} B, in-context {len(ctx_bytes)} B; first difference at byte {prefix}")
        report.note(f"  bare middle: {samples[-1]['bare_middle'][:300]!r}")
        report.note(f"  ctx  middle: {samples[-1]['ctx_middle'][:300]!r}")
    analysis["differing_samples"] = samples

    times = [int(m[9]) for m in measures], [int(m[10]) for m in measures]
    analysis["measure_command_ms"] = {"bare": times[0], "ctx": times[1]}
    analysis["mod_split_ms"] = [split["ms"] for server in servers for split in server["splits"]]

    if scenario["name"] == "P3":
        for i, server in enumerate(servers, 1):
            report.check(len(server["alone"]) >= 6, f"phase {i}: WARN for the entries that are over the budget on their own ({len(server['alone'])} lines)")

    (directory / "analysis.json").write_text(json.dumps(analysis, indent=1))
    print(f"{'PASS' if report.failures == 0 else 'FAIL'} {name} ({report.failures} failed assertion(s))")
    sys.exit(1 if report.failures else 0)


if __name__ == "__main__":
    main()
