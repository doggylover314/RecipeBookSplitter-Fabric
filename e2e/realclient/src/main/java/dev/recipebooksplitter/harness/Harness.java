package dev.recipebooksplitter.harness;

import com.google.gson.JsonArray;
import dev.recipebooksplitter.harness.mixin.ClientRecipeBookMixin;
import dev.recipebooksplitter.harness.mixin.SessionSearchTreesMixin;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.searchtree.SearchTree;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State and logic of the client test harness. All hooks (see the mixin package) call into the static methods here.
 *
 * <p>Output: one JSON object per line in {@code <rbs.harness.out>/events.jsonl}; the same lines are logged with the
 * prefix {@code [RBSH]}. Event types: {@code init}, {@code join}, {@code decode}, {@code handle}, {@code summary},
 * {@code disconnect}, {@code reconnect}, {@code exit}, {@code keepalive} and {@code slowframe}. A {@code handle} event
 * carries the number of client ticks and of frames that had completed when the packet was handled ({@code tick},
 * {@code frame}): packets that arrive in one bundle are all handled in one frame, loose ones may take several.
 *
 * <p>Frame times. A frame's <em>work time</em> is the time in {@code Minecraft.runTick} minus the wait of the frame rate
 * limiter ({@code RenderSystem.limitDisplayFPS}); with the 20 fps cap of the kit's {@code options.txt} an idle frame
 * takes 50 ms in all and has a work time of a few ms. The {@code summary} event reports, for the frames since the
 * previous summary: the slowest frame overall ({@code runMaxFrameWorkMs}), the slowest frame in the window from the
 * first decode of the run's packets to one second after the last was handled ({@code bookMaxFrameWorkMs}, with its
 * split into {@code packetsMs}, {@code tickMs} and {@code renderMs} in {@code bookMaxFrame}), the frames that handled
 * the run's packets ({@code handleFrames}, {@code handleFramesMaxWorkMs}), how many frames took at least 50 and 100 ms,
 * the number of background search builds that were scheduled ({@code searchUpdatesSinceLast}), and the keep-alive
 * packets that arrived with the largest delay since the server sent them ({@code keepAlives},
 * {@code keepAliveMaxDelayMs}; the id of a play-phase keep-alive is the server's {@code Util.getMillis()}, so the
 * difference is a delay only when the server runs on the same host). The timers are passive: they only read the clock.
 * With the digest on, the re-encoding for the SHA-256 runs on the render thread inside the frame of the packet and
 * inflates these times (see {@code rbs.harness.digest}).
 *
 * <p>System properties (all optional):
 * <ul>
 *   <li>{@code rbs.harness.server}: host:port used for reconnects (default 127.0.0.1:25565);</li>
 *   <li>{@code rbs.harness.out}: output directory (default {@code rbs-harness-out});</li>
 *   <li>{@code rbs.harness.quietMs}: no recipe activity for this long ends a "run" and produces a summary (4000);</li>
 *   <li>{@code rbs.harness.relog}: true = relog once (same as {@code relogs=1});</li>
 *   <li>{@code rbs.harness.relogs}: after the second summary (the one that follows the external "recipe give"),
 *       disconnect and join again this many times, one summary per join (default 1 with relog, else 0);</li>
 *   <li>{@code rbs.harness.summaries}: exit after this many summaries (default 2 + relogs);</li>
 *   <li>{@code rbs.harness.reconnectOnDisconnect}: reconnect this many times after a disconnect that the harness
 *       did not cause (default 0); after the last one, the next unexpected disconnect ends the client;</li>
 *   <li>{@code rbs.harness.reconnectDelayMs}: delay before a reconnect or before exiting after a disconnect (3000);</li>
 *   <li>{@code rbs.harness.maxRunMs}: safety limit, the client stops after this long (900000);</li>
 *   <li>{@code rbs.harness.digest}: set to {@code false} to skip re-encoding the received entries for the SHA-256. The
 *       re-encoding runs on the render thread, inside the frame that handles the packet, so frame times are only
 *       meaningful with it off ({@code RBS_DIGEST=0} in {@code run_client_e2e.sh}).</li>
 * </ul>
 */
