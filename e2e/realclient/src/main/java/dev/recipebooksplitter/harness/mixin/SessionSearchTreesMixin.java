package dev.recipebooksplitter.harness.mixin;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.client.searchtree.SearchTree;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor interface for the recipe search future (cast {@code SessionSearchTrees} to this interface). */
@Mixin(SessionSearchTrees.class)
public interface SessionSearchTreesMixin {
    @Accessor("recipeSearch")
    CompletableFuture<SearchTree<RecipeCollection>> rbsh$recipeSearch();
}
