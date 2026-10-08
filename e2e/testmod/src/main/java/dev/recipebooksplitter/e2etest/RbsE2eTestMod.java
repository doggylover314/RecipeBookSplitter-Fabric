package dev.recipebooksplitter.e2etest;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test only. Sends synthetic recipe book packets that vanilla never sends, through the normal send path
 * ({@code ServerGamePacketListenerImpl.send}, so Polymer's packet patching runs as for any packet):
 *
 * <ul>
 *   <li>{@code /rbstest bundle <player> <count> <padBytes>}: a bundle [remove(1000000), add(count entries, replace=false),
 *       remove(1000001)];</li>
 *   <li>{@code /rbstest huge <player> <bytes> <random>}: add [small 2000000, huge 2000001, small 2000002], replace=false;</li>
 *   <li>{@code /rbstest bundlehuge <player> <bytes> <random>}: a bundle [remove(3000000), add [small 3000001, huge 3000002],
 *       remove(3000003)].</li>
 * </ul>
 * The display ids do not exist on the server; a client just stores them.
 */
public class RbsE2eTestMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("rbs-e2e-testmod");

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("rbstest").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("bundle").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("padBytes", IntegerArgumentType.integer(0))
                                                .executes(RbsE2eTestMod::bundle)))))
                        .then(Commands.literal("huge").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("bytes", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("random", BoolArgumentType.bool())
                                                .executes(ctx -> huge(ctx, false))))))
                        .then(Commands.literal("bundlehuge").then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("bytes", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("random", BoolArgumentType.bool())
                                                .executes(ctx -> huge(ctx, true))))))));
    }

    private static int bundle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int pad = IntegerArgumentType.getInteger(ctx, "padBytes");
        List<ClientboundRecipeBookAddPacket.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(entry(1_000_100 + i, pad, true, (byte) (i % 4)));
        }
        List<Packet<? super ClientGamePacketListener>> subs = List.of(
                new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(1_000_000))),
                new ClientboundRecipeBookAddPacket(entries, false),
                new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(1_000_001))));
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(subs);
        player.connection.send(bundle);
        LOGGER.info("[rbs-testmod] sent bundle to {}: remove, add ({} entries, {} pad bytes each), remove", player.getPlainTextName(), count, pad);
        return 1;
    }

    private static int huge(CommandContext<CommandSourceStack> ctx, boolean inBundle) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        int bytes = IntegerArgumentType.getInteger(ctx, "bytes");
        boolean random = BoolArgumentType.getBool(ctx, "random");
        int base = inBundle ? 3_000_000 : 2_000_000;
        if (inBundle) {
            List<Packet<? super ClientGamePacketListener>> subs = List.of(
                    new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(base))),
                    new ClientboundRecipeBookAddPacket(List.of(entry(base + 1, 100, false, (byte) 0), entry(base + 2, bytes, random, (byte) 0)), false),
                    new ClientboundRecipeBookRemovePacket(List.of(new RecipeDisplayId(base + 3))));
            player.connection.send(new ClientboundBundlePacket(subs));
        } else {
            player.connection.send(new ClientboundRecipeBookAddPacket(List.of(
                    entry(base, 100, false, (byte) 0), entry(base + 1, bytes, random, (byte) 0), entry(base + 2, 100, false, (byte) 0)), false));
        }
        LOGGER.info("[rbs-testmod] sent {} with a {}-byte {} entry to {}", inBundle ? "bundle" : "recipe book packet", bytes,
                random ? "random" : "zero", player.getPlainTextName());
        return 1;
    }

    private static ClientboundRecipeBookAddPacket.Entry entry(int displayId, int padBytes, boolean random, byte flags) {
        byte[] pad = new byte[padBytes];
        if (random) {
            new Random(displayId).nextBytes(pad);
        }
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("pad", pad);
        ItemStack result = new ItemStack(Items.PAPER);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        var display = new ShapelessCraftingRecipeDisplay(
                List.of(new SlotDisplay.ItemSlotDisplay(Items.STICK), new SlotDisplay.ItemSlotDisplay(Items.DIAMOND)),
                new SlotDisplay.ItemStackSlotDisplay(result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        return new ClientboundRecipeBookAddPacket.Entry(new RecipeDisplayEntry(new RecipeDisplayId(displayId), display,
                OptionalInt.empty(), RecipeBookCategories.CRAFTING_MISC, Optional.empty()), flags);
    }
}
