#!/usr/bin/env python3
"""Self-test of analyze.py with a synthetic run (no Minecraft needed): the frame-time table, the digest note and the
tolerance for events written by an older harness.

Usage: python3 -I e2e/realclient/test_analyze.py
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

SPLIT_LINE = ("[08:00:00] [Netty Server IO #1/INFO]: [RecipeBookSplitter] Tester: split 8.8 MiB recipe book packet "
              "({bytes} bytes, {entries} entries, replace={replace}) into 2 chunks{bundle} (largest 600 bytes, limit "
              "1,048,576 bytes, 20 ms, written in 5 ms)")


def summary(n, join_no, known, packets, entries, replace_packets, frame_fields):
    s = {"event": "summary", "tMs": 1000.0 * n, "n": n, "joinNo": join_no, "knownRecipes": known, "highlighted": known,
         "idsSha256": "ab" * 32, "runPackets": packets, "runEntries": entries, "runReplacePackets": replace_packets,
         "runHandleTotalMs": 10.0, "runHandleAvgMs": 5.0, "runHandleMaxMs": 6.0, "bgBuildsSinceLast": 1,
         "bgBuildMsSinceLast": 5.0, "bgMaxParallel": 1, "bgWorkerCpuMsSinceLast": 4.0, "processCpuMsSinceLast": 100.0,
         "gcMsSinceLast": 1, "searchTreeDoneAtSummary": True, "searchTreeJoinMs": 0.0,
         "runFirstDecodeToLastHandleMs": 50.0, "runDecodeTotalMs": 1.0, "runEntryDigestSha256": None,
         "runPacketDigests": []}
    if frame_fields:
        s.update({"runMaxFrameWorkMs": 321.5, "runFramesOver50WorkMs": 7, "runFramesOver100WorkMs": 2,
                  "bookMaxFrameWorkMs": 187.5, "handleFramesMaxWorkMs": 187.5, "searchUpdatesSinceLast": 2,
                  "bookMaxFrame": {"frame": 5, "workMs": 187.5, "packetsMs": 70.25, "tickMs": 1.5, "renderMs": 100.75},
                  "keepAlives": 3, "keepAliveMaxDelayMs": 4})
    return s


def write_run(directory, digest, frame_fields):
    """A fresh join (one recipe), a give of 4 recipes in 2 chunks, a relog with all 5 recipes in 2 chunks."""
    os.makedirs(os.path.join(directory, "client"))
    scenario = {"name": "I", "description": "synthetic", "rbs": True, "proxy": False, "compression": 256,
                "max_chunk_bytes": 1048576, "relogs": 1, "reloads": 0, "huge_entry_bytes": 0, "bundle_chunks": False,
                "throttle_kbit": None}
    with open(os.path.join(directory, "scenario.json"), "w") as f:
        json.dump(scenario, f)
    with open(os.path.join(directory, "server.log"), "w") as f:
        f.write(SPLIT_LINE.format(bytes="1,200", entries="4", replace="false", bundle="") + "\n")
        f.write(SPLIT_LINE.format(bytes="1,300", entries="5", replace="true", bundle="") + "\n")
    events = [{"event": "init", "tMs": 1.0, "digest": digest}]
    # (joins first, recipes known after the run, entries of each packet, first packet has replace=true)
    runs = [(True, 1, [1], True), (False, 5, [2, 2], False), (True, 5, [3, 2], True)]
    joins = 0
    for n, (joined, known, packets, replace) in enumerate(runs, 1):
        if joined:
            joins += 1
            events.append({"event": "join", "tMs": 100.0 * n, "n": joins})
        for p, entries in enumerate(packets):
            events.append({"event": "decode", "tMs": 100.0 * n + p, "decodeMs": 0.5, "seq": p + 1, "entries": entries,
                           "replace": replace and p == 0, "bytes": 100})
            events.append({"event": "handle", "tMs": 100.0 * n + p, "frame": 5 + p, "tick": 3 + p, "handleMs": 5.0,
                           "refreshMs": 1.0, "loopMs": 4.0, "rebuildCollectionsMs": 2.0, "searchTreeUpdateMs": 1.0,
                           "knownAfter": known, "queueMs": 1.0, "seq": p + 1})
        events.append(summary(n, joins, known, len(packets), sum(packets), 1 if replace else 0, frame_fields))
    with open(os.path.join(directory, "client", "events.jsonl"), "w") as f:
        for e in events:
            f.write(json.dumps(e) + "\n")


def analyze(directory):
    p = subprocess.run([sys.executable, "-I", os.path.join(HERE, "analyze.py"), directory], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


class AnalyzeFrameTimes(unittest.TestCase):
    def run_analysis(self, digest, frame_fields):
        with tempfile.TemporaryDirectory() as d:
            write_run(d, digest, frame_fields)
            code, out, err = analyze(d)
            self.assertEqual("", err)
            self.assertEqual(0, code, out)  # the synthetic run satisfies every assertion
            with open(os.path.join(d, "analysis.json")) as f:
                rows = json.load(f)["runs"]
            return code, out, rows

    def test_frame_table_with_digest_off(self):
        _, out, rows = self.run_analysis(digest=False, frame_fields=True)
        self.assertIn("frame work time in ms", out)
        self.assertIn("70.25/1.5/100.75", out)  # the split of the slowest frame: packets/ticks/render
        self.assertNotIn("NOTE: the client's digest was on", out)
        self.assertEqual(187.5, rows[0]["bookMaxFrameWorkMs"])
        self.assertEqual(321.5, rows[0]["frameWorkMaxMs"])
        self.assertEqual(4, rows[0]["keepAliveMaxDelayMs"])
        self.assertEqual(2, rows[0]["searchBuildsScheduled"])

    def test_frame_table_warns_when_the_digest_was_on(self):
        _, out, _ = self.run_analysis(digest=True, frame_fields=True)
        self.assertIn("frame work time in ms", out)
        self.assertIn("NOTE: the client's digest was on", out)

    def test_events_of_an_older_harness_still_analyse(self):
        _, out, rows = self.run_analysis(digest=True, frame_fields=False)
        self.assertNotIn("frame work time in ms", out)
        self.assertNotIn("NOTE: the client's digest was on", out)
        self.assertIsNone(rows[0]["frameWorkMaxMs"])
        self.assertIsNone(rows[0]["keepAliveMaxDelayMs"])


if __name__ == "__main__":
    unittest.main()