public final class Harness {
    public static final Logger LOG = LoggerFactory.getLogger("RBSH");

    static final long T0 = System.nanoTime();
    static final String SERVER = System.getProperty("rbs.harness.server", "127.0.0.1:25565");
    static final long QUIET_MS = Long.getLong("rbs.harness.quietMs", 4000L);
    static final boolean RELOG = Boolean.getBoolean("rbs.harness.relog");
    static final int RELOGS = Integer.getInteger("rbs.harness.relogs", RELOG ? 1 : 0);
    static final int SUMMARIES = Integer.getInteger("rbs.harness.summaries", 2 + RELOGS);
    static final int RECONNECT_ON_DISCONNECT = Integer.getInteger("rbs.harness.reconnectOnDisconnect", 0);
    static final long RECONNECT_DELAY_MS = Long.getLong("rbs.harness.reconnectDelayMs", 3000L);
    static final long MAX_RUN_MS = Long.getLong("rbs.harness.maxRunMs", 900_000L);
    static final boolean DIGEST = !"false".equals(System.getProperty("rbs.harness.digest"));
    static final Path OUT = Path.of(System.getProperty("rbs.harness.out", "rbs-harness-out"));

    // Written by the Netty thread and the render thread.
    private static volatile long lastActivityNs = 0;
    private static volatile boolean activity = false;
    private static final ConcurrentLinkedQueue<long[]> DECODED = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger DECODE_SEQ = new AtomicInteger();

    // Background search tree builds (any thread).
    static final AtomicInteger BG_BUILDS = new AtomicInteger();
    static final AtomicLong BG_NS = new AtomicLong();
    static final AtomicInteger BG_RUNNING = new AtomicInteger();
    static final AtomicInteger BG_MAX_PARALLEL = new AtomicInteger();

    // Render thread only.
    private static int handleSeq;
    private static int joins;
    private static int summaries;
    private static int disconnects;
    private static int reconnects;
    private static boolean expectDisconnect;
    private static boolean finished;
    private static int relogsDone;
    private static long handleStartNs;
    private static long refreshStartNs;
    private static long refreshNs;
    private static long rebuildStartNs;
    private static long rebuildNs;
    private static long searchStartNs;
    private static long searchNs;
    private static long pendingAtNs;
    private static Runnable pending;
    private static boolean inGame;
    private static long ticks;
    private static long frames;
    // Frame timing (passive, see addFrameStats): frame work time is the time in runTick minus the wait of the frame rate
    // limiter, split into the packets that were handled, the ticks and the rest (rendering), plus keep-alive delays.
    private static long frameStartNs;
    private static long limiterStartNs;
    private static long limiterNs;
    private static long packetsStartNs;
    private static long framePacketsNs;
    private static long tickStartNs;
    private static long frameTickNs;
    private static int searchUpdates;
    private static int prevSearchUpdates;
    private static final TreeSet<Long> RUN_HANDLE_FRAMES = new TreeSet<>();
    private static final ArrayList<long[]> FRAME_LOG = new ArrayList<>();
    private static final ConcurrentLinkedQueue<long[]> KEEPALIVES = new ConcurrentLinkedQueue<>();

    // Run statistics (reset by each summary).
    private static int runPackets;
    private static int runEntries;
    private static int runHighlight;
    private static int runNotification;
    private static int runReplace;
    private static long runHandleNs;
    private static long runMaxHandleNs;
    private static long runDecodeNs;
    private static long runBytes;
    private static long runFirstDecodeNs;
    private static long runLastHandleEndNs;
    private static MessageDigest runDigest = newSha256();
    private static final JsonArray runPacketLog = new JsonArray();
    private static long prevCpuNs;
    private static long prevBgCpuNs;
    private static long prevGcMs;
    private static long prevBgNs;
    private static int prevBgBuilds;

