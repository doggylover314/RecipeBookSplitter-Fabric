package dev.recipebooksplitter.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ConnectionAccessor {
    /** {@code Connection} has no public getter for its Netty channel. */
    @Accessor("channel")
    Channel recipebooksplitter$getChannel();
}
