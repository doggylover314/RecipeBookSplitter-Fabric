#!/usr/bin/env python3
"""Reads the output of one run_client_e2e.sh scenario (<dir>/client/events.jsonl, <dir>/server.log, <dir>/scenario.json),
prints a summary with PASS/FAIL assertions and writes <dir>/analysis.json. Exit status 1 when an assertion fails.

Usage: python3 -I analyze.py <scenario dir>
"""
import json
import os
import re
import statistics
import sys


def load_events(path):
    events = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    events.append(json.loads(line))
    except FileNotFoundError:
        pass
    return events


def num(text):
    return int(text.replace(",", ""))


SPLIT_RE = re.compile(
    r"\[RecipeBookSplitter\] (\S+): split [\d.]+ MiB recipe book packet \(([\d,]+) bytes, ([\d,]+) entries, replace=(true|false)\)"
    r" into (\d+) chunks( in one bundle)? \(largest ([\d,]+) bytes, limit ([\d,]+) bytes, (\d+) ms")
DIGEST_RE = re.compile(
    r"\[RecipeBookSplitter\] digest player=(\S+) entries=(\d+) replace=(true|false) bytes=(\d+) chunks=(\d+) sha256=([0-9a-f]+)")


def parse_server(path):
    info = {"splits": [], "digests": [], "errors": [], "unlocked": [], "lost": [], "logins": 0, "warns": []}
    try:
        lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    except FileNotFoundError:
        return info
    for line in lines:
        m = SPLIT_RE.search(line)
        if m:
            info["splits"].append({"bytes": num(m.group(2)), "entries": num(m.group(3)), "replace": m.group(4) == "true",
                                   "chunks": int(m.group(5)), "one_bundle": m.group(6) is not None,
                                   "largest": num(m.group(7)), "limit": num(m.group(8)), "ms": int(m.group(9))})
        m = DIGEST_RE.search(line)
        if m:
            info["digests"].append({"entries": int(m.group(2)), "replace": m.group(3) == "true", "bytes": int(m.group(4)),
                                    "chunks": int(m.group(5)), "sha256": m.group(6)})
        if "/WARN]" in line and "RecipeBookSplitter" in line:
            info["warns"].append(line.strip()[:400])
        if re.search(r"Packet too (big|large)", line):
            info["errors"].append(line.strip()[:300])
        if re.search(r"lost connection|Disconnecting Tester|Tester lost", line):
            info["lost"].append(line.strip()[:300])
        if re.search(r"(Unlocked|Gave) .*recipes", line):
            info["unlocked"].append(line.strip()[:200])
        if re.search(r"Tester\[.*\] logged in", line):
            info["logins"] += 1
    return info


def pct(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100.0 * (len(s) - 1))))]


