package dev.recipebooksplitter.testutil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/**
 * Mirrors what {@code ClientPacketListener.handleRecipeBookAdd} does to the client's recipe book: clear on replace,
 * then add every entry, highlight it if flagged, and queue a toast if flagged.
 */
public final class FakeClientRecipeBook {
    /** Recipe display id to a stable rendering of its contents. */
    public final Map<Integer, String> known = new HashMap<>();
    public final Set<Integer> highlight = new HashSet<>();
    public final List<Integer> toasts = new ArrayList<>();

    public void apply(ClientboundRecipeBookAddPacket packet, Function<RecipeDisplayEntry, String> render) {
        if (packet.replace()) {
            known.clear();
            highlight.clear();
        }
        for (ClientboundRecipeBookAddPacket.Entry entry : packet.entries()) {
            int id = entry.contents().id().index();
            known.put(id, render.apply(entry.contents()));
            if (entry.highlight()) {
                highlight.add(id);
            }
            if (entry.notification()) {
                toasts.add(id);
            }
        }
    }

    /** Seeds entries that a later replace=true packet must remove, and that replace=false must leave alone. */
    public void seedHighlighted(int firstId, int count) {
        for (int id = firstId; id < firstId + count; id++) {
            known.put(id, "stale");
            highlight.add(id);
        }
    }
}