    private Harness() {}

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double ms(long ns) {
        return Math.round(ns / 1000.0) / 1000.0;
    }

    private static double sinceStartMs() {
        return ms(System.nanoTime() - T0);
    }

    static synchronized void event(String type, JsonObject o) {
        o.addProperty("event", type);
        o.addProperty("tMs", sinceStartMs());
        String line = o.toString();
        LOG.info("[RBSH] {}", line);
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("events.jsonl"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("[RBSH] cannot write events.jsonl", e);
        }
    }

    public static void init() {
        JsonObject o = new JsonObject();
        o.addProperty("server", SERVER);
        o.addProperty("quietMs", QUIET_MS);
        o.addProperty("relogs", RELOGS);
        o.addProperty("summaries", SUMMARIES);
        o.addProperty("reconnectOnDisconnect", RECONNECT_ON_DISCONNECT);
        o.addProperty("digest", DIGEST);
        o.addProperty("java", System.getProperty("java.version"));
        o.addProperty("cpus", Runtime.getRuntime().availableProcessors());
        o.addProperty("maxHeapMb", Runtime.getRuntime().maxMemory() >> 20);
        event("init", o);
        prevCpuNs = processCpuNs();
    }

    // ---- hooks ---------------------------------------------------------------------------------------------

    private static final ThreadLocal<long[]> DECODE_START = ThreadLocal.withInitial(() -> new long[2]);

    /** Netty thread, at the start of PacketDecoder.decode; the packet payload is {@code bytes} long. */
    public static void decodeStart(int bytes) {
        long[] s = DECODE_START.get();
        s[0] = System.nanoTime();
        s[1] = bytes;
    }

    /** Netty thread, called after PacketDecoder.decode produced a recipe_book_add packet. */
    public static void decodeEnd(ClientboundRecipeBookAddPacket p) {
        long now = System.nanoTime();
        long[] s = DECODE_START.get();
        long durNs = now - s[0];
        int bytes = (int) s[1];
        int seq = DECODE_SEQ.incrementAndGet();
        DECODED.add(new long[] {seq, now, durNs, bytes});
        lastActivityNs = now;
        activity = true;
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("entries", p.entries().size());
        o.addProperty("replace", p.replace());
        o.addProperty("bytes", bytes);
        o.addProperty("decodeMs", ms(durNs));
        event("decode", o);
    }

    /** Render thread, after handleLogin completed on the render thread. */
    public static void onJoin() {
        joins++;
        inGame = true;
        lastActivityNs = System.nanoTime();
        activity = true;
        resetRun();
        JsonObject o = new JsonObject();
        o.addProperty("n", joins);
        if (joins == 1) {
            // What the (software) renderer reports; logged once.
            try {
                com.mojang.blaze3d.systems.GpuDevice gpu = com.mojang.blaze3d.systems.RenderSystem.getDevice();
                o.addProperty("glVendor", gpu.getVendor());
                o.addProperty("glRenderer", gpu.getRenderer());
                o.addProperty("glVersion", gpu.getVersion());
                o.addProperty("backend", gpu.getBackendName());
            } catch (RuntimeException e) {
                o.addProperty("glInfoError", e.toString());
            }
        }
        event("join", o);
    }

    public static void onHandleStart() {
        if (!Minecraft.getInstance().isSameThread()) {
            return; // first call on the Netty thread; it throws right away and the packet is queued
        }
        handleStartNs = System.nanoTime();
        refreshNs = rebuildNs = searchNs = 0;
    }

    public static void onRefreshStart() {
        refreshStartNs = System.nanoTime();
    }

    public static void onRefreshEnd() {
        refreshNs = System.nanoTime() - refreshStartNs;
    }

    public static void onRebuildStart() {
        rebuildStartNs = System.nanoTime();
    }

    public static void onRebuildEnd() {
        rebuildNs += System.nanoTime() - rebuildStartNs;
    }

