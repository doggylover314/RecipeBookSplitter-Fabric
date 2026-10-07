package dev.recipebooksplitter.split;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.recipebooksplitter.testutil.RecipeFixtures;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.network.Connection;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

/**
 * A server-side {@link Connection} on a real (not embedded) in-memory channel with its own event loop thread, so that
 * sends from the test thread go through the event loop like on a server. The peer just discards what it receives. With
 * an encoder the pipeline also has the bundle unpacker, as vanilla's does.
 */
final class LocalConnection implements AutoCloseable {
    final Connection connection = new Connection(PacketFlow.SERVERBOUND);
    final WriteRecorder recorder = new WriteRecorder();
    /** How many packets the encoder was asked to encode, including the mod's measuring probes. */
    final AtomicInteger encodes = new AtomicInteger();
    /** The server side of the in-memory channel. */
    volatile Channel child;
    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
    private final Channel client;

    LocalConnection(boolean withEncoder) throws Exception {
        LocalAddress address = new LocalAddress("recipebooksplitter-" + System.nanoTime());
        new ServerBootstrap()
                .group(group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel child) {
                        LocalConnection.this.child = child;
                        if (withEncoder) {
                            child.pipeline().addLast(HandlerNames.ENCODER, new PacketEncoder<ClientGamePacketListener>(RecipeFixtures.protocol()) {
                                @Override
                                protected void encode(ChannelHandlerContext ctx, Packet<ClientGamePacketListener> packet, ByteBuf out) throws Exception {
                                    encodes.incrementAndGet();
                                    super.encode(ctx, packet, out);
                                }
                            });
                            child.pipeline().addLast(HandlerNames.UNBUNDLER, new PacketBundleUnpacker(RecipeFixtures.protocol().bundlerInfo()));
                        }
                        child.pipeline().addLast("recorder", recorder);
                        child.pipeline().addLast(HandlerNames.PACKET_HANDLER, connection);
                    }
                })
                .bind(address).sync();
        client = new Bootstrap()
                .group(group)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ReferenceCountUtil.release(msg);
                    }
                })
                .connect(address).sync().channel();
        awaitUntil(connection::isConnected, "connection did not become active");
    }

    static void awaitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, failureMessage);
            Thread.sleep(5);
        }
    }

    @Override
    public void close() throws Exception {
        client.close().sync();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
    }
}
