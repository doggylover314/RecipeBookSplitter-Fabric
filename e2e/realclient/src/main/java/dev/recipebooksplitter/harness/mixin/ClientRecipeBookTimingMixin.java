package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import net.minecraft.client.ClientRecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times {@code ClientRecipeBook.rebuildCollections()}. */
@Mixin(ClientRecipeBook.class)
public abstract class ClientRecipeBookTimingMixin {
    @Inject(method = "rebuildCollections", at = @At("HEAD"))
    private void rbsh$rebuildStart(CallbackInfo ci) {
        Harness.onRebuildStart();
    }

    @Inject(method = "rebuildCollections", at = @At("RETURN"))
    private void rbsh$rebuildEnd(CallbackInfo ci) {
        Harness.onRebuildEnd();
    }
}
