package dev.recipebooksplitter.split;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.mixin.ConnectionAccessor;
import dev.recipebooksplitter.mixin.PacketEncoderInvoker;
import dev.recipebooksplitter.util.Sizes;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Splits oversized {@link ClientboundRecipeBookAddPacket}s. {@link #onSend} is called from the {@code Connection.send}
 * hook; the measuring, splitting and writing happen as one task on the channel's event loop.
 *
 * <p>Running on the event loop keeps the ordering of vanilla's own send path: {@code Connection.sendPacket} also hands
 * packets from other threads to the event loop, so our task takes the place the original packet's write task would have
 * had. All chunks are then written back to back inside that one task, so nothing can end up between them. The price is
 * that measuring (about one extra encode of the whole packet) runs on that event loop thread, which other connections
 * share; it is kept off the server thread.
 *
 * <p>The measuring already encodes every entry once with the connection's real encoder, so the bytes are kept
 * ({@link EncodedEntries}) and, while the chunks are written, {@code PacketEncoder} puts them into the chunk instead of
 * running the codec on the entries a second time (see {@link #preparedFor}). That is only done for the exact packet
 * object being written by this task to the exact encoder that measured it, so anything unexpected in between makes
 * the packet be encoded normally.
 */
public final class RecipeBookSendInterceptor {
    /** Set to {@code true} to log a SHA-256 digest of the entry bytes of every measured packet (used by the e2e kit). */
    static final String DEBUG_DIGEST_PROPERTY = "recipebooksplitter.debugDigest";
    /** Set to {@code false} to encode the chunks again after measuring, as 1.0.0 did. */
    static final String ENCODE_ONCE_PROPERTY = "recipebooksplitter.encodeOnce";
    /**
     * Set to {@code true} to also encode every packet that is written from measured bytes the normal way, compare, and
     * send the normally encoded bytes (used by the e2e kit to find content that encodes differently in a chunk).
     */
    static final String VERIFY_ENCODE_ONCE_PROPERTY = "recipebooksplitter.verifyEncodeOnce";
    /** Largest frame a 3-byte VarInt length prefix can describe; the effective packet limit when compression is off. */
    static final int FRAME_LIMIT_BYTES = 2_097_151;

    private static final Logger LOGGER = RecipeBookSplitter.LOGGER;
    private static final boolean DEBUG_DIGEST = Boolean.getBoolean(DEBUG_DIGEST_PROPERTY);
    /** Not final so tests can switch them. */
    static volatile boolean encodeOnce = !"false".equalsIgnoreCase(System.getProperty(ENCODE_ONCE_PROPERTY));
    static volatile boolean verifyEncodeOnce = Boolean.getBoolean(VERIFY_ENCODE_ONCE_PROPERTY);

    /**
     * What {@link #write} is sending on this thread right now: {@code packet} is the only object that passes the hook
     * unsplit (a plain flag would also let any other recipe packet through that is sent while we write, for example
     * from a send listener to a player whose connection runs on the same event loop), and {@code prepared} are the
     * measured bytes of the packets of this send.
     */
    private record Resend(Packet<?> packet, List<PreparedPacket> prepared) {}

    private static final ThreadLocal<Resend> RESENDING = new ThreadLocal<>();
    /** Package-private so tests can reset it. */
    static final AtomicBoolean WARNED_NO_ENCODER = new AtomicBoolean();

    private RecipeBookSendInterceptor() {}

    /**
     * @return true if the packet was taken over (the caller must cancel the original send), false to let vanilla
     *         send it. Never throws: whatever goes wrong before the work is scheduled or started leaves the packet to
     *         vanilla.
     */
    public static boolean onSend(Connection connection, ClientboundRecipeBookAddPacket packet,
                                 @Nullable ChannelFutureListener listener, boolean flush) {
        boolean handedOver = false;
        try {
            // Nothing to split with fewer than two entries; this also keeps the common case free of any other work.
            if (packet.entries().size() < 2 || packet == currentlyResent()) {
                return false;
            }
            // For a connection that is not open, vanilla only queues the packet (a server connection never reopens),
            // so there is nothing to write chunks to.
            if (!connection.isConnected()) {
                return false;
            }
            Channel channel = ((ConnectionAccessor) connection).recipebooksplitter$getChannel();
            EventLoop loop = channel.eventLoop();
            if (loop.inEventLoop()) {
                handedOver = true; // from here on chunks may already be written, so vanilla must not send the original too
                splitAndWrite(connection, channel, packet, listener, flush);
            } else {
                loop.execute(() -> splitAndWrite(connection, channel, packet, listener, flush));
            }
            return true;
        } catch (RejectedExecutionException e) {
            // The event loop is shutting down; behave exactly like vanilla.
            return false;
        } catch (Throwable t) {
            LOGGER.debug("[RecipeBookSplitter] problem while taking over a recipe book packet (taken over: {})", handedOver, t);
            return handedOver;
        }
    }

    /** The packet being re-sent on this thread, if any. */
    static @Nullable Packet<?> currentlyResent() {
        Resend resend = RESENDING.get();
        return resend == null ? null : resend.packet();
    }

    /**
     * For {@code PacketEncoderMixin}: the measured bytes of {@code packet} if this thread is writing the chunks of a
     * send that measured exactly this packet object with exactly this encoder instance, and they were not used yet.
     * Anything else (a handler that replaced or delayed the packet, another connection, a second write of the same
     * object) gets null and is encoded normally.
     */
    public static @Nullable PreparedPacket preparedFor(Object encoder, ClientboundRecipeBookAddPacket packet) {
        Resend resend = RESENDING.get();
        if (resend != null) {
            for (PreparedPacket prepared : resend.prepared()) {
                if (prepared.packet == packet && prepared.encoder == encoder && prepared.outcome == PreparedPacket.Outcome.PENDING) {
                    return prepared;
                }
            }
        }
        return null;
    }

    /** The connection's encoder, and a way to run it on a packet. */
    private record Probe(Object encoder, EntrySizer.PacketWriter writer) {}

    /**
     * What to write for one recipe book packet.
     *
     * @param packets the chunks, or just the original if it is within the limit
     * @param prepared the measured bytes of {@code packets}; empty if they were not kept
     */
    private record Split(ClientboundRecipeBookAddPacket original, List<ClientboundRecipeBookAddPacket> packets,
                         List<PreparedPacket> prepared, EntrySizer.Measurement measurement, List<ChunkPlanner.Chunk> plan) {}

    /** Runs on the event loop. Never throws; if anything fails the original packet is sent unsplit. */
    static void splitAndWrite(Connection connection, Channel channel, ClientboundRecipeBookAddPacket packet,
                              @Nullable ChannelFutureListener listener, boolean flush) {
        if (!channel.isOpen()) {
            // The client went away while the task was queued: don't measure for nobody, let vanilla deal with it.
            write(connection, List.of(packet), List.of(), listener, flush);
            return;
        }
        long start = System.nanoTime();
        SplitterConfig config = RecipeBookSplitter.config();
        Split split = null;
        try {
            Probe probe = probe(connection, channel);
            if (probe != null) {
                split = plan(packet, probe, config);
            }
        } catch (Throwable t) {
            LOGGER.error("[RecipeBookSplitter] {}: could not measure/split recipe book packet ({} entries); sending it unsplit",
                    describe(connection), packet.entries().size(), t);
        }
        long measuredAt = System.nanoTime();

        if (split == null) {
            write(connection, List.of(packet), List.of(), listener, flush);
            return;
        }
        write(connection, split.packets(), split.prepared(), listener, flush);
        long writtenAt = System.nanoTime();

        try {
            report(connection, split, config, (measuredAt - start) / 1_000_000, (writtenAt - measuredAt) / 1_000_000);
        } catch (Throwable t) {
            LOGGER.error("[RecipeBookSplitter] {}: could not log the recipe book packet details", describe(connection), t);
        }
    }

    /** @return null if the connection has no {@code PacketEncoder} to measure with */
    private static @Nullable Probe probe(Connection connection, Channel channel) {
        ChannelHandlerContext ctx = channel.pipeline().context(HandlerNames.ENCODER);
        if (ctx == null || !(ctx.handler() instanceof PacketEncoder<?> encoder)) {
            // Fake players (e.g. Carpet's) sit on an EmbeddedChannel without an encoder and never write to a
            // network, so only a real channel without an encoder is worth a warning.
            if (!(channel instanceof EmbeddedChannel) && WARNED_NO_ENCODER.compareAndSet(false, true)) {
                LOGGER.warn("[RecipeBookSplitter] {}: no PacketEncoder named '{}' in the pipeline; sending recipe book packet unsplit (further occurrences logged at DEBUG)",
                        describe(connection), HandlerNames.ENCODER);
            } else {
                LOGGER.debug("[RecipeBookSplitter] {}: no PacketEncoder in the pipeline; sending recipe book packet unsplit", describe(connection));
            }
            return null;
        }
        PacketEncoderInvoker invoker = (PacketEncoderInvoker) encoder;
        return new Probe(encoder, (p, out) -> invoker.recipebooksplitter$encode(ctx, p, out));
    }

    /** Measures the packet and cuts it into chunks; the packet itself is the only "chunk" if it fits. */
    private static Split plan(ClientboundRecipeBookAddPacket packet, Probe probe, SplitterConfig config) throws Exception {
        EntrySizer.Measurement measurement = EntrySizer.measure(packet.entries(), probe.writer(), DEBUG_DIGEST, encodeOnce);
        List<ChunkPlanner.Chunk> plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), config.maxChunkBytes());
        List<ClientboundRecipeBookAddPacket> packets = plan.size() > 1
                ? ChunkPlanner.split(packet.entries(), packet.replace(), plan, ClientboundRecipeBookAddPacket::new)
                : List.of(packet);
        return new Split(packet, packets, prepare(packets, plan, measurement.encoded(), probe.encoder()), measurement, plan);
    }

    /** One {@link PreparedPacket} per packet, or none if the entry bytes were not kept. */
    private static List<PreparedPacket> prepare(List<ClientboundRecipeBookAddPacket> packets, List<ChunkPlanner.Chunk> plan,
                                                @Nullable EncodedEntries bytes, Object encoder) {
        if (bytes == null) {
            return List.of();
        }
        List<PreparedPacket> prepared = new ArrayList<>(packets.size());
        for (int i = 0; i < packets.size(); i++) {
            ChunkPlanner.Chunk chunk = plan.get(i);
            prepared.add(new PreparedPacket(packets.get(i), encoder, bytes, IntStream.range(chunk.start(), chunk.end()).toArray(), chunk.bytes()));
        }
        return prepared;
    }

    /**
     * Sends through {@code Connection.send} again so vanilla's connected/pending/flush handling stays in charge. Every
     * packet but the last is written without flush and without listener, so flushing and completion behave as they
     * would for the original single packet.
     *
     * <p>{@code prepared} can be found through {@link #preparedFor} only while this runs. The pipeline hands a packet
     * to the encoder synchronously from {@code send} on this thread; a handler that holds a packet back and writes it
     * later finds nothing and the packet is encoded normally.
     */
    private static void write(Connection connection, List<? extends Packet<?>> packets, List<PreparedPacket> prepared,
                              @Nullable ChannelFutureListener listener, boolean flush) {
        Resend outer = RESENDING.get();
        try {
            int last = packets.size() - 1;
            for (int i = 0; i <= last; i++) {
                Packet<?> packet = packets.get(i);
                RESENDING.set(new Resend(packet, prepared));
                connection.send(packet, i == last ? listener : null, i == last && flush);
            }
        } catch (Throwable t) {
            LOGGER.error("[RecipeBookSplitter] {}: error while sending recipe book packet(s)", describe(connection), t);
        } finally {
            RESENDING.set(outer);
        }
    }

    private static void report(Connection connection, Split split, SplitterConfig config, long measureMs, long writeMs) {
        ClientboundRecipeBookAddPacket packet = split.original();
        EntrySizer.Measurement measurement = split.measurement();
        List<ChunkPlanner.Chunk> plan = split.plan();
        String player = describe(connection);
        long total = measurement.totalBytes();
        int entries = packet.entries().size();
        String encodeOnceNote = encodeOnceNote(split);

        if (plan.size() > 1) {
            if (config.logSplits()) {
                long largest = plan.stream().mapToLong(ChunkPlanner.Chunk::bytes).max().orElse(0);
                LOGGER.info("[RecipeBookSplitter] {}: split {} recipe book packet ({} bytes, {} entries, replace={}) into {} chunks (largest {} bytes, limit {} bytes, {} ms, written in {} ms){}",
                        player, Sizes.mib(total), Sizes.bytes(total), Sizes.bytes(entries), packet.replace(), plan.size(),
                        Sizes.bytes(largest), Sizes.bytes(config.maxChunkBytes()), measureMs, writeMs, encodeOnceNote);
            }
        } else {
            LOGGER.debug("[RecipeBookSplitter] {}: recipe book packet {} bytes ({} entries) is within the limit; sent unsplit{}",
                    player, Sizes.bytes(total), entries, encodeOnceNote);
        }

        for (ChunkPlanner.Chunk chunk : plan) {
            if (chunk.oversized()) {
                int index = chunk.start();
                LOGGER.warn("[RecipeBookSplitter] {}: recipe display entry #{} (display id {}) is {} bytes on its own, more than maxChunkBytes ({}); sending it in a chunk by itself ({} bytes){}",
                        player, index, packet.entries().get(index).contents().id().index(), Sizes.bytes(measurement.entryBytes()[index]),
                        Sizes.bytes(config.maxChunkBytes()), Sizes.bytes(chunk.bytes()),
                        chunk.bytes() > FRAME_LIMIT_BYTES ? "; this exceeds the 2,097,151-byte frame limit when network compression is disabled" : "");
            }
        }

        if (measurement.sha256() != null) {
            // Plain digits, no grouping: e2e/check.py parses this line.
            LOGGER.info("[RecipeBookSplitter] digest player={} entries={} replace={} bytes={} chunks={} sha256={}",
                    player, entries, packet.replace(), total, plan.size(), measurement.sha256());
        }
    }

    /**
     * How many of the packets were written from the measured bytes (or, in verify mode, matched a normal encode); "0 of
     * N" means the bytes were encoded again, which is correct but costs what 1.0.0 cost.
     */
    private static String encodeOnceNote(Split split) {
        if (!encodeOnce) {
            return "";
        }
        PreparedPacket.Outcome counted = verifyEncodeOnce ? PreparedPacket.Outcome.VERIFIED : PreparedPacket.Outcome.REUSED;
        long matching = split.prepared().stream().filter(prepared -> prepared.outcome == counted).count();
        int packets = split.packets().size();
        return verifyEncodeOnce
                ? "; " + matching + " of " + packets + " packets verified against a normal encode"
                : "; measured bytes reused for " + matching + " of " + packets + " packets";
    }

    /** Logs which of the optional debugging switches are on. */
    public static void logStartup() {
        if (!encodeOnce) {
            LOGGER.info("[RecipeBookSplitter] encode once is off (-D{}=false): recipe book chunks are encoded again after measuring", ENCODE_ONCE_PROPERTY);
        }
        if (verifyEncodeOnce) {
            LOGGER.info("[RecipeBookSplitter] verifyEncodeOnce is on: every packet written from measured bytes is also encoded normally and compared; the normally encoded bytes are sent (for testing only)");
        }
    }

    /** The player's name once the connection has one, otherwise its remote address. */
    static String describe(Connection connection) {
        try {
            if (connection.getPacketListener() instanceof ServerCommonPacketListenerImpl listener) {
                return listener.getOwner().name();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the address below.
        }
        return String.valueOf(connection.getRemoteAddress());
    }
}
