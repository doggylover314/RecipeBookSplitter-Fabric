package dev.recipebooksplitter.harness.mixin;

import dev.recipebooksplitter.harness.Harness;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the cause chain of every exception the client's connection handles (decode errors and the like). */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    private void rbsh$exception(ChannelHandlerContext ctx, Throwable throwable, CallbackInfo ci) {
        Harness.onNetworkException(throwable);
    }
}