def main():
    d = sys.argv[1]
    scenario = json.load(open(os.path.join(d, "scenario.json")))
    events = load_events(os.path.join(d, "client", "events.jsonl"))
    server = parse_server(os.path.join(d, "server.log"))

    # Group decode/handle events into runs: a run ends with the summary event that follows it.
    runs = []
    cur = {"decode": [], "handle": []}
    for e in events:
        if e["event"] in ("decode", "handle"):
            cur[e["event"]].append(e)
        elif e["event"] == "join":
            cur["join"] = e
        elif e["event"] == "summary":
            cur["summary"] = e
            runs.append(cur)
            cur = {"decode": [], "handle": []}
    trailing = cur

    joins = [e for e in events if e["event"] == "join"]
    disconnects = [e for e in events if e["event"] == "disconnect"]
    unexpected = [e for e in disconnects if not e["expected"]]
    exit_ev = [e for e in events if e["event"] == "exit"]

    out = {"scenario": scenario, "joins": len(joins), "disconnects": disconnects, "exit": exit_ev,
           "server": {k: v for k, v in server.items()}, "runs": []}
    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print(("PASS" if ok else "FAIL") + "  " + name + (("  [" + detail + "]") if detail else ""))

    print(f"=== {scenario['name']}: {scenario['description']}")
    print(f"client events: {len(events)}; joins={len(joins)}, disconnects={len(disconnects)} "
          f"(unexpected {len(unexpected)}), summaries={len(runs)}")
    for dis in disconnects:
        print(f"  client disconnect #{dis['n']} phase={dis['phase']} expected={dis['expected']} "
              f"at {dis['tMs']} ms: {dis['reason']!r}")
    for e in [x for x in events if x["event"] == "netty_exception"][:4]:
        print("  client connection exception chain: " + " <- ".join(e["chain"]))
    for s in server["splits"]:
        print(f"  server split: {s['entries']} entries, {s['bytes']:,} bytes, replace={s['replace']} -> {s['chunks']} chunks"
              f"{' in one bundle' if s['one_bundle'] else ''} (largest {s['largest']:,}, limit {s['limit']:,}), {s['ms']} ms")
    for s in server["errors"][:4]:
        print("  server error: " + s)
    for s in server["unlocked"][:3]:
        print("  server: " + s)

    print()
    print("run  join  known  highl  pkts  entries  repl  handle_total_ms  handle_avg_ms  handle_max_ms  decode_total_ms  "
          "bg_builds  bg_ms  bgcpu_ms  cpu_ms  gc_ms  searchDone  join_ms  frames  ticks  decode_to_handled_ms")
    for i, r in enumerate(runs, 1):
        s = r["summary"]
        handles = [h["handleMs"] for h in r["handle"]]
        row = {
            "n": s["n"], "joinNo": s["joinNo"], "knownRecipes": s["knownRecipes"], "highlighted": s["highlighted"],
            "idsSha256": s["idsSha256"], "packets": s["runPackets"], "entries": s["runEntries"],
            "replacePackets": s["runReplacePackets"], "handleTotalMs": s["runHandleTotalMs"],
            "handleAvgMs": s["runHandleAvgMs"], "handleMaxMs": s["runHandleMaxMs"],
            "handleP50Ms": pct(handles, 50), "handleP95Ms": pct(handles, 95),
            "decodeTotalMs": round(sum(x["decodeMs"] for x in r["decode"]), 3),
            "decodeMaxMs": max([x["decodeMs"] for x in r["decode"]] or [0]), "bgBuilds": s["bgBuildsSinceLast"], "bgBuildMs": s["bgBuildMsSinceLast"],
            "bgMaxParallel": s["bgMaxParallel"], "bgWorkerCpuMs": s["bgWorkerCpuMsSinceLast"],
            "processCpuMs": s["processCpuMsSinceLast"], "gcMs": s["gcMsSinceLast"], "searchTreeDone": s["searchTreeDoneAtSummary"],
            "searchTreeJoinMs": s["searchTreeJoinMs"], "firstDecodeToLastHandleMs": s["runFirstDecodeToLastHandleMs"],
            "entryDigest": s.get("runEntryDigestSha256"), "packetDigests": s.get("runPacketDigests"),
            "queueMsMax": max([h.get("queueMs", 0) for h in r["handle"]] or [0]),
            "refreshAvgMs": statistics.mean([h["refreshMs"] for h in r["handle"]]) if r["handle"] else 0,
            "loopAvgMs": statistics.mean([h["loopMs"] for h in r["handle"]]) if r["handle"] else 0,
            "rebuildAvgMs": statistics.mean([h["rebuildCollectionsMs"] for h in r["handle"]]) if r["handle"] else 0,
            "searchUpdateAvgMs": statistics.mean([h["searchTreeUpdateMs"] for h in r["handle"]]) if r["handle"] else 0,
            "handleMsList": handles,
            # a book is handled in one frame when it arrived in one bundle; loose packets may take several
            "distinctFrames": len({h["frame"] for h in r["handle"] if "frame" in h}),
            "distinctTicks": len({h["tick"] for h in r["handle"] if "tick" in h}),
            "tickSpan": (lambda ticks: max(ticks) - min(ticks) + 1 if ticks else 0)([h["tick"] for h in r["handle"] if "tick" in h]),
        }
        out["runs"].append(row)
        print(f"{i:>3}  {s['joinNo']:>4}  {s['knownRecipes']:>5}  {s['highlighted']:>5}  {s['runPackets']:>4}  {s['runEntries']:>7}  "
              f"{s['runReplacePackets']:>4}  {s['runHandleTotalMs']:>15}  {s['runHandleAvgMs']:>13}  {s['runHandleMaxMs']:>13}  "
              f"{row['decodeTotalMs']:>15.1f}  {s['bgBuildsSinceLast']:>9}  {s['bgBuildMsSinceLast']:>5}  "
              f"{s['bgWorkerCpuMsSinceLast']:>8}  {s['processCpuMsSinceLast']:>6}  {s['gcMsSinceLast']:>5}  "
              f"{str(s['searchTreeDoneAtSummary']):>10}  {s['searchTreeJoinMs']:>7}  {row['distinctFrames']:>6}  {row['tickSpan']:>5}  "
              f"{s['runFirstDecodeToLastHandleMs']:>20}")
    print("(bg_builds = background search-tree builds that ran; one build is scheduled per packet handled, so pkts - bg_builds were "
          "cancelled before they started; frames = distinct frames in which the run's packets were handled, ticks = ticks they span)")

    # Main-thread cost per packet against the size of the recipe book at that point (rebuildCollections is O(known)).
    print()
    print("handle time by recipe book size (ms per packet, run 2 = give, run 3 = relog):")
    for i, r in enumerate(runs, 1):
        buckets = {}
        for h in r["handle"]:
            b = (h["knownAfter"] // 1000) * 1000
            buckets.setdefault(b, []).append(h)
        if len(r["handle"]) < 20:
            continue
        parts = []
        for b in sorted(buckets):
            hs = buckets[b]
            parts.append(f"[{b}-{b + 999}] n={len(hs)} avg={statistics.mean(x['handleMs'] for x in hs):.2f} "
                         f"rebuild={statistics.mean(x['rebuildCollectionsMs'] for x in hs):.2f}")
        print(f"  run {i}: " + "; ".join(parts))
    print()
    if scenario.get("huge_entry_bytes", 0) > 0:
        for w in server["warns"]:
            print("  server WARN: " + w)
        check("huge entry: the server sent the packets (no 'Packet too big/large' on the server)", not server["errors"],
              "; ".join(server["errors"][:2]))
        check("huge entry: the real client cannot read the entry and is disconnected (README: 2 MiB NBT quota)",
              len(unexpected) >= 1, "; ".join(e["reason"][:300] for e in unexpected[:2]))
    elif scenario["rbs"]:
        relogs, reloads = scenario.get("relogs", 1), scenario.get("reloads", 0)
        bundled = scenario.get("bundle_chunks", False)
        check("client was never disconnected unexpectedly", not unexpected, "; ".join(e["reason"] for e in unexpected))
        check(f"client joined {1 + relogs} times (join + {relogs} relog) and logged {2 + relogs + reloads} summaries",
              len(joins) == 1 + relogs and len(runs) == 2 + relogs + reloads, f"joins={len(joins)} summaries={len(runs)}")

        def single_frame(tag, run):
            """With bundleChunks a book arrives in one bundle, which the client handles in one go."""
            if bundled:
                check(f"{tag}: bundleChunks: all {run['packets']} packets of the book were handled in a single frame and tick",
                      run["distinctFrames"] == 1 and run["distinctTicks"] == 1,
                      f"frames={run['distinctFrames']} ticks={run['distinctTicks']}")

        if len(runs) >= 3:
            fresh, give = out["runs"][0], out["runs"][1]
            relog_runs = out["runs"][2:]
            give_splits = [s for s in server["splits"] if not s["replace"]]
            relog_splits = [s for s in server["splits"] if s["replace"]]
            given = give_splits[-1]["entries"] if give_splits else None
            total = relog_splits[-1]["entries"] if relog_splits else None
            check("fresh player: the first join delivered almost no recipes (vanilla unlocks a few by itself)",
                  fresh["knownRecipes"] < 50, f"known={fresh['knownRecipes']}")
            check("recipe give: the client received exactly the entries the server sent (run entries == server entries)",
                  given is not None and give["entries"] == given,
                  f"server entries={given} client run entries={give['entries']}")
            check("recipe give: client recipe book = recipes known before + recipes given",
                  given is not None and give["knownRecipes"] == fresh["knownRecipes"] + given,
                  f"known before={fresh['knownRecipes']} given={given} client known={give['knownRecipes']}")
            check("recipe give: client received the same number of packets as the server's chunk count",
                  bool(give_splits) and give["packets"] == give_splits[-1]["chunks"],
                  f"server chunks={give_splits[-1]['chunks'] if give_splits else None} client packets={give['packets']}")
            single_frame("recipe give", give)
            sd = [x for x in server["digests"] if x["entries"] == give["entries"] and not x["replace"]]
            if give["entryDigest"] and sd:
                check("give: client re-encoded entry bytes hash to the server's digest",
                      any(x["sha256"] == give["entryDigest"] for x in sd),
                      f"client {give['entryDigest'][:16]} server {[x['sha256'][:16] for x in sd]}")
            # (the fresh player's first join also has a replace=true digest, of its one or two recipes)
            server_relog_digests = [x for x in server["digests"] if x["replace"] and x["entries"] == give["knownRecipes"]]
            for k, relog in enumerate(relog_runs, 1):
                tag = f"relog {k}" if k <= relogs else "reload"
                single_frame(tag, relog)
                spl = relog_splits[k - 1] if k - 1 < len(relog_splits) else None
                check(f"{tag}: initial recipe book is complete (client known == run entries == server entries == all recipes)",
                      spl is not None and relog["knownRecipes"] == relog["entries"] == spl["entries"] == give["knownRecipes"],
                      f"known={relog['knownRecipes']} run entries={relog['entries']} "
                      f"server entries={spl['entries'] if spl else None} known after give={give['knownRecipes']}")
                check(f"{tag}: same chunk count as the server", spl is not None and relog["packets"] == spl["chunks"],
                      f"server chunks={spl['chunks'] if spl else None} client packets={relog['packets']}")
                check(f"{tag}: replace=true only on the first packet", relog["replacePackets"] == 1,
                      f"{relog['replacePackets']}")
                check(f"{tag}: recipe ids identical to those after the give (SHA-256 of the sorted id list)",
                      give["idsSha256"] == relog["idsSha256"], f"{give['idsSha256'][:16]} vs {relog['idsSha256'][:16]}")
                dg = server_relog_digests[k - 1] if k - 1 < len(server_relog_digests) else None
                if relog["entryDigest"] and dg:
                    check(f"{tag}: client re-encoded entry bytes hash to the server's digest",
                          dg["sha256"] == relog["entryDigest"],
                          f"client {relog['entryDigest'][:16]} server {dg['sha256'][:16]}")
        check("server logged no 'Packet too big/large'", not server["errors"], "; ".join(server["errors"][:2]))
    else:
        check("baseline: the client was disconnected", len(unexpected) >= 1, f"{len(unexpected)} unexpected disconnects")
        check("baseline: the server logged 'Packet too big' / 'Packet too large'", bool(server["errors"]))
        check("baseline: the client never held the full recipe book",
              all(r["summary"]["knownRecipes"] < 1000 for r in runs), f"known={[r['summary']['knownRecipes'] for r in runs]}")

    with open(os.path.join(d, "analysis.json"), "w") as f:
        json.dump(out, f, indent=1)
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} assertions passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
