#!/usr/bin/env python3
"""Self-test of analyze.py with a synthetic run (no Minecraft needed): the frame-time table, the digest note, the
tolerance for events written by an older harness, the digest comparisons (a missing digest line is a failure when the
client's digest was on), whether the server sent the chunks in one bundle as configured, and the assertion of scenario F.

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
DIGEST_LINE = ("[08:00:00] [Netty Server IO #1/INFO]: [RecipeBookSplitter] digest player=Tester entries={entries} "
               "replace={replace} bytes={bytes} chunks=2 sha256={sha}")
GIVE_DIGEST = "a1" * 32
RELOG_DIGEST = "b2" * 32


def summary(n, join_no, known, packets, entries, replace_packets, frame_fields, entry_digest=None):
    s = {"event": "summary", "tMs": 1000.0 * n, "n": n, "joinNo": join_no, "knownRecipes": known, "highlighted": known,
         "idsSha256": "ab" * 32, "runPackets": packets, "runEntries": entries, "runReplacePackets": replace_packets,
         "runHandleTotalMs": 10.0, "runHandleAvgMs": 5.0, "runHandleMaxMs": 6.0, "bgBuildsSinceLast": 1,
         "bgBuildMsSinceLast": 5.0, "bgMaxParallel": 1, "bgWorkerCpuMsSinceLast": 4.0, "processCpuMsSinceLast": 100.0,
         "gcMsSinceLast": 1, "searchTreeDoneAtSummary": True, "searchTreeJoinMs": 0.0,
         "runFirstDecodeToLastHandleMs": 50.0, "runDecodeTotalMs": 1.0, "runEntryDigestSha256": entry_digest,
         "runPacketDigests": []}
    if frame_fields:
        s.update({"runMaxFrameWorkMs": 321.5, "runFramesOver50WorkMs": 7, "runFramesOver100WorkMs": 2,
                  "bookMaxFrameWorkMs": 187.5, "handleFramesMaxWorkMs": 187.5, "searchUpdatesSinceLast": 2,
                  "bookMaxFrame": {"frame": 5, "workMs": 187.5, "packetsMs": 70.25, "tickMs": 1.5, "renderMs": 100.75},
                  "keepAlives": 3, "keepAliveMaxDelayMs": 4})
    return s


def write_run(directory, digest, frame_fields, server_digests=True, bundle_config=False, bundle_in_log=False,
              client_digests=None):
    """A fresh join (one recipe), a give of 4 recipes in 2 chunks, a relog with all 5 recipes in 2 chunks. With the
    client's digest on, its summaries carry entry digests (client_digests, by default those the server logs); the
    server logs digest lines unless server_digests is false. bundle_config is the scenario's bundleChunks,
    bundle_in_log whether the server's split lines say 'in one bundle'. With bundle_config the client handles every book
    in one frame and tick (as it does with a bundle), otherwise each packet gets a frame and tick of its own."""
    os.makedirs(os.path.join(directory, "client"))
    scenario = {"name": "I", "description": "synthetic", "rbs": True, "proxy": False, "compression": 256,
                "max_chunk_bytes": 1048576, "relogs": 1, "reloads": 0, "huge_entry_bytes": 0,
                "bundle_chunks": bundle_config, "throttle_kbit": None}
    with open(os.path.join(directory, "scenario.json"), "w") as f:
        json.dump(scenario, f)
    bundle = " in one bundle" if bundle_in_log else ""
    with open(os.path.join(directory, "server.log"), "w") as f:
        f.write(SPLIT_LINE.format(bytes="1,200", entries="4", replace="false", bundle=bundle) + "\n")
        if server_digests:
            f.write(DIGEST_LINE.format(entries=1, replace="true", bytes=40, sha="00" * 32) + "\n")  # the fresh join
            f.write(DIGEST_LINE.format(entries=4, replace="false", bytes=1200, sha=GIVE_DIGEST) + "\n")
        f.write(SPLIT_LINE.format(bytes="1,300", entries="5", replace="true", bundle=bundle) + "\n")
        if server_digests:
            f.write(DIGEST_LINE.format(entries=5, replace="true", bytes=1300, sha=RELOG_DIGEST) + "\n")
    if client_digests is None:
        client_digests = (None, GIVE_DIGEST, RELOG_DIGEST) if digest else (None, None, None)
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
            events.append({"event": "handle", "tMs": 100.0 * n + p, "frame": 5 + (0 if bundle_config else p), "tick": 3 + (0 if bundle_config else p), "handleMs": 5.0,
                           "refreshMs": 1.0, "loopMs": 4.0, "rebuildCollectionsMs": 2.0, "searchTreeUpdateMs": 1.0,
                           "knownAfter": known, "queueMs": 1.0, "seq": p + 1})
        events.append(summary(n, joins, known, len(packets), sum(packets), 1 if replace else 0, frame_fields,
                              client_digests[n - 1]))
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


class AnalyzeAssertions(unittest.TestCase):
    """The assertions that must fail when something is wrong (they used to pass silently)."""

    def analysis(self, **kwargs):
        with tempfile.TemporaryDirectory() as d:
            write_run(d, **kwargs)
            code, out, err = analyze(d)
        self.assertEqual("", err)
        return code, out

    def test_digest_on_with_matching_server_digests_passes(self):
        code, out = self.analysis(digest=True, frame_fields=True)
        self.assertEqual(0, code, out)
        self.assertIn("PASS  give: client re-encoded entry bytes hash to the server's digest", out)
        self.assertIn("PASS  relog 1: client re-encoded entry bytes hash to the server's digest", out)

    def test_digest_on_without_server_digest_lines_fails(self):
        code, out = self.analysis(digest=True, frame_fields=True, server_digests=False)
        self.assertEqual(1, code, out)
        self.assertIn("FAIL  give: client re-encoded entry bytes hash to the server's digest", out)
        self.assertIn("FAIL  relog 1: client re-encoded entry bytes hash to the server's digest", out)
        self.assertIn("no matching digest line in server.log", out)

    def test_digest_on_without_a_client_digest_fails(self):
        code, out = self.analysis(digest=True, frame_fields=True, client_digests=(None, None, None))
        self.assertEqual(1, code, out)
        self.assertIn("the client's digest was on but its summary has no entry digest", out)

    def test_digest_on_with_a_different_digest_fails(self):
        code, out = self.analysis(digest=True, frame_fields=True, client_digests=(None, "c3" * 32, RELOG_DIGEST))
        self.assertEqual(1, code, out)
        self.assertIn("FAIL  give: client re-encoded entry bytes hash to the server's digest", out)
        self.assertNotIn("FAIL  relog 1", out)

    def test_digest_off_skips_the_comparisons_and_says_so(self):
        code, out = self.analysis(digest=False, frame_fields=True, server_digests=False)
        self.assertEqual(0, code, out)
        self.assertNotIn("hash to the server's digest", out)
        self.assertIn("NOTE: the client's digest was off", out)

    def test_bundle_chunks_needs_the_server_to_say_in_one_bundle(self):
        code, out = self.analysis(digest=False, frame_fields=True, bundle_config=True, bundle_in_log=True)
        self.assertEqual(0, code, out)
        self.assertIn("sent in one bundle (server log)", out)
        # the client handled the packets in one frame and tick, but the server sent loose chunks: not bundleChunks
        code, out = self.analysis(digest=False, frame_fields=True, bundle_config=True, bundle_in_log=False)
        self.assertEqual(1, code, out)
        self.assertIn("FAIL  recipe give: client received the same number of packets as the server's chunk count, sent in one bundle", out)
        self.assertIn("FAIL  relog 1: same chunk count as the server, sent in one bundle", out)

    def test_loose_chunks_fail_when_the_server_used_a_bundle(self):
        code, out = self.analysis(digest=False, frame_fields=True, bundle_config=False, bundle_in_log=False)
        self.assertEqual(0, code, out)
        self.assertIn("sent loose, not in a bundle (server log)", out)
        code, out = self.analysis(digest=False, frame_fields=True, bundle_config=False, bundle_in_log=True)
        self.assertEqual(1, code, out)
        self.assertIn("FAIL  recipe give: client received the same number of packets as the server's chunk count, sent loose", out)


def write_huge_run(directory, reason, chain):
    """Scenario F: the server sends a 4.5 MB entry; the client is disconnected (and reconnects, so more than one)."""
    os.makedirs(os.path.join(directory, "client"))
    scenario = {"name": "F", "description": "synthetic", "rbs": True, "proxy": False, "compression": 256,
                "max_chunk_bytes": 1048576, "relogs": 0, "reloads": 0, "huge_entry_bytes": 4500000,
                "bundle_chunks": False, "throttle_kbit": None}
    with open(os.path.join(directory, "scenario.json"), "w") as f:
        json.dump(scenario, f)
    with open(os.path.join(directory, "server.log"), "w") as f:
        f.write(SPLIT_LINE.format(bytes="13,000,000", entries="4458", replace="true", bundle="") + "\n")
    events = [{"event": "init", "tMs": 1.0, "digest": True}, {"event": "join", "tMs": 100.0, "n": 1}]
    if chain:
        events.append({"event": "netty_exception", "tMs": 200.0, "chain": chain})
    events.append({"event": "disconnect", "tMs": 210.0, "n": 1, "phase": "ClientPacketListener", "expected": False,
                   "reason": reason, "summariesSoFar": 0})
    with open(os.path.join(directory, "client", "events.jsonl"), "w") as f:
        for e in events:
            f.write(json.dumps(e) + "\n")


class AnalyzeHugeEntry(unittest.TestCase):
    REASON = "Internal Exception: io.netty.handler.codec.DecoderException: Failed to decode packet 'clientbound/minecraft:recipe_book_add'"
    NBT_CHAIN = ["io.netty.handler.codec.DecoderException: Failed to decode packet 'clientbound/minecraft:recipe_book_add'",
                 "net.minecraft.nbt.NbtAccounterException: Tried to read NBT tag that was too big; tried to allocate: "
                 "2043742 + 60000 bytes where max allowed: 2097152"]

    def analysis(self, reason, chain):
        with tempfile.TemporaryDirectory() as d:
            write_huge_run(d, reason, chain)
            code, out, err = analyze(d)
        self.assertEqual("", err)
        return code, out

    def test_nbt_quota_in_the_exception_chain_passes(self):
        code, out = self.analysis(self.REASON, self.NBT_CHAIN)
        self.assertEqual(0, code, out)
        self.assertIn("PASS  huge entry: the real client cannot read the entry (NbtAccounterException", out)

    def test_nbt_quota_in_the_reason_passes(self):
        code, out = self.analysis("Internal Exception: net.minecraft.nbt.NbtAccounterException: Tried to read NBT tag that was too big", [])
        self.assertEqual(0, code, out)

    def test_a_disconnect_for_another_reason_fails(self):
        code, out = self.analysis("Timed out", [])
        self.assertEqual(1, code, out)
        self.assertIn("FAIL  huge entry: the real client cannot read the entry", out)
        self.assertIn("no NbtAccounterException; disconnects: Timed out", out)

    def test_another_decode_error_fails(self):
        code, out = self.analysis(self.REASON, [self.NBT_CHAIN[0], "java.io.IOException: Packet was larger than I expected"])
        self.assertEqual(1, code, out)


if __name__ == "__main__":
    unittest.main()