    public static void onSearchUpdateStart() {
        searchUpdates++;
        searchStartNs = System.nanoTime();
    }

    public static void onSearchUpdateEnd() {
        searchNs += System.nanoTime() - searchStartNs;
    }

    private static final ThreadLocal<long[]> BG_START = ThreadLocal.withInitial(() -> new long[1]);

    /** Any thread: the background build of the recipe search tree starts. */
    public static void onBgBuildStart() {
        int running = BG_RUNNING.incrementAndGet();
        BG_MAX_PARALLEL.accumulateAndGet(running, Math::max);
        BG_START.get()[0] = System.nanoTime();
    }

    /** Any thread: the build that this thread started has finished. */
    public static void onBgBuildEnd() {
        long durNs = System.nanoTime() - BG_START.get()[0];
        BG_RUNNING.decrementAndGet();
        BG_BUILDS.incrementAndGet();
        BG_NS.addAndGet(durNs);
    }

    /** Render thread, at the end of handleRecipeBookAdd (not reached for the Netty-thread call that throws). */
    public static void onHandleEnd(ClientboundRecipeBookAddPacket p, ClientPacketListener listener) {
        long endNs = System.nanoTime();
        if (handleStartNs == 0) {
            return;
        }
        long handleNs = endNs - handleStartNs;
        handleStartNs = 0;
        handleSeq++;
        long[] decoded = DECODED.poll();
        int entries = p.entries().size();
        int highlight = 0;
        int notification = 0;
        for (ClientboundRecipeBookAddPacket.Entry e : p.entries()) {
            if (e.highlight()) highlight++;
            if (e.notification()) notification++;
        }
        ClientRecipeBook book = Minecraft.getInstance().player.getRecipeBook();
        ClientRecipeBookMixin acc = (ClientRecipeBookMixin) book;
        JsonObject o = new JsonObject();
        o.addProperty("seq", handleSeq);
        o.addProperty("entries", entries);
        o.addProperty("replace", p.replace());
        o.addProperty("highlightFlags", highlight);
        o.addProperty("notificationFlags", notification);
        o.addProperty("tick", ticks);
        o.addProperty("frame", frames);
        RUN_HANDLE_FRAMES.add(frames);
        o.addProperty("handleMs", ms(handleNs));
        o.addProperty("refreshMs", ms(refreshNs));
        o.addProperty("loopMs", ms(handleNs - refreshNs));
        o.addProperty("rebuildCollectionsMs", ms(rebuildNs));
        o.addProperty("searchTreeUpdateMs", ms(searchNs));
        if (decoded != null) {
            o.addProperty("decodeSeq", decoded[0]);
            o.addProperty("queueMs", ms(handleStartNsOf(endNs, handleNs) - decoded[1]));
            o.addProperty("bytes", decoded[3]);
            runDecodeNs += decoded[2];
            runBytes += decoded[3];
            if (runFirstDecodeNs == 0) runFirstDecodeNs = decoded[1] - decoded[2];
        }
        o.addProperty("knownAfter", acc.rbsh$known().size());
        event("handle", o);

        runPackets++;
        runEntries += entries;
        runHighlight += highlight;
        runNotification += notification;
        if (p.replace()) runReplace++;
        runHandleNs += handleNs;
        runMaxHandleNs = Math.max(runMaxHandleNs, handleNs);
        runLastHandleEndNs = endNs;
        lastActivityNs = endNs;
        activity = true;

        if (DIGEST) {
            // Outside of the timed handle region, but still on the render thread and inside this frame: re-encode every
            // entry like the server's probe does and hash the bytes. For a 9 MB book that is several hundred ms of
            // frame time.
            try {
                MessageDigest packetDigest = newSha256();
                ByteBuf buf = Unpooled.buffer(4096);
                try {
                    RegistryFriendlyByteBuf rbuf = new RegistryFriendlyByteBuf(buf, listener.registryAccess());
                    for (ClientboundRecipeBookAddPacket.Entry e : p.entries()) {
                        buf.clear();
                        ClientboundRecipeBookAddPacket.Entry.STREAM_CODEC.encode(rbuf, e);
                        packetDigest.update(buf.nioBuffer());
                        runDigest.update(buf.nioBuffer());
                    }
                } finally {
                    buf.release();
                }
                JsonObject pl = new JsonObject();
                pl.addProperty("entries", entries);
                pl.addProperty("replace", p.replace());
                pl.addProperty("sha256", HexFormat.of().formatHex(packetDigest.digest()));
                runPacketLog.add(pl);
            } catch (RuntimeException e) {
                LOG.error("[RBSH] digest failed", e);
            }
        }
    }

