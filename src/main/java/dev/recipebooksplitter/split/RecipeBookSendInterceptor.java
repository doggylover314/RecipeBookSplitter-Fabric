package dev.recipebooksplitter.split;

import dev.recipebooksplitter.RecipeBookSplitter;
import dev.recipebooksplitter.config.SplitterConfig;
import dev.recipebooksplitter.config.SplitterConfig.UndeliverableEntries;
import dev.recipebooksplitter.mixin.ConnectionAccessor;
import dev.recipebooksplitter.mixin.PacketEncoderInvoker;
import dev.recipebooksplitter.util.Sizes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Splits oversized {@link ClientboundRecipeBookAddPacket}s, also inside a {@link ClientboundBundlePacket}, and keeps
 * out entries that the connection could never send. {@link #onSend} and {@link #onSendBundle} are called from the
 * {@code Connection.send} hook; the measuring, splitting and writing happen as one task on the channel's event loop.
 *
 * <p>Running on the event loop keeps the ordering of vanilla's own send path: {@code Connection.sendPacket} also hands
 * packets from other threads to the event loop, so our task takes the place the original packet's write task would have
 * had. All chunks are then written back to back inside that one task (a bundle is rebuilt and written as one packet),
 * so nothing can end up between them. The price is that measuring (about one extra encode of the whole packet) runs on
 * that event loop thread, which other connections share; it is kept off the server thread.
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

    private static final Logger LOGGER = RecipeBookSplitter.LOGGER;
    /** Not final so tests can switch them. */
    static volatile boolean debugDigest = Boolean.getBoolean(DEBUG_DIGEST_PROPERTY);
    static volatile boolean encodeOnce = !"false".equalsIgnoreCase(System.getProperty(ENCODE_ONCE_PROPERTY));
    static volatile boolean verifyEncodeOnce = Boolean.getBoolean(VERIFY_ENCODE_ONCE_PROPERTY);

    /**
     * What {@link #write} is sending on this thread right now: {@code packet} (a chunk, or a bundle) is the only object
     * that passes the hook unchanged (a plain flag would also let any other packet through that is sent while we write,
     * for example from a send listener to a player whose connection runs on the same event loop), and {@code prepared}
     * are the measured bytes of the recipe book packets of this send.
     */
    private record Resend(Packet<?> packet, List<PreparedPacket> prepared) {}

    private static final ThreadLocal<Resend> RESENDING = new ThreadLocal<>();
    /** Package-private so tests can reset it. */
    static final AtomicBoolean WARNED_NO_ENCODER = new AtomicBoolean();
    /** The reasons for not keeping measured bytes that were logged already: each is reported once. Package-private so tests can reset it. */
    static final Set<EntrySizer.NotKept> LOGGED_NOT_KEPT = ConcurrentHashMap.newKeySet();

    private RecipeBookSendInterceptor() {}

    /**
     * A packet needs at least this many entries to be looked at: two to split it, and with
     * {@code undeliverableEntries = drop} one, because a lone entry may be one that no connection could send.
     */
    static int minEntries(SplitterConfig config) {
        return config.undeliverableEntries() == UndeliverableEntries.DROP ? 1 : 2;
    }

    /**
     * @return true if the packet was taken over (the caller must cancel the original send), false to let vanilla
     *         send it. Never throws: whatever goes wrong before the work is scheduled or started leaves the packet to
     *         vanilla.
     */
    public static boolean onSend(Connection connection, ClientboundRecipeBookAddPacket packet,
                                 @Nullable ChannelFutureListener listener, boolean flush) {
        try {
            // Not enough entries to be worth a look; this also keeps the common case free of any other work.
            if (packet == currentlyResent() || packet.entries().size() < minEntries(RecipeBookSplitter.config())) {
                return false;
            }
        } catch (Throwable t) {
            LOGGER.debug("[RecipeBookSplitter] problem while looking at a recipe book packet", t);
            return false;
        }
        return takeOver(connection, channel -> splitAndWrite(connection, channel, packet, listener, flush));
    }

    /**
     * The same for a bundle: taken over only if it holds a recipe book packet worth measuring. A bundle without one
     * (every bundle vanilla sends) costs one look at each sub-packet and is left to vanilla.
     */
    public static boolean onSendBundle(Connection connection, ClientboundBundlePacket bundle,
                                       @Nullable ChannelFutureListener listener, boolean flush) {
        try {
            if (bundle == currentlyResent() || !containsCandidate(bundle, minEntries(RecipeBookSplitter.config()))) {
                return false;
            }
        } catch (Throwable t) {
            LOGGER.debug("[RecipeBookSplitter] problem while looking at a bundle packet", t);
            return false;
        }
        return takeOver(connection, channel -> splitBundleAndWrite(connection, channel, bundle, listener, flush));
    }

    /**
     * Only a {@link Collection} is looked into: {@code subPackets()} is a plain {@code Iterable}, and one that can be
     * walked only once would be used up here. Vanilla and Polymer build bundles from lists, and Fabric API copies
     * every bundle's contents into one.
     */
    static boolean containsCandidate(ClientboundBundlePacket bundle, int minEntries) {
        if (!(bundle.subPackets() instanceof Collection<?> packets)) {
            return false;
        }
        for (Object packet : packets) {
            if (packet instanceof ClientboundRecipeBookAddPacket recipeBookAdd && recipeBookAdd.entries().size() >= minEntries) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code work} on the connection's event loop, inline if this already is that thread.
     *
     * @return true if the packet was taken over (the caller must cancel the original send), false to let vanilla send
     *         it. Never throws.
     */
    private static boolean takeOver(Connection connection, Consumer<Channel> work) {
        boolean handedOver = false;
        try {
            // For a connection that is not open, vanilla only queues the packet (a server connection never reopens),
            // so there is nothing to write chunks to.
            if (!connection.isConnected()) {
                return false;
            }
            Channel channel = ((ConnectionAccessor) connection).recipebooksplitter$getChannel();
            EventLoop loop = channel.eventLoop();
            if (loop.inEventLoop()) {
                handedOver = true; // from here on chunks may already be written, so vanilla must not send the original too
                work.accept(channel);
            } else {
                loop.execute(() -> work.accept(channel));
            }
            return true;
        } catch (RejectedExecutionException e) {
            // The event loop is shutting down; behave exactly like vanilla.
            return false;
        } catch (Throwable t) {
            LOGGER.debug("[RecipeBookSplitter] problem while taking over a packet (taken over: {})", handedOver, t);
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

    /** The connection's encoder, a way to run it on a packet, and what its pipeline can carry. */
    private record Probe(Object encoder, EntrySizer.PacketWriter writer, ConnectionLimits limits) {}

    /**
     * One entry that is bigger than {@code maxChunkBytes} on its own.
     *
     * @param index position in the original packet
     * @param packetBytes size of the one-entry packet that carries it
     * @param dropped left out of what is sent
     */
    private record EntryIssue(int index, RecipeDisplayId displayId, long packetBytes, ConnectionLimits.Verdict verdict, boolean dropped) {}

    /**
     * What to write for one recipe book packet.
     *
     * @param packets what to send in place of the original, in order: its chunks, or the original object alone if
     *                nothing changed
     * @param prepared the measured bytes of {@code packets}; empty if they were not kept
     * @param plan the chunks of the entries that are sent
     * @param sentEntries how many entries are sent, which is fewer than the packet has if some are left out
     * @param sentBytes the size the packet would have with just the entries that are sent
     * @param sentSha256 debug digest over the entries that are sent, or null if not requested
     */
    private record Split(ClientboundRecipeBookAddPacket original, List<ClientboundRecipeBookAddPacket> packets,
                         List<PreparedPacket> prepared, EntrySizer.Measurement measurement, List<ChunkPlanner.Chunk> plan,
                         List<EntryIssue> issues, int sentEntries, long sentBytes, @Nullable String sentSha256) {
        boolean unchanged() {
            return packets.size() == 1 && packets.get(0) == original;
        }
    }

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
        boolean bundled = sendAsBundle(config, channel, split.packets().size());
        if (bundled) {
            // The new bundle is the packet being re-sent, so it passes the hook; its chunks pass the encoder one by one.
            write(connection, List.of(new ClientboundBundlePacket(new ArrayList<Packet<? super ClientGamePacketListener>>(split.packets()))),
                    split.prepared(), listener, flush);
        } else {
            if (config.bundleChunks() && split.packets().size() > 1) {
                LOGGER.debug("[RecipeBookSplitter] {}: bundleChunks: sending {} chunks loose ({})", describe(connection),
                        split.packets().size(), split.packets().size() > BundlerInfo.BUNDLE_SIZE_LIMIT
                                ? "a bundle holds at most " + Sizes.bytes(BundlerInfo.BUNDLE_SIZE_LIMIT) + " packets"
                                : "the connection has no bundle unpacker");
            }
            write(connection, split.packets(), split.prepared(), listener, flush);
        }
        long writtenAt = System.nanoTime();

        try {
            report(connection, split, config, (measuredAt - start) / 1_000_000, (writtenAt - measuredAt) / 1_000_000, false, bundled);
        } catch (Throwable t) {
            LOGGER.error("[RecipeBookSplitter] {}: could not log the recipe book packet details", describe(connection), t);
        }
    }

    /**
     * Whether the chunks of a split go out in one bundle ({@code bundleChunks}): more than one of them, few enough for
     * a client to accept in a bundle, and a pipeline whose {@code unbundler} turns the bundle into packets again.
     * Without it the bundle would reach the encoder, which has no codec for it.
     */
    static boolean sendAsBundle(SplitterConfig config, Channel channel, int packets) {
        return config.bundleChunks() && packets > 1 && packets <= BundlerInfo.BUNDLE_SIZE_LIMIT
                && channel.pipeline().get(HandlerNames.UNBUNDLER) instanceof PacketBundleUnpacker;
    }

    /**
     * Runs on the event loop. Rebuilds the bundle with every recipe book sub-packet replaced, in place, by its chunks,
     * and sends that one bundle with the original listener and flush flag. Never throws; if anything fails, or the
     * rebuilt bundle would hold more sub-packets than a client accepts, the original bundle is sent.
     */
    static void splitBundleAndWrite(Connection connection, Channel channel, ClientboundBundlePacket bundle,
                                    @Nullable ChannelFutureListener listener, boolean flush) {
        if (!channel.isOpen()) {
            write(connection, List.of(bundle), List.of(), listener, flush);
            return;
        }
        long start = System.nanoTime();
        SplitterConfig config = RecipeBookSplitter.config();
        Packet<?> toSend = bundle;
        List<PreparedPacket> prepared = List.of();
        List<Split> splits = List.of();
        try {
            Probe probe = probe(connection, channel);
            if (probe != null && bundle.subPackets() instanceof Collection<?> original) {
                int minEntries = minEntries(config);
                List<Packet<? super ClientGamePacketListener>> rebuilt = new ArrayList<>(original.size() + 16);
                List<PreparedPacket> preparedAll = new ArrayList<>();
                List<Split> measured = new ArrayList<>();
                boolean changed = false;
                for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                    if (sub instanceof ClientboundRecipeBookAddPacket recipeBookAdd && recipeBookAdd.entries().size() >= minEntries) {
                        Split split = plan(recipeBookAdd, probe, config);
                        rebuilt.addAll(split.packets());
                        preparedAll.addAll(split.prepared());
                        measured.add(split);
                        changed |= !split.unchanged();
                    } else {
                        rebuilt.add(sub);
                    }
                }
                if (rebuilt.size() > BundlerInfo.BUNDLE_SIZE_LIMIT && rebuilt.size() > original.size()) {
                    LOGGER.error("[RecipeBookSplitter] {}: splitting the recipe book packet(s) in a bundle would make it {} packets, more than the {} a client accepts in one bundle; sending the bundle unsplit",
                            describe(connection), Sizes.bytes(rebuilt.size()), Sizes.bytes(BundlerInfo.BUNDLE_SIZE_LIMIT));
                } else {
                    // Nothing changed: the original bundle object, with the measured bytes of its unchanged packets.
                    toSend = changed ? new ClientboundBundlePacket(rebuilt) : bundle;
                    prepared = preparedAll;
                    splits = measured;
                }
            }
        } catch (Throwable t) {
            toSend = bundle;
            prepared = List.of();
            splits = List.of();
            LOGGER.error("[RecipeBookSplitter] {}: could not measure/split the recipe book packet in a bundle; sending the bundle unsplit",
                    describe(connection), t);
        }
        long measuredAt = System.nanoTime();

        write(connection, List.of(toSend), prepared, listener, flush);
        long writtenAt = System.nanoTime();

        try {
            // The times are those of the whole bundle.
            for (Split split : splits) {
                report(connection, split, config, (measuredAt - start) / 1_000_000, (writtenAt - measuredAt) / 1_000_000, true, false);
            }
        } catch (Throwable t) {
            LOGGER.error("[RecipeBookSplitter] {}: could not log the recipe book packet details", describe(connection), t);
        }
    }

    /** Null (logged) if the connection has no {@code PacketEncoder} to measure with. */
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
        return new Probe(encoder, (p, out) -> invoker.recipebooksplitter$encode(ctx, p, out), ConnectionLimits.detect(channel.pipeline()));
    }

    /**
     * Measures the packet, decides about entries the connection cannot send, and cuts the rest into chunks; the packet
     * itself is the only "chunk" if nothing changes.
     */
    private static Split plan(ClientboundRecipeBookAddPacket packet, Probe probe, SplitterConfig config) throws Exception {
        List<ClientboundRecipeBookAddPacket.Entry> entries = packet.entries();
        EntrySizer.Measurement measurement = EntrySizer.measure(entries, probe.writer(), debugDigest, encodeOnce);
        EncodedEntries kept = measurement.encoded();
        logNotKept(measurement.notKept());
        int fixed = measurement.fixedOverheadBytes();
        int[] sizes = measurement.entryBytes();

        List<ClientboundRecipeBookAddPacket.Entry> sent = new ArrayList<>(entries.size());
        int[] sentIndex = new int[entries.size()]; // position in the original packet of each entry that is sent
        List<EntryIssue> issues = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            ClientboundRecipeBookAddPacket.Entry entry = entries.get(i);
            long alone = ChunkPlanner.packetBytes(fixed, 1, sizes[i]);
            if (alone > config.maxChunkBytes()) {
                // Such an entry always gets a chunk of its own; it is the first chunk if nothing before it is sent.
                boolean replace = packet.replace() && sent.isEmpty();
                int index = i;
                ConnectionLimits.Verdict verdict = probe.limits().check(alone, () -> kept != null
                        ? oneEntryPacket(kept, index, replace, alone)
                        : EntrySizer.encodeProbe(probe.writer(), new ClientboundRecipeBookAddPacket(List.of(entry), replace)));
                boolean drop = verdict.certainlyUnsendable() && config.undeliverableEntries() == UndeliverableEntries.DROP;
                issues.add(new EntryIssue(i, entry.contents().id(), alone, verdict, drop));
                if (drop) {
                    continue;
                }
            }
            sentIndex[sent.size()] = i;
            sent.add(entry);
        }

        int count = sent.size();
        sentIndex = Arrays.copyOf(sentIndex, count);
        int[] sentSizes = new int[count];
        long sentSum = 0;
        for (int k = 0; k < count; k++) {
            sentSizes[k] = sizes[sentIndex[k]];
            sentSum += sentSizes[k];
        }
        List<ChunkPlanner.Chunk> plan = ChunkPlanner.plan(sentSizes, fixed, config.maxChunkBytes());
        boolean dropped = count < entries.size();
        List<ClientboundRecipeBookAddPacket> packets = !dropped && plan.size() == 1
                ? List.of(packet)
                : ChunkPlanner.split(sent, packet.replace(), plan, ClientboundRecipeBookAddPacket::new);
        String sentSha256 = !dropped ? measurement.sha256()
                : debugDigest ? EntrySizer.measure(sent, probe.writer(), true, false).sha256() : null;
        return new Split(packet, packets, prepare(packets, plan, sentIndex, kept, probe.encoder()), measurement, plan,
                issues, count, ChunkPlanner.packetBytes(fixed, count, sentSum), sentSha256);
    }

    /**
     * Encode once is on but the measured bytes cannot be kept: say why, once per reason, because the cause is another
     * mod and the only visible effect is the "reused for 0 of N packets" ending of the split lines.
     */
    static void logNotKept(EntrySizer.@Nullable NotKept reason) {
        if (reason == null || !LOGGED_NOT_KEPT.add(reason)) {
            return;
        }
        String why = switch (reason) {
            case NO_CODEC_HOOK -> "the hook around the codec call in PacketEncoder.encode did not run while measuring (for example because another mod replaced that method)";
            case OUTSIDE_CODEC -> "something besides the codec call writes into the packet buffer in PacketEncoder.encode, or the codec is called more than once (another mod's hook?)";
            case LAYOUT -> "a measured recipe book packet does not have the layout packet id, count, entries, replace flag";
        };
        LOGGER.info("[RecipeBookSplitter] encode once is not used: {}. Recipe book packets are encoded again after measuring, as in 1.0.0 (logged once)", why);
    }

    /** The bytes of a packet with just this entry, written from the kept ones. */
    private static ByteBuf oneEntryPacket(EncodedEntries kept, int index, boolean replace, long bytes) {
        ByteBuf out = Unpooled.buffer(Math.toIntExact(bytes));
        try {
            kept.writePacket(out, new int[] {index}, replace, bytes);
            return out;
        } catch (Throwable t) {
            out.release();
            throw t;
        }
    }

    /**
     * One {@link PreparedPacket} per packet, or none if the entry bytes were not kept.
     *
     * @param sentIndex for each entry that is sent, its position in the packet the bytes were measured from
     */
    private static List<PreparedPacket> prepare(List<ClientboundRecipeBookAddPacket> packets, List<ChunkPlanner.Chunk> plan,
                                                int[] sentIndex, @Nullable EncodedEntries bytes, Object encoder) {
        if (bytes == null) {
            return List.of();
        }
        List<PreparedPacket> prepared = new ArrayList<>(packets.size());
        for (int i = 0; i < packets.size(); i++) {
            ChunkPlanner.Chunk chunk = plan.get(i);
            prepared.add(new PreparedPacket(packets.get(i), encoder, bytes, Arrays.copyOfRange(sentIndex, chunk.start(), chunk.end()), chunk.bytes()));
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

    /**
     * @param inBundle the packet was a sub-packet of a bundle
     * @param bundled the chunks were sent in one bundle of their own ({@code bundleChunks})
     */
    private static void report(Connection connection, Split split, SplitterConfig config, long measureMs, long writeMs,
                               boolean inBundle, boolean bundled) {
        ClientboundRecipeBookAddPacket packet = split.original();
        EntrySizer.Measurement measurement = split.measurement();
        List<ChunkPlanner.Chunk> plan = split.plan();
        String player = describe(connection);
        long total = measurement.totalBytes();
        int entries = packet.entries().size();
        String encodeOnceNote = encodeOnceNote(split);
        String where = inBundle ? " in a bundle" : "";

        if (plan.size() > 1) {
            if (config.logSplits()) {
                long largest = plan.stream().mapToLong(ChunkPlanner.Chunk::bytes).max().orElse(0);
                LOGGER.info("[RecipeBookSplitter] {}: split {} recipe book packet{} ({} bytes, {} entries, replace={}) into {} chunks{} (largest {} bytes, limit {} bytes, {} ms, written in {} ms){}",
                        player, Sizes.mib(total), where, Sizes.bytes(total), Sizes.bytes(entries), packet.replace(), plan.size(),
                        bundled ? " in one bundle" : "", Sizes.bytes(largest), Sizes.bytes(config.maxChunkBytes()), measureMs, writeMs, encodeOnceNote);
            }
        } else if (split.issues().isEmpty()) {
            LOGGER.debug("[RecipeBookSplitter] {}: recipe book packet{} {} bytes ({} entries) is within the limit; sent unsplit{}",
                    player, where, Sizes.bytes(total), entries, encodeOnceNote);
        }

        for (EntryIssue issue : split.issues()) {
            String recipe = recipeName(connection, issue.displayId());
            ConnectionLimits.Verdict verdict = issue.verdict();
            if (issue.dropped()) {
                LOGGER.error("[RecipeBookSplitter] {}: left out recipe display entry #{} (display id {}, recipe {}): it is {} bytes on its own and this connection cannot send it ({}). The player stays connected, but their recipe book does not show this recipe. Make the recipe smaller, or set \"undeliverableEntries\": \"send\" to send it anyway (the player is then disconnected)",
                        player, issue.index(), issue.displayId().index(), recipe, Sizes.bytes(issue.packetBytes()), verdict.reason());
            } else if (verdict.certainlyUnsendable()) {
                LOGGER.error("[RecipeBookSplitter] {}: recipe display entry #{} (display id {}, recipe {}) is {} bytes on its own and this connection cannot send it ({}); sending it anyway because undeliverableEntries is \"send\", so the player will be disconnected",
                        player, issue.index(), issue.displayId().index(), recipe, Sizes.bytes(issue.packetBytes()), verdict.reason());
            } else {
                String note = verdict.sendable() == null && verdict.reason() != null ? "; " + verdict.reason()
                        : issue.packetBytes() <= ConnectionLimits.FRAME_LIMIT_BYTES ? ""
                        : verdict.sendable() == null ? "; this connection's compression or framing is not vanilla's, so whether it can send more than 2,097,151 bytes is unknown"
                        : "; network compression lets this connection send it, but a proxy or client that receives it uncompressed cannot";
                LOGGER.warn("[RecipeBookSplitter] {}: recipe display entry #{} (display id {}, recipe {}) is {} bytes on its own, more than maxChunkBytes ({}); sending it in a chunk by itself{}",
                        player, issue.index(), issue.displayId().index(), recipe, Sizes.bytes(issue.packetBytes()),
                        Sizes.bytes(config.maxChunkBytes()), note);
            }
        }

        if (split.sentSha256() != null) {
            int dropped = entries - split.sentEntries();
            // Plain digits, no grouping: e2e/check.py parses this line.
            LOGGER.info("[RecipeBookSplitter] digest player={} entries={} replace={} bytes={} chunks={} sha256={}{}{}",
                    player, split.sentEntries(), packet.replace(), split.sentBytes(), plan.size(), split.sentSha256(),
                    dropped > 0 ? " dropped=" + dropped : "", inBundle || bundled ? " bundle=true" : "");
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

    /** The recipe a display belongs to, for log lines; "unknown" if it cannot be looked up from this thread. */
    private static String recipeName(Connection connection, RecipeDisplayId displayId) {
        try {
            if (connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
                RecipeManager.ServerDisplayInfo info = listener.player.level().getServer().getRecipeManager().getRecipeFromDisplay(displayId);
                if (info != null) {
                    return info.parent().id().identifier().toString();
                }
            }
        } catch (RuntimeException ignored) {
            // Best effort: the recipe list is replaced by /reload, and this runs on the event loop.
        }
        return "unknown";
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
