package dev.recipebooksplitter.e2e.polytest;

import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers {@code polytest.items} (default 400) server-side Polymer items {@code polytest:p000}, {@code p001}, ...
 * and the {@code /polytest measure} debug command. Tags and recipes are data: the jar ships a small data pack, and
 * {@code e2e/gen_polytest_pack.py} generates large ones.
 *
 * <p>System properties: {@code polytest.items} (number of items, default 400), {@code polytest.bound} (every n-th
 * item, counting from item 0, is player-bound, see {@link PolyItem}; 0 = none, the default).
 */
public final class PolyTestMod implements ModInitializer {
    public static final String ID = "polytest";
    public static final Logger LOGGER = LoggerFactory.getLogger("polytest");

    /** The vanilla items the Polymer items are shown as on the client. */
    private static final List<Item> BASES = List.of(Items.PAPER, Items.STICK, Items.AMETHYST_SHARD, Items.IRON_NUGGET,
            Items.ECHO_SHARD, Items.GOLD_NUGGET, Items.FEATHER, Items.FLINT, Items.CLAY_BALL, Items.BRICK);

    @Override
    public void onInitialize() {
        int count = Integer.getInteger("polytest.items", 400);
        int bound = Integer.getInteger("polytest.bound", 0);
        for (int i = 0; i < count; i++) {
            register(i, bound > 0 && i % bound == 0);
        }
        LOGGER.info("[polytest] registered {} Polymer items polytest:p000.. (player-bound: every {}th, 0 = none)", count, bound);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> MeasureCommand.register(dispatcher));
    }

    static String name(int index) {
        return String.format("p%03d", index);
    }

    private static void register(int index, boolean playerBound) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(ID, name(index)));
        // The item name is not set here: Item's constructor overwrites ITEM_NAME with the translatable description id
        // (Item.Properties.buildAndValidateComponents), so the client-side stacks carry "translate: item.polytest.pNNN".
        Item.Properties properties = new Item.Properties().setId(key);
        if (index % 2 == 0) {
            properties.component(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("A server-side item that the client sees as " + BASES.get(index % BASES.size()).getDescriptionId()),
                    Component.literal("Registered by the Recipe Book Splitter e2e kit"))));
        }
        boolean customModel = index % 3 == 0;
        if (customModel) {
            properties.component(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(ID, "model/" + name(index)));
        }
        Registry.register(BuiltInRegistries.ITEM, key, new PolyItem(properties, BASES.get(index % BASES.size()), customModel, playerBound));
    }
}
