package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.testutil.BenchDataset;
import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Opt-in benchmark (not part of the normal build, see build.gradle): what the mod's whole send task costs for one
 * recipe book, with and without reusing the measured bytes. Run it with
 * {@code ./gradlew test -PrbsBench --tests '*EncodeOnceBenchmarkTest' --no-daemon}, optionally with
 * {@code -PrbsBench.rounds=30 -PrbsBench.warmup=10 -PrbsBench.datasets=book,tiny,small,smallcustom}.
 *
 * <p>The operations run one after the other in a rotating order within every round, so that machine load and JIT state
 * affect all of them alike, and the differences are taken per round:
 * <ul>
 *   <li>V: the unsplit packet written straight to the channel, so only vanilla's single encode (nothing the mod does);
 *   <li>M0: {@code Connection.send} with encode once off, which is what 1.0.0 did: measure, split, encode every chunk;
 *   <li>M1: {@code Connection.send} with encode once on;
 *   <li>M0c, M1c: as M0 and M1 with network compression (threshold 256) after the encoder.
 * </ul>
 * Everything runs on the calling thread, like the mod's task on an event loop. No timing is asserted, only that M0 and
 * M1 write the same bytes. The numbers are for comparing the operations on one machine, not absolute.
 */
@Tag("benchmark")
class EncodeOnceBenchmarkTest {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private final List<TestConnection> open = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        RecipeFixtures.bootstrap();
    }

    @AfterEach
    void tearDown() {
        open.forEach(TestConnection::close);
        RecipeBookSendInterceptor.encodeOnce = true;
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
    }

    /** What one operation sends and where it ends up. */
    private record Op(TestConnection target, Runnable send) {}

    @Test
    void benchmark() throws Exception {
        RecipeBookSplitter.setConfig(SplitterConfig.DEFAULTS);
        int rounds = Integer.getInteger("rbsBench.rounds", 30);
        int warmup = Integer.getInteger("rbsBench.warmup", 10);
        for (String dataset : System.getProperty("rbsBench.datasets", "book,tiny,small,smallcustom").split(",")) {
            List<Entry> entries = switch (dataset) {
                case "book" -> BenchDataset.book(3_000, 3_000, 1_457, 1L); // about 9.2 MB, 4,457 entries
                case "tiny" -> BenchDataset.book(0, 0, 140_000, 3L); // very many very small entries
                case "small" -> BenchDataset.book(0, 0, 1_457, 4L); // as many entries as vanilla has, within the limit
                case "smallcustom" -> BenchDataset.book(250, 3_000, 1_457, 5L); // custom data, still within the limit
                default -> throw new IllegalArgumentException("unknown dataset " + dataset);
            };
            run(dataset, entries, rounds, warmup);
        }
    }

    private void run(String dataset, List<Entry> entries, int rounds, int warmup) throws Exception {
        TestConnection plain = connection(false);
        TestConnection compressed = connection(true);
        Map<String, Op> ops = new LinkedHashMap<>();
        ops.put("V", new Op(plain, () -> plain.channel().writeAndFlush(new ClientboundRecipeBookAddPacket(entries, false))));
        ops.put("M0", new Op(plain, () -> send(plain, entries, false)));
        ops.put("M1", new Op(plain, () -> send(plain, entries, true)));
        ops.put("M0c", new Op(compressed, () -> send(compressed, entries, false)));
        ops.put("M1c", new Op(compressed, () -> send(compressed, entries, true)));

        String[] names = ops.keySet().toArray(String[]::new);
        double[][] wallMs = new double[names.length][rounds];
        double[][] allocMib = new double[names.length][rounds];
        Map<String, List<byte[]>> lastFrames = new LinkedHashMap<>();
        long thread = Thread.currentThread().threadId();
        System.out.printf("BENCH start dataset=%s entries=%,d rounds=%d warmup=%d loadavg=%s%n", dataset, entries.size(), rounds, warmup, loadavg());
        for (int round = -warmup; round < rounds; round++) {
            for (int i = 0; i < names.length; i++) {
                int op = Math.floorMod(i + round, names.length);
                long allocatedBefore = THREADS.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                ops.get(names[op]).send().run();
                long end = System.nanoTime();
                long allocatedAfter = THREADS.getThreadAllocatedBytes(thread);
                List<byte[]> keep = round == rounds - 1 ? new ArrayList<>() : null;
                drain(ops.get(names[op]).target(), keep);
                if (keep != null) {
                    lastFrames.put(names[op], keep);
                }
                if (round >= 0) {
                    wallMs[op][round] = (end - start) / 1e6;
                    allocMib[op][round] = (allocatedAfter - allocatedBefore) / 1048576.0;
                }
            }
        }

        for (int op = 0; op < names.length; op++) {
            System.out.printf("BENCH dataset=%s op=%s median_ms=%.2f p90_ms=%.2f alloc_mib=%.2f%n",
                    dataset, names[op], median(wallMs[op]), percentile90(wallMs[op]), median(allocMib[op]));
        }
        pair(dataset, names, wallMs, "M1", "M0");
        pair(dataset, names, wallMs, "M1", "V");
        pair(dataset, names, wallMs, "M1c", "M0c");
        System.out.printf("BENCH end dataset=%s loadavg=%s%n", dataset, loadavg());

        // Whatever the timings say, the two ways of writing must put the same bytes on the wire.
        for (String[] pair : new String[][] {{"M0", "M1"}, {"M0c", "M1c"}}) {
            List<byte[]> expected = lastFrames.get(pair[0]);
            List<byte[]> actual = lastFrames.get(pair[1]);
            assertEquals(expected.size(), actual.size(), dataset + " " + Arrays.toString(pair));
            for (int i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), actual.get(i), dataset + " " + Arrays.toString(pair) + " frame " + i);
            }
        }
    }

    private TestConnection connection(boolean withCompression) throws Exception {
        TestConnection test = TestConnection.create(new PacketEncoder<>(RecipeFixtures.protocol()));
        open.add(test);
        if (withCompression) {
            // In front of the encoder in the list, so behind it in the outbound direction.
            test.channel().pipeline().addFirst("compress", new CompressionEncoder(256));
        }
        return test;
    }

    private static void send(TestConnection target, List<Entry> entries, boolean encodeOnce) {
        RecipeBookSendInterceptor.encodeOnce = encodeOnce;
        target.connection().send(new ClientboundRecipeBookAddPacket(entries, false));
    }

    /** Releases what was written, so that every operation starts from the same state; keeps a copy if asked to. */
    private static void drain(TestConnection test, List<byte[]> keep) {
        test.channel().runPendingTasks();
        ByteBuf frame;
        while ((frame = test.channel().readOutbound()) != null) {
            if (keep != null) {
                keep.add(ByteBufUtil.getBytes(frame));
            }
            frame.release();
        }
        test.recorder().messages.clear();
        test.recorder().resent.clear();
        test.recorder().withListener.clear();
    }

    private static void pair(String dataset, String[] names, double[][] wallMs, String a, String b) {
        double[] first = wallMs[Arrays.asList(names).indexOf(a)];
        double[] second = wallMs[Arrays.asList(names).indexOf(b)];
        double[] difference = new double[first.length];
        for (int i = 0; i < difference.length; i++) {
            difference[i] = first[i] - second[i];
        }
        System.out.printf("BENCH dataset=%s pair %s-%s median_ms=%.2f p90_ms=%.2f%n", dataset, a, b, median(difference), percentile90(difference));
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private static double percentile90(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[(int) Math.ceil(0.9 * sorted.length) - 1];
    }

    private static String loadavg() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
