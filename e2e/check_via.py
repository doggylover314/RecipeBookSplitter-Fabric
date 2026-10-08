#!/usr/bin/env python3
"""Checks one ViaFabric scenario. Usage: check_via.py <scenario>   (reads E2E_WORK/<scenario>, see run_e2e.sh)

The server log is cut into phases at every "Tester[...] logged in" line and compared with what the protocol client
(client.py) received in the same phase. Phases are '774/<action>' (a client of the server's own version, no translation)
or 'newer/<action>' (the scenario's newer protocol, translated by ViaVersion).

Split scenarios: a 774 client is the control for the sizes. Its recipe_book_add packets are exactly what the server's
encoder produced (ViaVersion does not touch packets for a client of the server's own version), so its data sizes are the
server-side chunk sizes. The newer-protocol client gets the same chunks translated by ViaVersion; every one of them must
stay within the 8,388,608-byte uncompressed and 2,097,151-byte frame limits, have the same entry count and replace flag
as its control chunk, and the translation growth is printed. With bundleChunks (VC) the chunks of each book must arrive
inside one bundle, translated or not.
Baseline scenarios: the newer-protocol client must be disconnected with "Packet too big / too large" on the server.
Limit scenarios (VW3x) document a ceiling that is too high for a translated book. They are not baselines: the mod runs
and must have split the book as configured, and the client must be disconnected by a packet that is too large for a frame
(2,097,151 bytes) although it is smaller than the whole book, so that the packet that failed is a translated chunk.
Exit status 0 if every assertion holds. Writes <scenario>/via-summary.json.
"""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402  (the kit's check.py; only imported, its main() is not run)

WORK = check.WORK
FRAME_LIMIT = check.FRAME_LIMIT
UNCOMPRESSED_LIMIT = check.UNCOMPRESSED_LIMIT
BASELINE_ERROR_RE = re.compile(r"(Packet too big|Packet too large|unable to fit)")
# The frame encoder's text: "Packet too large: size 2416673 is over 8" (the 8 is the VarInt length limit it prints).
FRAME_ERROR_RE = re.compile(r"Packet too large: size (\d+)")
NOISE_RE = re.compile(r"No key layers|SERVER IS RUNNING IN OFFLINE|The server will make no attempt|While this makes the game possible|To change this|There is a newer plugin version")


def finish(r, w, summary):
    (w / "via-summary.json").write_text(json.dumps(summary, indent=1))
    print("RESULT:", "ALL PASS" if r.failures == 0 else f"{r.failures} FAILED")
    sys.exit(1 if r.failures else 0)


def big(c):
    """The packets of the big book: everything except tiny packets (the initial book of a player without recipes)."""
    return [p for p in c["recipe_book_add"] if p["count"] > 1]


def family(action):
    return "relog" if action == "relog" else "give"


def check_baseline(r, specs, clients, server_phases, summary):
    for k, (_, action, _) in enumerate(specs):
        c = clients[k]
        r.check(c is not None, f"phase {k + 1} ({specs[k][0]}/{action}) produced a result")
        if c is None:
            continue
        reasons = server_phases[k]["lost"] if k < len(server_phases) else []
        ok = c["disconnect"] is not None or any(BASELINE_ERROR_RE.search(x) for x in reasons)
        r.check(ok, f"phase {k + 1} ({action}, client {c['client_version']}): client disconnected ({c['disconnect']!r})")
        r.check(k < len(server_phases) and (any(BASELINE_ERROR_RE.search(x) for x in reasons)
                                            or any(BASELINE_ERROR_RE.search(line) for line in server_phases[k]["lines"])
                                            or check.CLIENT_BASELINE_RE.search(c["disconnect"] or "") is not None),
                f"phase {k + 1}: the connection ended in 'Packet too big/large': {reasons[:1]}")
        summary["phases"].append({"phase": k + 1, "spec": specs[k], "disconnect": c["disconnect"], "server_lost": reasons})


def check_limit(r, scenario, specs, clients, server_phases, summary):
    """VW3x: every phase must show a book the mod split within the budget, and a client that was disconnected by a packet
    over the frame limit that is smaller than the whole book. Without the mod, or with a mod that splits nothing, the
    unsplit book is the packet that fails and its size is the book's size, so this cannot pass on a baseline."""
    budget = scenario["max_chunk_bytes"]
    for k, (_, action, _) in enumerate(specs):
        label = f"phase {k + 1} ({action}, client {scenario['newer_protocol']})"
        client = clients[k]
        if not r.check(client is not None and k < len(server_phases), f"{label}: produced a result and a server log phase"):
            continue
        server = server_phases[k]
        splits = [x for x in server["splits"] if x["chunks"] >= 2]
        r.check(bool(splits) and all(x["limit"] == budget and x["largest"] <= budget for x in splits),
                f"{label}: the mod split the book into >= 2 chunks of at most {budget:,} bytes "
                f"({[(x['chunks'], x['largest']) for x in splits]})")
        books = [d for d in server["digests"] if d["entries"] > 1]
        r.check(bool(books), f"{label}: a digest line for the book")
        total = max((d["bytes"] for d in books), default=0)
        largest = max((x["largest"] for x in splits), default=0)
        reasons = [client["disconnect"] or ""] + server["lost"]
        sizes = [int(m[1]) for text in reasons if (m := FRAME_ERROR_RE.search(text))]
        failed = sizes[0] if sizes else 0
        r.check(failed > 0, f"{label}: the client was disconnected by 'Packet too large: size N' ({client['disconnect']!r})")
        if failed:
            r.check(FRAME_LIMIT < failed < total and failed > largest,
                    f"{label}: the failing packet of {failed:,} bytes is over the frame limit {FRAME_LIMIT:,}, grew from a chunk "
                    f"(largest {largest:,}) and is smaller than the whole book ({total:,} bytes): a translated chunk")
        over = [p["data"] for p in client["recipe_book_add"] if p["data"] > budget]
        r.check(not over, f"{label}: no recipe_book_add over {budget:,} bytes arrived before the disconnect ({over[:2]})")
        summary["phases"].append({"phase": k + 1, "spec": specs[k], "disconnect": client["disconnect"], "failing_packet_bytes": failed,
                                  "book_bytes": total, "largest_chunk": largest})


def check_one_phase(r, scenario, k, spec, client, server, newer):
    """The assertions every split phase has, whether the client is translated or not."""
    proto, action, _ = spec
    label = f"phase {k + 1} ({'client ' + str(newer) if proto == 'newer' else 'control 774'}, {action})"
    r.check(client["disconnect"] is None, f"{label}: no disconnect ({client['disconnect']!r})")
    r.check(not client["violations"], f"{label}: no frame over {FRAME_LIMIT:,} / data over {UNCOMPRESSED_LIMIT:,} bytes ({client['violations'][:2]})")
    b = big(client)
    r.check(len(b) >= 2, f"{label}: the big book arrived in {len(b)} recipe_book_add packets (>= 2 entries each)")
    if b:
        r.note(f"entries {sum(p['count'] for p in b):,} in {len(b)} packets; largest data {max(p['data'] for p in b):,}, "
               f"largest frame {max(p['frame'] for p in b):,}; max frame of any packet {client['max_frame']:,}")
        want_replace = action == "relog"
        r.check(b[0]["replace"] == want_replace and not any(p["replace"] for p in b[1:]),
                f"{label}: replace flag only on the first packet and only for the initial book (first={b[0]['replace']})")
        if proto == "newer":
            r.check(max(p["frame"] for p in b) < FRAME_LIMIT,
                    f"{label}: the largest translated frame {max(p['frame'] for p in b):,} is below {FRAME_LIMIT:,}")
        if scenario["bundle_chunks"]:
            bundles = {p["bundle"] for p in b}
            inside = [item["kind"] for item in client["sequence"] if item["bundle"] in bundles and item["kind"] != "delimiter"] if None not in bundles else []
            r.check(len(bundles) == 1 and None not in bundles and inside == ["add"] * len(b),
                    f"{label}: the {len(b)} chunks of the book arrived inside one bundle that holds nothing else (bundles {sorted(bundles, key=str)}, content {inside[:3]}...)")
    # The mod also measures (and digests) one-entry packets; the book itself is made of packets with more entries.
    tot_server = sum(d["entries"] for d in server["digests"] if d["entries"] > 1)
    tot_client = sum(p["count"] for p in client["recipe_book_add"] if p["count"] > 1)
    r.check(tot_server == tot_client and tot_server > 0,
            f"{label}: client entries {tot_client:,} == entries of the packets the mod measured {tot_server:,}")
    if server["splits"]:
        s = server["splits"][-1]
        r.check(s["chunks"] == len(b), f"{label}: server split into {s['chunks']} chunks, client got {len(b)}")
        r.note(f"server: {s['bytes']:,} bytes, largest chunk {s['largest']:,}, limit {s['limit']:,}, {s['ms']} ms")
        if proto != "newer":
            r.check(max(p["data"] for p in b) <= s["limit"], f"{label}: control chunks within maxChunkBytes {s['limit']:,}")
    else:
        r.check(False, f"{label}: the server logged a split")
    check.check_encode_once(r, label, server, scenario)


def compare_with_control(r, specs, clients, newer, summary):
    """Pairs every newer-protocol phase with the closest earlier control phase of the same kind (same player state)."""
    growth_rows = []
    for k, (proto, action, _) in enumerate(specs):
        if proto != "newer" or clients[k] is None:
            continue
        earlier = [j for j in range(k) if specs[j][0] != "newer" and clients[j] is not None and family(specs[j][1]) == family(action)]
        if not earlier:
            continue
        ck = earlier[-1]
        nb, cb = big(clients[k]), big(clients[ck])
        label = f"phase {k + 1} (client {newer}, {action}) vs control phase {ck + 1}"
        if family(action) == "give":
            # a recipe give sends the recipes in a stable order, so the chunks of the two runs are the same chunks
            r.check(len(nb) == len(cb) and [(p["count"], p["replace"]) for p in nb] == [(p["count"], p["replace"]) for p in cb],
                    f"{label}: same chunk count ({len(nb)}), entry counts and replace flags as the server-side chunks")
        else:
            # the initial book is built from a hash set, so every join sends the entries in another order and the chunk
            # boundaries differ between joins: only the totals can be compared
            r.check(len(nb) == len(cb) and sum(p["count"] for p in nb) == sum(p["count"] for p in cb),
                    f"{label}: same chunk count ({len(nb)}) and entry total ({sum(p['count'] for p in nb):,}); chunk boundaries differ between joins")
        n = min(len(nb), len(cb))
        if n:
            ratios = [nb[j]["data"] / cb[j]["data"] for j in range(n)]
            diffs = [nb[j]["data"] - cb[j]["data"] for j in range(n)]
            r.note(f"data size, translated vs server side{'' if family(action) == 'give' else ' (other join, other entry order)'}: total {sum(p['data'] for p in nb):,} vs {sum(p['data'] for p in cb):,} "
                   f"({sum(p['data'] for p in nb) / sum(p['data'] for p in cb):.4f}x); per chunk min {min(ratios):.4f}x, max {max(ratios):.4f}x, "
                   f"max growth {max(diffs):+,} bytes; largest translated chunk {max(p['data'] for p in nb):,} "
                   f"(server side largest {max(p['data'] for p in cb):,})")
            r.note(f"headroom: {UNCOMPRESSED_LIMIT - max(p['data'] for p in nb):,} bytes below 8,388,608 (data), "
                   f"{FRAME_LIMIT - max(p['frame'] for p in nb):,} bytes below 2,097,151 (largest frame)")
        growth_rows.append({"phase": k + 1, "control": ck + 1, "action": action, "chunks": n,
                            "server_sizes": [p["data"] for p in cb], "translated_sizes": [p["data"] for p in nb],
                            "translated_frames": [p["frame"] for p in nb], "server_frames": [p["frame"] for p in cb]})
    summary["growth"] = growth_rows


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    name = sys.argv[1]
    w = WORK / name
    scenario = json.loads((w / "scenario.json").read_text())
    log_lines = (w / "server.log").read_text(errors="replace").splitlines()
    server_phases = [check.parse_server_phase(p) for p in check.split_phases(log_lines)]
    specs = [check.parse_phase(s) for s in scenario["phases"]]
    newer = scenario["newer_protocol"]
    r = check.Report()
    print(f"== {name}: {scenario['description']}  (java {scenario.get('java')}, compression {scenario['compression']}, "
          f"max_chunk_bytes {scenario['max_chunk_bytes']})")

    clients = [check.load_client(w / f"phase{k + 1}.json") for k in range(len(specs))]
    summary = {"scenario": scenario, "phases": []}

    if scenario["kind"] == "baseline":
        check_baseline(r, specs, clients, server_phases, summary)
        return finish(r, w, summary)

    check.check_mod_log(r, scenario, log_lines)
    if scenario["kind"] == "limit":
        check_limit(r, scenario, specs, clients, server_phases, summary)
        return finish(r, w, summary)
    for k, spec in enumerate(specs):
        if not r.check(clients[k] is not None, f"phase {k + 1}: produced a result"):
            continue
        if k < len(server_phases):
            check_one_phase(r, scenario, k, spec, clients[k], server_phases[k], newer)
        else:
            r.check(False, f"phase {k + 1}: the client logged in")
    compare_with_control(r, specs, clients, newer, summary)
    for k, c in enumerate(clients):
        if c is not None:
            summary["phases"].append({"phase": k + 1, "spec": specs[k], "disconnect": c["disconnect"],
                                      "max_frame": c["max_frame"], "packets": c["packets"],
                                      "recipe_book_add": [{"count": p["count"], "replace": p["replace"], "data": p["data"], "frame": p["frame"]} for p in c["recipe_book_add"]]})

    # anything else the server complained about
    extra = [line for line in log_lines if ("/ERROR]" in line or "/WARN]" in line) and not NOISE_RE.search(line)]
    r.check(not any("/ERROR]" in line for line in extra), f"no ERROR lines in the server log ({sum('/ERROR]' in line for line in extra)})")
    for line in extra[:12]:
        r.note(line[:220])
    summary["log_warnings"] = extra[:50]
    return finish(r, w, summary)


if __name__ == "__main__":
    main()