    private static long handleStartNsOf(long endNs, long handleNs) {
        return endNs - handleNs;
    }

    /** Any thread: an exception reached the connection's exceptionCaught; log its cause chain. */
    public static void onNetworkException(Throwable t) {
        JsonArray chain = new JsonArray();
        for (Throwable c = t; c != null && chain.size() < 8; c = c.getCause() == c ? null : c.getCause()) {
            String m = String.valueOf(c);
            chain.add(m.length() > 400 ? m.substring(0, 400) + "..." : m);
        }
        JsonObject o = new JsonObject();
        o.add("chain", chain);
        event("netty_exception", o);
    }

    /** Called from every disconnect hook (login, configuration and play phase). */
    public static void onDisconnect(String phase, String reason) {
        disconnects++;
        DECODED.clear(); // decoded but never handled packets of the connection that just ended
        boolean expected = expectDisconnect;
        expectDisconnect = false;
        inGame = false;
        JsonObject o = new JsonObject();
        o.addProperty("n", disconnects);
        o.addProperty("phase", phase);
        o.addProperty("expected", expected);
        o.addProperty("reason", reason);
        o.addProperty("summariesSoFar", summaries);
        event("disconnect", o);
        if (finished || expected) {
            return;
        }
        if (reconnects < RECONNECT_ON_DISCONNECT) {
            reconnects++;
            schedule(RECONNECT_DELAY_MS, Harness::reconnect);
        } else {
            schedule(RECONNECT_DELAY_MS, () -> finish("unexpected disconnect"));
        }
    }

    // ---- render thread tick --------------------------------------------------------------------------------

    /** Render thread, at the start of every frame ({@code Minecraft.runTick}), before the queued packets are handled. */
    public static void onFrame() {
        frames++;
        frameStartNs = System.nanoTime();
        limiterNs = 0;
        framePacketsNs = 0;
        frameTickNs = 0;
    }

    /** Render thread, around {@code PacketProcessor.processQueuedPackets} (all packets queued for this frame). */
    public static void onPacketsStart() {
        packetsStartNs = System.nanoTime();
    }

    public static void onPacketsEnd() {
        framePacketsNs += System.nanoTime() - packetsStartNs;
    }

    /** Render thread, around {@code Minecraft.tick} (a frame may run several ticks). */
    public static void onTickStart() {
        tickStartNs = System.nanoTime();
    }

    public static void onTickEnd() {
        frameTickNs += System.nanoTime() - tickStartNs;
    }

    /** Render thread, at the end of every frame ({@code Minecraft.runTick}). */
    public static void onFrameEnd() {
        long end = System.nanoTime();
        if (frameStartNs == 0) {
            return;
        }
        long full = end - frameStartNs;
        long work = full - limiterNs;
        FRAME_LOG.add(new long[] {frameStartNs, end, work, frames, framePacketsNs, frameTickNs,
                Minecraft.getInstance().getFrameTimeNs()});
        if (work >= 100_000_000L) {
            JsonObject o = new JsonObject();
            o.addProperty("frame", frames);
            o.addProperty("tick", ticks);
            o.addProperty("workMs", ms(work));
            o.addProperty("fullMs", ms(full));
            event("slowframe", o);
        }
        frameStartNs = 0;
    }

