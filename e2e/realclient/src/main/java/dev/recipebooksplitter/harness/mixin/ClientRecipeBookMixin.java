package dev.recipebooksplitter.harness.mixin;

import java.util.Map;
import java.util.Set;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor interface for the private recipe maps of the client recipe book (cast the book to this interface). */
@Mixin(ClientRecipeBook.class)
public interface ClientRecipeBookMixin {
    @Accessor("known")
    Map<RecipeDisplayId, RecipeDisplayEntry> rbsh$known();

    @Accessor("highlight")
    Set<RecipeDisplayId> rbsh$highlight();
}
