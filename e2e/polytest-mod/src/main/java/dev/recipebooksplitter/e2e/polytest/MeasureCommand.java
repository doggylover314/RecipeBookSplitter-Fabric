package dev.recipebooksplitter.e2e.polytest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * {@code /polytest measure <player> [send]}: debug command that sizes the recipe book of a player in two ways, without
 * going through the connection.
 *
 * <ul>
 *   <li><b>bare</b>: {@code Entry.STREAM_CODEC.encode} on a fresh {@link RegistryFriendlyByteBuf}, on the server thread,
 *       outside any packet-tweaker {@link PacketContext}. This is what a size estimate that just calls the packet
 *       codec would see.</li>
 *   <li><b>ctx</b>: the same encode inside {@link PacketContext#supplyWithContext}, with the player's connection and
 *       the packet, which is the context packet-tweaker's {@code PacketEncoder} hook sets for a real send.</li>
 * </ul>
 *
 * It logs one {@code [polytest] measure ...} line with both totals and SHA-256 digests over the entry bytes (the digest
 * the Recipe Book Splitter logs with {@code -Drecipebooksplitter.debugDigest=true} is computed the same way), and writes
 * the per-entry sizes to {@code polytest-measure-<n>.json} in the server directory. With {@code send}, the same entry
 * list is then sent to the player as one {@code replace=true} packet through the normal send path, so the mod's digest
 * line and the client's recording describe exactly the entries measured here.
 */
final class MeasureCommand {
    private static final AtomicInteger RUNS = new AtomicInteger();

    private MeasureCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("polytest")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("measure")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(c -> run(c, false))
                                .then(Commands.literal("send").executes(c -> run(c, true))))));
    }

    private static int run(CommandContext<CommandSourceStack> context, boolean send) {
        try {
            ServerPlayer player = EntityArgument.getPlayer(context, "player");
            measure(context.getSource().getServer(), player, send);
            return 1;
        } catch (Throwable t) {
            PolyTestMod.LOGGER.error("[polytest] measure failed", t);
            return 0;
        }
    }

    private static void measure(MinecraftServer server, ServerPlayer player, boolean send) throws Exception {
        RecipeManager recipes = server.getRecipeManager();
        RegistryAccess access = server.registryAccess();

        // The entries sendInitialRecipeBook would send: every known recipe, each with its displays.
        List<ClientboundRecipeBookAddPacket.Entry> entries = new ArrayList<>();
        List<String> recipeIds = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes.getRecipes()) {
            if (!player.getRecipeBook().contains(holder.id())) {
                continue;
            }
            String id = holder.id().identifier().toString();
            recipes.listDisplaysForRecipe(holder.id(), display -> {
                entries.add(new ClientboundRecipeBookAddPacket.Entry(display, false, false));
                recipeIds.add(id);
            });
        }
        ClientboundRecipeBookAddPacket packet = new ClientboundRecipeBookAddPacket(entries, true);

        long t0 = System.nanoTime();
        Sizes bare = encodeEntries(entries, access);
        long t1 = System.nanoTime();
        Sizes ctx = PacketContext.supplyWithContext(player.connection, packet, () -> encodeEntries(entries, access));
        long t2 = System.nanoTime();

        // The whole packet through its own codec, bare: must be id-less payload = count VarInt + entries + replace flag.
        ByteBuf whole = Unpooled.buffer();
        ClientboundRecipeBookAddPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(whole, access), packet);
        int barePacketPayload = whole.readableBytes();
        whole.release();

        PolyTestMod.LOGGER.info("[polytest] measure player={} entries={} bare_entry_bytes={} bare_sha256={} ctx_entry_bytes={} ctx_sha256={} bare_packet_payload={} identical={} bare_ms={} ctx_ms={} send={}",
                player.getGameProfile().name(), entries.size(), bare.total, bare.sha256, ctx.total, ctx.sha256, barePacketPayload,
                bare.sha256.equals(ctx.sha256), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, send);

        // Up to three of the smallest entries whose bare and in-context encodings differ, as base64, for a closer look.
        List<Integer> differing = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (bare.perEntry[i] != ctx.perEntry[i]) {
                differing.add(i);
            }
        }
        differing.sort((a, b) -> Integer.compare(ctx.perEntry[a], ctx.perEntry[b]));
        StringBuilder diffs = new StringBuilder();
        for (int k = 0; k < Math.min(3, differing.size()); k++) {
            int i = differing.get(k);
            ClientboundRecipeBookAddPacket.Entry entry = entries.get(i);
            byte[] bareBytes = encodeOne(entry, access);
            byte[] ctxBytes = PacketContext.supplyWithContext(player.connection, packet, () -> encodeOne(entry, access));
            diffs.append(k > 0 ? "," : "").append("{\"id\":\"").append(recipeIds.get(i)).append("\",\"bare\":\"")
                    .append(java.util.Base64.getEncoder().encodeToString(bareBytes)).append("\",\"ctx\":\"")
                    .append(java.util.Base64.getEncoder().encodeToString(ctxBytes)).append("\"}");
        }

        writeJson(Path.of("polytest-measure-" + RUNS.incrementAndGet() + ".json"), player, recipeIds, bare, ctx, barePacketPayload, diffs.toString());

        if (send) {
            player.connection.send(packet);
        }
    }

    private record Sizes(int[] perEntry, long total, String sha256) {}

    private static byte[] encodeOne(ClientboundRecipeBookAddPacket.Entry entry, RegistryAccess access) {
        ByteBuf scratch = Unpooled.buffer(4096);
        try {
            ClientboundRecipeBookAddPacket.Entry.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(scratch, access), entry);
            byte[] bytes = new byte[scratch.readableBytes()];
            scratch.readBytes(bytes);
            return bytes;
        } finally {
            scratch.release();
        }
    }

    private static Sizes encodeEntries(List<ClientboundRecipeBookAddPacket.Entry> entries, RegistryAccess access) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int[] sizes = new int[entries.size()];
        long total = 0;
        ByteBuf scratch = Unpooled.buffer(4096);
        try {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(scratch, access);
            for (int i = 0; i < sizes.length; i++) {
                scratch.clear();
                ClientboundRecipeBookAddPacket.Entry.STREAM_CODEC.encode(buf, entries.get(i));
                sizes[i] = scratch.readableBytes();
                total += sizes[i];
                digest.update(scratch.nioBuffer(0, sizes[i]));
            }
        } finally {
            scratch.release();
        }
        return new Sizes(sizes, total, HexFormat.of().formatHex(digest.digest()));
    }

    private static void writeJson(Path path, ServerPlayer player, List<String> recipeIds, Sizes bare, Sizes ctx, int barePacketPayload, String diffs) throws IOException {
        Map<String, Integer> namespaces = new LinkedHashMap<>();
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < recipeIds.size(); i++) {
            String id = recipeIds.get(i);
            int ns = namespaces.computeIfAbsent(id.substring(0, id.indexOf(':')), k -> namespaces.size());
            if (i > 0) {
                entries.append(',');
            }
            entries.append("[").append(ns).append(",\"").append(id).append("\",").append(bare.perEntry[i]).append(',').append(ctx.perEntry[i]).append(']');
        }
        StringBuilder ns = new StringBuilder();
        for (String name : namespaces.keySet()) {
            ns.append(ns.length() == 0 ? "" : ",").append('"').append(name).append('"');
        }
        String json = "{\"player\":\"" + player.getGameProfile().name() + "\",\"entries\":" + recipeIds.size()
                + ",\"bare_total\":" + bare.total + ",\"ctx_total\":" + ctx.total
                + ",\"bare_sha256\":\"" + bare.sha256 + "\",\"ctx_sha256\":\"" + ctx.sha256 + "\""
                + ",\"bare_packet_payload\":" + barePacketPayload
                + ",\"diffs\":[" + diffs + "]"
                + ",\"namespaces\":[" + ns + "],\"rows\":[" + entries + "]}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }
}
