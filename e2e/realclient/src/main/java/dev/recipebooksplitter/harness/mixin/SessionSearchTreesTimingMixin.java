package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.client.searchtree.SearchTree;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Timing for {@code SessionSearchTrees.updateRecipes} (render thread, cheap: it only schedules the build) and for the
 * background build itself.
 *
 * <p>{@code method_60361} is the synthetic lambda body of the supplier that {@code updateRecipes} hands to
 * {@code CompletableFuture.supplyAsync}: it constructs the {@code FullTextSearchTree}. The name is the intermediary
 * name Loom uses for lambdas of the 1.21.11 jar (see the javap output in the report), so it is only valid for 1.21.11.
 */
@Mixin(SessionSearchTrees.class)
public abstract class SessionSearchTreesTimingMixin {
    @Inject(method = "updateRecipes", at = @At("HEAD"))
    private void rbsh$updateStart(ClientRecipeBook book, Level level, CallbackInfo ci) {
        Harness.onSearchUpdateStart();
    }

    @Inject(method = "updateRecipes", at = @At("RETURN"))
    private void rbsh$updateEnd(ClientRecipeBook book, Level level, CallbackInfo ci) {
        Harness.onSearchUpdateEnd();
    }

    @Inject(method = "method_60361", at = @At("HEAD"))
    private static void rbsh$bgStart(CallbackInfoReturnable<SearchTree<?>> cir) {
        Harness.onBgBuildStart();
    }

    @Inject(method = "method_60361", at = @At("RETURN"))
    private static void rbsh$bgEnd(CallbackInfoReturnable<SearchTree<?>> cir) {
        Harness.onBgBuildEnd();
    }
}
