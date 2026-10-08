package dev.recipebooksplitter.e2e.polytest;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import xyz.nucleoid.packettweaker.PacketContext;

/**
 * A server-side Polymer item that the vanilla client sees as {@code base}. Polymer builds that client-side stack at
 * encode time (item name, custom_data with the server item id, use_cooldown, ...).
 *
 * <p>If {@code playerBound} is set the client-side stack also depends on who it is encoded for: it gets two more lore
 * lines when the {@link PacketContext} has a player. That stands for the per-player content real Polymer mods produce
 * (owner names, per-player counters, language dependent text) and lets the test tell an encode that runs inside the
 * connection's packet context from one that runs outside it.
 */
public final class PolyItem extends SimplePolymerItem {
    private final boolean playerBound;

    public PolyItem(Item.Properties properties, Item base, boolean useItemModel, boolean playerBound) {
        super(properties, base, useItemModel);
        this.playerBound = playerBound;
    }

    @Override
    public void modifyClientTooltip(List<Component> tooltip, ItemStack stack, PacketContext context) {
        if (this.playerBound && context.getPlayer() != null) {
            tooltip.add(Component.literal("Bound to " + context.getPlayer().getGameProfile().name()));
            tooltip.add(Component.literal("Soulbound, cannot be dropped"));
        }
    }
}