    /** Render thread, around {@code RenderSystem.limitDisplayFPS} (the wait that keeps the frame rate at the cap). */
    public static void onLimiterStart() {
        limiterStartNs = System.nanoTime();
    }

    public static void onLimiterEnd() {
        limiterNs += System.nanoTime() - limiterStartNs;
    }

    /** Netty thread: a keep-alive arrived. The id is the server's Util.getMillis() (System.nanoTime() / 10^6) at the time it
     *  was sent; on one host System.nanoTime() is CLOCK_MONOTONIC, so the difference is the one-way delay. */
    public static void onKeepAlive(long id) {
        // play phase: System.nanoTime() / 10^6; configuration phase: epoch milliseconds
        long delay = id > 100_000_000_000L ? System.currentTimeMillis() - id : System.nanoTime() / 1_000_000L - id;
        KEEPALIVES.add(new long[] {System.nanoTime(), delay});
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("delayMs", delay);
        event("keepalive", o);
    }

    public static void onTick(Minecraft mc) {
        ticks++;
        long now = System.nanoTime();
        if (!finished && (now - T0) / 1_000_000L > MAX_RUN_MS) {
            finish("maxRunMs reached");
            return;
        }
        if (pending != null && now >= pendingAtNs) {
            Runnable r = pending;
            pending = null;
            r.run();
            return;
        }
        if (finished || !inGame || mc.player == null || !activity) {
            return;
        }
        if ((now - lastActivityNs) / 1_000_000L < QUIET_MS) {
            return;
        }
        activity = false;
        summary(mc);
        if (summaries >= SUMMARIES) {
            schedule(1000, () -> finish("all summaries done"));
        } else if (summaries >= 2 && relogsDone < RELOGS) {
            relogsDone++;
            schedule(1000, Harness::relog);
        }
    }

    private static void schedule(long delayMs, Runnable r) {
        pendingAtNs = System.nanoTime() + delayMs * 1_000_000L;
        pending = r;
    }

    private static void resetRun() {
        runPackets = runEntries = runHighlight = runNotification = runReplace = 0;
        runHandleNs = runMaxHandleNs = runDecodeNs = runBytes = 0;
        runFirstDecodeNs = runLastHandleEndNs = 0;
        runDigest = newSha256();
        while (runPacketLog.size() > 0) runPacketLog.remove(0);
    }

