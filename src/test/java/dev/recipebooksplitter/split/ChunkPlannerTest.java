package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.split.ChunkPlanner.Chunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ChunkPlannerTest {
    private record FakeEntry(int id, byte flags) {}

    private record FakePacket(List<FakeEntry> entries, boolean replace) {}

    private static int[] filled(int count, int value) {
        int[] sizes = new int[count];
        Arrays.fill(sizes, value);
        return sizes;
    }

    @Test
    void varIntSizeBoundaries() {
        for (int value : new int[] {0, 1, 127}) {
            assertEquals(1, ChunkPlanner.varIntSize(value), "value " + value);
        }
        for (int value : new int[] {128, 16_383}) {
            assertEquals(2, ChunkPlanner.varIntSize(value), "value " + value);
        }
        for (int value : new int[] {16_384, 2_097_151}) {
            assertEquals(3, ChunkPlanner.varIntSize(value), "value " + value);
        }
        for (int value : new int[] {2_097_152, 268_435_455}) {
            assertEquals(4, ChunkPlanner.varIntSize(value), "value " + value);
        }
        for (int value : new int[] {268_435_456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
            assertEquals(5, ChunkPlanner.varIntSize(value), "value " + value);
        }
    }

    @Test
    void allFitGivesOneChunk() {
        assertEquals(List.of(new Chunk(0, 3, 63, false)), ChunkPlanner.plan(new int[] {10, 20, 30}, 2, 63));
    }

    @Test
    void boundaryIsInclusive() {
        int[] sizes = {10, 20, 30};
        assertEquals(1, ChunkPlanner.plan(sizes, 2, 63).size());
        assertEquals(2, ChunkPlanner.plan(sizes, 2, 62).size());
    }

    @Test
    void greedyIsMaximal() {
        // 3 entries: 2 + 1 + 300 = 303 bytes exactly.
        List<Chunk> plan = ChunkPlanner.plan(filled(10, 100), 2, 303);
        assertEquals(List.of(3, 3, 3, 1), plan.stream().map(Chunk::entryCount).toList());
        assertEquals(List.of(303L, 303L, 303L, 103L), plan.stream().map(Chunk::bytes).toList());
    }

    @Test
    void varIntGrowthAt128() {
        int[] sizes = filled(128, 1);
        assertEquals(130, ChunkPlanner.packetBytes(2, 127, 127));
        assertEquals(132, ChunkPlanner.packetBytes(2, 128, 128));
        List<Chunk> plan = ChunkPlanner.plan(sizes, 2, 131);
        assertEquals(List.of(new Chunk(0, 127, 130, false), new Chunk(127, 128, 4, false)), plan);
    }

    @Test
    void oversizedEntryIsolated() {
        List<Chunk> plan = ChunkPlanner.plan(new int[] {10, 500, 10, 10}, 2, 100);
        assertEquals(List.of(new Chunk(0, 1, 13, false), new Chunk(1, 2, 503, true), new Chunk(2, 4, 23, false)), plan);
    }

    @Test
    void oversizedFirstLastAndAll() {
        assertEquals(List.of(new Chunk(0, 1, 503, true), new Chunk(1, 3, 23, false)),
                ChunkPlanner.plan(new int[] {500, 10, 10}, 2, 100));
        assertEquals(List.of(new Chunk(0, 2, 23, false), new Chunk(2, 3, 503, true)),
                ChunkPlanner.plan(new int[] {10, 10, 500}, 2, 100));
        List<Chunk> all = ChunkPlanner.plan(new int[] {500, 600, 700}, 2, 100);
        assertEquals(3, all.size());
        assertTrue(all.stream().allMatch(Chunk::oversized));
    }

    @Test
    void emptyAndSingleEntryInputs() {
        assertEquals(List.of(new Chunk(0, 0, 3, false)), ChunkPlanner.plan(new int[0], 2, 100));
        assertEquals(List.of(new Chunk(0, 1, 13, false)), ChunkPlanner.plan(new int[] {10}, 2, 100));
        assertEquals(List.of(new Chunk(0, 1, 503, true)), ChunkPlanner.plan(new int[] {500}, 2, 100));
    }

    @Test
    void invalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> ChunkPlanner.plan(new int[] {1}, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkPlanner.plan(new int[] {1}, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> ChunkPlanner.plan(new int[] {1}, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> ChunkPlanner.plan(new int[] {1, -1}, 2, 100));
        assertThrows(NullPointerException.class, () -> ChunkPlanner.plan(null, 2, 100));
    }

    @Test
    void randomizedInvariants() {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 2000; iteration++) {
            int[] sizes = new int[random.nextInt(301)];
            for (int i = 0; i < sizes.length; i++) {
                sizes[i] = random.nextInt(3001);
            }
            int fixed = 2 + random.nextInt(5);
            int max = fixed + 2 + random.nextInt(20_000 - fixed - 1);

            List<Chunk> plan = ChunkPlanner.plan(sizes, fixed, max);

            int expectedStart = 0;
            for (int k = 0; k < plan.size(); k++) {
                Chunk chunk = plan.get(k);
                assertEquals(expectedStart, chunk.start(), "contiguous, iteration " + iteration);
                assertTrue(sizes.length == 0 || chunk.entryCount() > 0, "non-empty, iteration " + iteration);
                expectedStart = chunk.end();

                long sum = 0;
                for (int i = chunk.start(); i < chunk.end(); i++) {
                    sum += sizes[i];
                }
                assertEquals(fixed + ChunkPlanner.varIntSize(chunk.entryCount()) + sum, chunk.bytes(), "exact bytes, iteration " + iteration);
                assertTrue(chunk.bytes() <= max || chunk.entryCount() == 1, "within budget, iteration " + iteration);
                assertEquals(chunk.bytes() > max, chunk.oversized(), "oversized flag, iteration " + iteration);

                if (k < plan.size() - 1) {
                    long withNext = ChunkPlanner.packetBytes(fixed, chunk.entryCount() + 1, sum + sizes[chunk.end()]);
                    assertTrue(withNext > max, "greedy maximality, iteration " + iteration);
                }
            }
            assertEquals(sizes.length, expectedStart, "coverage, iteration " + iteration);
            assertEquals(plan.size() == 1, plan.get(0).entryCount() == sizes.length, "single chunk iff all fit, iteration " + iteration);
        }
    }

    @Test
    void totalEntriesEqualInput() {
        int[] sizes = new int[1234];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = 100 + i % 50;
        }
        List<Chunk> plan = ChunkPlanner.plan(sizes, 2, 5000);
        assertEquals(sizes.length, plan.stream().mapToInt(Chunk::entryCount).sum());
    }

    @Test
    void splitReplaceOrderingAndIdentity() {
        List<FakeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(new FakeEntry(i, (byte) (i % 4)));
        }
        List<Chunk> plan = ChunkPlanner.plan(filled(10, 100), 2, 303);

        List<FakePacket> replaced = ChunkPlanner.split(entries, true, plan, FakePacket::new);
        assertEquals(List.of(true, false, false, false), replaced.stream().map(FakePacket::replace).toList());

        List<FakePacket> added = ChunkPlanner.split(entries, false, plan, FakePacket::new);
        assertTrue(added.stream().noneMatch(FakePacket::replace));

        List<FakeEntry> flattened = replaced.stream().flatMap(packet -> packet.entries().stream()).toList();
        assertEquals(entries.size(), flattened.size());
        for (int i = 0; i < entries.size(); i++) {
            assertSame(entries.get(i), flattened.get(i));
            assertEquals(entries.get(i).flags(), flattened.get(i).flags());
        }

        // Chunks own their lists: clearing the original afterwards changes nothing.
        assertNotSame(entries, replaced.get(0).entries());
        entries.clear();
        assertEquals(3, replaced.get(0).entries().size());
        assertFalse(replaced.get(3).entries().isEmpty());
    }

    @Test
    void splitRejectsBadPlan() {
        List<FakeEntry> entries = List.of(new FakeEntry(0, (byte) 0), new FakeEntry(1, (byte) 0), new FakeEntry(2, (byte) 0));
        ChunkPlanner.ChunkFactory<FakeEntry, FakePacket> factory = FakePacket::new;
        // gap
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPlanner.split(entries, true, List.of(new Chunk(0, 1, 0, false), new Chunk(2, 3, 0, false)), factory));
        // overlap
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPlanner.split(entries, true, List.of(new Chunk(0, 2, 0, false), new Chunk(1, 3, 0, false)), factory));
        // incomplete coverage
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPlanner.split(entries, true, List.of(new Chunk(0, 2, 0, false)), factory));
        // does not start at zero
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPlanner.split(entries, true, List.of(new Chunk(1, 3, 0, false)), factory));
        // plan longer than the entries
        assertThrows(IllegalArgumentException.class,
                () -> ChunkPlanner.split(entries, true, List.of(new Chunk(0, 5, 0, false)), factory));
    }
}
