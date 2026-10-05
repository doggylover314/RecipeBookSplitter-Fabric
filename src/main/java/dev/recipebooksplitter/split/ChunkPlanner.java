package dev.recipebooksplitter.split;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Greedy packing of recipe book entries into packets of at most a given size. Pure Java on purpose, so the
 * algorithm can be unit-tested without Minecraft.
 */
public final class ChunkPlanner {
    private ChunkPlanner() {}

    /**
     * Entries {@code [start, end)} of the original list.
     *
     * @param bytes exact size of the encoded packet: packet id, entry count, entries and the replace flag
     * @param oversized {@code bytes} exceeds the budget; only possible for a chunk with a single entry
     */
    public record Chunk(int start, int end, long bytes, boolean oversized) {
        public int entryCount() {
            return end - start;
        }
    }

    @FunctionalInterface
    public interface ChunkFactory<E, P> {
        P create(List<E> entries, boolean replace);
    }

    /** Same algorithm as {@code net.minecraft.network.VarInt.getByteSize}. */
    public static int varIntSize(int value) {
        for (int bytes = 1; bytes < 5; bytes++) {
            if ((value & (-1 << bytes * 7)) == 0) {
                return bytes;
            }
        }
        return 5;
    }

    /** @param fixedOverheadBytes packet id VarInt plus the 1-byte replace flag */
    public static long packetBytes(int fixedOverheadBytes, int entryCount, long entryBytesSum) {
        return (long) fixedOverheadBytes + varIntSize(entryCount) + entryBytesSum;
    }

    /**
     * Packs consecutive entries into as few chunks as possible without any chunk exceeding {@code maxChunkBytes}.
     * An entry that does not fit even alone gets a chunk of its own, flagged as oversized.
     * The returned chunks are contiguous, ordered and cover every entry; with no entries there is one empty chunk.
     */
    public static List<Chunk> plan(int[] entryBytes, int fixedOverheadBytes, int maxChunkBytes) {
        Objects.requireNonNull(entryBytes, "entryBytes");
        if (fixedOverheadBytes < 0 || maxChunkBytes <= 0) {
            throw new IllegalArgumentException("fixedOverheadBytes must be >= 0 and maxChunkBytes > 0");
        }
        for (int size : entryBytes) {
            if (size < 0) {
                throw new IllegalArgumentException("negative entry size: " + size);
            }
        }

        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        long sum = 0;
        for (int i = 0; i < entryBytes.length; i++) {
            if (i > start && packetBytes(fixedOverheadBytes, i - start + 1, sum + entryBytes[i]) > maxChunkBytes) {
                chunks.add(chunk(start, i, sum, fixedOverheadBytes, maxChunkBytes));
                start = i;
                sum = 0;
            }
            sum += entryBytes[i];
        }
        chunks.add(chunk(start, entryBytes.length, sum, fixedOverheadBytes, maxChunkBytes));
        return List.copyOf(chunks);
    }

    private static Chunk chunk(int start, int end, long sum, int fixedOverheadBytes, int maxChunkBytes) {
        long bytes = packetBytes(fixedOverheadBytes, end - start, sum);
        return new Chunk(start, end, bytes, bytes > maxChunkBytes);
    }

    /**
     * Builds one packet per chunk. Only the first one carries {@code replace}: the client clears its recipe book for
     * that flag, so every later chunk must add to what the first one set up. Entries are shared with the original
     * list, so their flags are preserved; each chunk gets its own list.
     */
    public static <E, P> List<P> split(List<E> entries, boolean replace, List<Chunk> plan, ChunkFactory<E, P> factory) {
        int expectedStart = 0;
        for (Chunk chunk : plan) {
            if (chunk.start() != expectedStart || chunk.end() < chunk.start()) {
                throw new IllegalArgumentException("plan is not contiguous at entry " + expectedStart);
            }
            expectedStart = chunk.end();
        }
        if (expectedStart != entries.size()) {
            throw new IllegalArgumentException("plan covers " + expectedStart + " of " + entries.size() + " entries");
        }

        List<P> packets = new ArrayList<>(plan.size());
        for (int i = 0; i < plan.size(); i++) {
            Chunk chunk = plan.get(i);
            packets.add(factory.create(new ArrayList<>(entries.subList(chunk.start(), chunk.end())), replace && i == 0));
        }
        return packets;
    }
}