    private static long processCpuNs() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getProcessCpuTime();
        }
        return -1;
    }

    private static long bgCpuNs() {
        ThreadMXBean tm = ManagementFactory.getThreadMXBean();
        long total = 0;
        for (ThreadInfo ti : tm.getThreadInfo(tm.getAllThreadIds())) {
            if (ti != null && ti.getThreadName().startsWith("Worker-Main")) {
                long c = tm.getThreadCpuTime(ti.getThreadId());
                if (c > 0) total += c;
            }
        }
        return total;
    }

    private static long gcMs() {
        long t = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionTime() > 0) t += gc.getCollectionTime();
        }
        return t;
    }

    private static void summary(Minecraft mc) {
        summaries++;
        ClientRecipeBook book = mc.player.getRecipeBook();
        ClientRecipeBookMixin acc = (ClientRecipeBookMixin) book;
        Map<RecipeDisplayId, RecipeDisplayEntry> known = acc.rbsh$known();
        Set<RecipeDisplayId> highlight = acc.rbsh$highlight();
        int[] ids = known.keySet().stream().mapToInt(RecipeDisplayId::index).sorted().toArray();
        StringBuilder sb = new StringBuilder();
        for (int id : ids) sb.append(id).append(',');
        String idHash = HexFormat.of().formatHex(newSha256().digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        long collectionRecipes = 0;
        for (RecipeCollection c : book.getCollections()) collectionRecipes += c.getRecipes().size();

        // How far is the background search tree build behind? (does not block unless it is still running)
        CompletableFuture<SearchTree<RecipeCollection>> future = ((SessionSearchTreesMixin) mc.getConnection().searchTrees()).rbsh$recipeSearch();
        boolean searchDone = future.isDone();
        long j0 = System.nanoTime();
        try {
            future.join();
        } catch (RuntimeException e) {
            LOG.warn("[RBSH] search tree future: {}", e.toString());
        }
        long searchJoinNs = System.nanoTime() - j0;

        long cpu = processCpuNs();
        long bgCpu = bgCpuNs();
        long gc = gcMs();
        long bgNs = BG_NS.get();
        int bgBuilds = BG_BUILDS.get();

        JsonObject o = new JsonObject();
        o.addProperty("n", summaries);
        o.addProperty("joinNo", joins);
        o.addProperty("knownRecipes", known.size());
        o.addProperty("highlighted", highlight.size());
        o.addProperty("recipesInCollections", collectionRecipes);
        o.addProperty("collections", book.getCollections().size());
        o.addProperty("idsSha256", idHash);
        o.addProperty("minId", ids.length > 0 ? ids[0] : -1);
        o.addProperty("maxId", ids.length > 0 ? ids[ids.length - 1] : -1);
        o.addProperty("runPackets", runPackets);
        o.addProperty("runEntries", runEntries);
        o.addProperty("runReplacePackets", runReplace);
        o.addProperty("runHighlightFlags", runHighlight);
        o.addProperty("runNotificationFlags", runNotification);
        o.addProperty("runBytes", runBytes);
        o.addProperty("runEntryDigestSha256", DIGEST ? HexFormat.of().formatHex(runDigest.digest()) : null);
        o.add("runPacketDigests", runPacketLog.deepCopy());
        o.addProperty("runHandleTotalMs", ms(runHandleNs));
        o.addProperty("runHandleMaxMs", ms(runMaxHandleNs));
        o.addProperty("runHandleAvgMs", runPackets == 0 ? 0 : ms(runHandleNs / runPackets));
        o.addProperty("runDecodeTotalMs", ms(runDecodeNs));
        o.addProperty("runFirstDecodeToLastHandleMs", runPackets == 0 ? 0 : ms(runLastHandleEndNs - runFirstDecodeNs));
        o.addProperty("searchTreeDoneAtSummary", searchDone);
        o.addProperty("searchTreeJoinMs", ms(searchJoinNs));
        o.addProperty("bgBuildsTotal", bgBuilds);
        o.addProperty("bgBuildsSinceLast", bgBuilds - prevBgBuilds);
        o.addProperty("bgBuildMsSinceLast", ms(bgNs - prevBgNs));
        o.addProperty("bgMaxParallel", BG_MAX_PARALLEL.get());
        o.addProperty("bgWorkerCpuMsSinceLast", ms(bgCpu - prevBgCpuNs));
        o.addProperty("processCpuMsSinceLast", cpu < 0 ? -1 : ms(cpu - prevCpuNs));
        o.addProperty("gcMsSinceLast", gc - prevGcMs);
        Runtime rt = Runtime.getRuntime();
        o.addProperty("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        addFrameStats(o);
        event("summary", o);

        prevCpuNs = cpu;
        prevBgCpuNs = bgCpu;
        prevGcMs = gc;
        prevBgNs = bgNs;
        prevBgBuilds = bgBuilds;
        resetRun();
    }

    /** Frame statistics since the last summary, and for the frames that overlap the book's arrival window. */
    private static void addFrameStats(JsonObject o) {
        long winStart = runFirstDecodeNs;
        long winEnd = runLastHandleEndNs + 1_000_000_000L;
        int n = 0, over50 = 0, over100 = 0, bn = 0, b50 = 0, b100 = 0;
        long max = 0, maxFull = 0, bmax = 0, bmaxFull = 0;
        long[] bmaxFrame = null;
        for (long[] f : FRAME_LOG) {
            n++;
            max = Math.max(max, f[2]);
            maxFull = Math.max(maxFull, f[1] - f[0]);
            if (f[2] >= 50_000_000L) over50++;
            if (f[2] >= 100_000_000L) over100++;
            if (runPackets > 0 && f[1] >= winStart && f[0] <= winEnd) {
                bn++;
                if (f[2] > bmax) {
                    bmaxFrame = f;
                }
                bmax = Math.max(bmax, f[2]);
                bmaxFull = Math.max(bmaxFull, f[1] - f[0]);
                if (f[2] >= 50_000_000L) b50++;
                if (f[2] >= 100_000_000L) b100++;
            }
        }
        JsonArray hf = new JsonArray();
        long hfMax = 0;
        for (long[] f : FRAME_LOG) {
            if (RUN_HANDLE_FRAMES.contains(f[3])) {
                JsonObject x = new JsonObject();
                x.addProperty("frame", f[3]);
                x.addProperty("workMs", ms(f[2]));
                x.addProperty("packetsMs", ms(f[4]));
                x.addProperty("tickMs", ms(f[5]));
                x.addProperty("renderMs", ms(f[6]));
                hf.add(x);
                hfMax = Math.max(hfMax, f[2]);
            }
        }
        RUN_HANDLE_FRAMES.clear();
        o.add("handleFrames", hf);
        o.addProperty("handleFramesMaxWorkMs", ms(hfMax));
        o.addProperty("searchUpdatesSinceLast", searchUpdates - prevSearchUpdates);
        prevSearchUpdates = searchUpdates;
        FRAME_LOG.clear();
        o.addProperty("runFrames", n);
        o.addProperty("runMaxFrameWorkMs", ms(max));
        o.addProperty("runMaxFrameFullMs", ms(maxFull));
        o.addProperty("runFramesOver50WorkMs", over50);
        o.addProperty("runFramesOver100WorkMs", over100);
        o.addProperty("bookFrames", bn);
        o.addProperty("bookMaxFrameWorkMs", ms(bmax));
        o.addProperty("bookMaxFrameFullMs", ms(bmaxFull));
        if (bmaxFrame != null) {
            JsonObject w = new JsonObject();
            w.addProperty("frame", bmaxFrame[3]);
            w.addProperty("workMs", ms(bmaxFrame[2]));
            w.addProperty("packetsMs", ms(bmaxFrame[4]));
            w.addProperty("tickMs", ms(bmaxFrame[5]));
            w.addProperty("renderMs", ms(bmaxFrame[6]));
            o.add("bookMaxFrame", w);
        }
        o.addProperty("bookFramesOver50WorkMs", b50);
        o.addProperty("bookFramesOver100WorkMs", b100);
        int kc = 0;
        long kmax = Long.MIN_VALUE;
        long[] k;
        while ((k = KEEPALIVES.poll()) != null) {
            kc++;
            kmax = Math.max(kmax, k[1]);
        }
        o.addProperty("keepAlives", kc);
        o.addProperty("keepAliveMaxDelayMs", kc == 0 ? -1 : kmax);
    }

    private static void relog() {
        Minecraft mc = Minecraft.getInstance();
        expectDisconnect = true;
        JsonObject o = new JsonObject();
        o.addProperty("action", "disconnect");
        event("reconnect", o);
        mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        schedule(RECONNECT_DELAY_MS, Harness::reconnect);
    }

    private static void reconnect() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        o.addProperty("action", "connect");
        o.addProperty("server", SERVER);
        event("reconnect", o);
        ServerData data = new ServerData("RBS harness", SERVER, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(SERVER), data, false, null);
    }

    private static void finish(String why) {
        if (finished) return;
        finished = true;
        JsonObject o = new JsonObject();
        o.addProperty("why", why);
        o.addProperty("joins", joins);
        o.addProperty("summaries", summaries);
        o.addProperty("disconnects", disconnects);
        event("exit", o);
        Minecraft.getInstance().stop();
    }
}
