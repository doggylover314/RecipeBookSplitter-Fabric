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
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketEncoder;
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
 */
public final class RecipeBookSendInterceptor {
    /** Set to {@code true} to log a SHA-256 digest of the entry bytes of every measured packet (used by the e2e kit). */
    static final String DEBUG_DIGEST_PROPERTY = "recipebooksplitter.debugDigest";
    /** Largest frame a 3-byte VarInt length prefix can describe; the effective packet limit when compression is off. */
    static final int FRAME_LIMIT_BYTES = 2_097_151;

    private static final Logger LOGGER = RecipeBookSplitter.LOGGER;
    private static final boolean DEBUG_DIGEST = Boolean.getBoolean(DEBUG_DIGEST_PROPERTY);
    /**
     * The packet {@link #write} is sending on this thread right now. Only that exact object passes the hook unsplit.
     * A plain flag would also let any other recipe packet through that is sent while we write, for example from a send
     * listener to a player whose connection runs on the same event loop.
     */
    private static final ThreadLocal<ClientboundRecipeBookAddPacket> RESENDING = new ThreadLocal<>();
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
            if (packet.entries().size() < 2 || packet == RESENDING.get()) {
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

    /** The packet being re-sent on this thread, if any; for tests. */
    static @Nullable ClientboundRecipeBookAddPacket currentlyResent() {
        return RESENDING.get();
    }

    /** Runs on the event loop. Never throws; if anything fails the original packet is sent unsplit. */
    static void splitAndWrite(Connection connection, Channel channel, ClientboundRecipeBookAddPacket packet,
                              @Nullable ChannelFutureListener listener, boolean flush) {
        if (!channel.isOpen()) {
            // The client went away while the task was queued: don't measure for nobody, let vanilla deal with it.
            write(connection, List.of(packet), listener, flush);
            return;
        }
        long start = System.nanoTime();
        SplitterConfig config = RecipeBookSplitter.config();
        List<ClientboundRecipeBookAddPacket> toSend = List.of(packet);
        EntrySizer.Measurement measurement = null;
        List<ChunkPlanner.Chunk> plan = null;
        try {
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
            } else {
                PacketEncoderInvoker invoker = (PacketEncoderInvoker) encoder;
                measurement = EntrySizer.measure(packet.entries(), (p, out) -> invoker.recipebooksplitter$encode(ctx, p, out), DEBUG_DIGEST);
                plan = ChunkPlanner.plan(measurement.entryBytes(), measurement.fixedOverheadBytes(), config.maxChunkBytes());
                if (plan.size() > 1) {
                    toSend = ChunkPlanner.split(packet.entries(), packet.replace(), plan, ClientboundRecipeBookAddPacket::new);
                }
            }
        } catch (Throwable t) {
            toSend = List.of(packet);
            measurement = null;
            LOGGER.error("[RecipeBookSplitter] {}: could not measure/split recipe book packet ({} entries); sending it unsplit",
                    describe(connection), packet.entries().size(), t);
        }
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        write(connection, toSend, listener, flush);

        if (measurement != null) {
            try {
                report(connection, packet, measurement, plan, config, tookMs);
            } catch (Throwable t) {
                LOGGER.error("[RecipeBookSplitter] {}: could not log the recipe book packet details", describe(connection), t);
            }
        }
    }

    /**
     * Sends through {@code Connection.send} again so vanilla's connected/pending/flush handling stays in charge. Every
     * chunk but the last is written without flush and without listener, so flushing and completion behave as they
     * would for the original single packet.
     */
    private static void write(Connection connection, List<ClientboundRecipeBookAddPacket> packets,
                              @Nullable ChannelFutureListener listener, boolean flush) {
        ClientboundRecipeBookAddPacket outer = RESENDING.get();
        try {
            int last = packets.size() - 1;
            for (int i = 0; i <= last; i++) {
                RESENDING.set(packets.get(i));
                connection.send(packets.get(i), i == last ? listener : null, i == last && flush);
            }
        } catch (Throwable t) {
            LOGGER.error("[RecipeBookSplitter] {}: error while sending recipe book packet(s)", describe(connection), t);
        } finally {
            RESENDING.set(outer);
        }
    }

    private static void report(Connection connection, ClientboundRecipeBookAddPacket packet, EntrySizer.Measurement measurement,
                               List<ChunkPlanner.Chunk> plan, SplitterConfig config, long tookMs) {
        String player = describe(connection);
        long total = measurement.totalBytes();
        int entries = packet.entries().size();

        if (plan.size() > 1) {
            if (config.logSplits()) {
                long largest = plan.stream().mapToLong(ChunkPlanner.Chunk::bytes).max().orElse(0);
                LOGGER.info("[RecipeBookSplitter] {}: split {} recipe book packet ({} bytes, {} entries, replace={}) into {} chunks (largest {} bytes, limit {} bytes, {} ms)",
                        player, Sizes.mib(total), Sizes.bytes(total), Sizes.bytes(entries), packet.replace(), plan.size(),
                        Sizes.bytes(largest), Sizes.bytes(config.maxChunkBytes()), tookMs);
            }
        } else {
            LOGGER.debug("[RecipeBookSplitter] {}: recipe book packet {} bytes ({} entries) is within the limit; sent unsplit",
                    player, Sizes.bytes(total), entries);
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
