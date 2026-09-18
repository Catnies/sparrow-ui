package net.momirealms.sparrow.ui.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NetworkTransportConcurrencyTest {
    @Test
    @SuppressWarnings("deprecation")
    void concurrentCallersDispatchAllPacketsOnTheEventLoop() throws Exception {
        MockBukkit.mock();
        NetworkManager manager = NetworkManagerTestSupport.openManager();
        DefaultEventLoopGroup loops = new DefaultEventLoopGroup(2);
        Channel listener = null;
        Channel client = null;
        Channel connection = null;
        int sends = 80;
        CountDownLatch received = new CountDownLatch(sends);
        CountDownLatch injected = new CountDownLatch(sends);
        AtomicInteger outboundNms = new AtomicInteger();
        AtomicInteger outboundBytes = new AtomicInteger();
        AtomicInteger inboundNms = new AtomicInteger();
        AtomicInteger inboundBytes = new AtomicInteger();
        AtomicInteger wrongThread = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CompletableFuture<NetworkUser> ready = new CompletableFuture<>();
        PacketType outbound = PacketTypes.Play.Clientbound.MERCHANT_OFFERS;
        PacketType inbound = PacketTypes.Play.Serverbound.RENAME_ITEM;
        manager.listenNMS(outbound, (user, event, packet) -> {
            if (!user.channel().eventLoop().inEventLoop()) wrongThread.incrementAndGet();
            outboundNms.incrementAndGet();
        });
        manager.listenByteBuf(outbound, (user, event) -> outboundBytes.incrementAndGet());
        manager.listenNMS(inbound, (user, event, packet) -> inboundNms.incrementAndGet());
        manager.listenByteBuf(inbound, (user, event) -> inboundBytes.incrementAndGet());
        try {
            listener = new ServerBootstrap().group(loops).channel(LocalServerChannel.class)
                    .childHandler(new ChannelInitializer<LocalChannel>() {
                        @Override
                        protected void initChannel(LocalChannel channel) {
                            channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
                            channel.pipeline().addLast("decoder", new NetworkTransportTest.Decoder(manager));
                            channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
                            channel.pipeline().addLast("encoder", new NetworkTransportTest.Encoder());
                            channel.pipeline().addLast("unbundler", new NetworkTransportTest.Unbundler());
                            channel.pipeline().addLast("packet_handler", new net.minecraft.network.Connection());
                            channel.pipeline().addLast("consume", new SimpleChannelInboundHandler<NetworkTransportTest.WirePacket>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext context, NetworkTransportTest.WirePacket packet) {
                                    if (!context.executor().inEventLoop()) wrongThread.incrementAndGet();
                                    injected.countDown();
                                }
                            });
                            NetworkUser user = manager.injectConnectionChannel(channel);
                            user.setConnectionState(ConnectionState.PLAY);
                            channel.pipeline().addLast("write-result", new ChannelOutboundHandlerAdapter() {
                                @Override
                                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                                    promise.addListener(result -> {
                                        if (!result.isSuccess()) errors.add(result.cause());
                                    });
                                    context.write(message, promise);
                                }
                            });
                            ready.complete(user);
                        }
                    }).bind(new LocalAddress("sparrow-context-" + System.nanoTime())).sync().channel();
            client = new Bootstrap().group(loops).channel(LocalChannel.class)
                    .handler(new SimpleChannelInboundHandler<ByteBuf>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
                            // LocalChannel 可合并相邻 ByteBuf, 按协议包计数而非按 channelRead 次数计数.
                            while (message.isReadable()) {
                                assertEquals(manager.packetIds().id(outbound), PacketBuf.readVarInt(message));
                                assertEquals(7, PacketBuf.readVarInt(message));
                                received.countDown();
                            }
                        }
                    }).connect(listener.localAddress()).sync().channel();
            NetworkUser user = ready.get(5, TimeUnit.SECONDS);
            connection = user.channel();
            Object nativeType = manager.packetIds().nativeType(outbound);
            int id = manager.packetIds().id(outbound);
            List<Callable<Void>> calls = new ArrayList<>();
            for (int index = 0; index < sends; index++) {
                calls.add(() -> {
                    NetworkTransportTest.WirePacket packet = new NetworkTransportTest.WirePacket(nativeType, id, 7);
                    user.sendPacket(packet);
                    user.receivePacket(inbound, buffer -> buffer.writeVarInt(7));
                    return null;
                });
            }
            try (var callers = Executors.newFixedThreadPool(4)) {
                for (var result : callers.invokeAll(calls)) {
                    result.get();
                }
            }
            assertTrue(received.await(5, TimeUnit.SECONDS), () -> "outstanding=" + received.getCount() + " nms=" + outboundNms + " bytes=" + outboundBytes + " errors=" + errors);
            assertTrue(injected.await(5, TimeUnit.SECONDS), () -> "outstanding=" + injected.getCount() + " nms=" + inboundNms + " bytes=" + inboundBytes + " errors=" + errors);
            assertEquals(sends, outboundNms.get());
            assertEquals(sends, outboundBytes.get());
            assertEquals(sends, inboundNms.get());
            assertEquals(sends, inboundBytes.get());
            assertEquals(0, wrongThread.get());
        } finally {
            if (connection != null) connection.close().syncUninterruptibly();
            if (client != null) client.close().syncUninterruptibly();
            if (listener != null) listener.close().syncUninterruptibly();
            NetworkManagerTestSupport.closeManager(manager);
            loops.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            MockBukkit.unmock();
        }
    }
}
