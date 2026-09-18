package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NetworkTransportTest {
    private static final PacketType OUTBOUND = PacketTypes.Play.Clientbound.MERCHANT_OFFERS;
    private static final PacketType INBOUND = PacketTypes.Play.Serverbound.RENAME_ITEM;

    private NetworkManager manager;
    private EmbeddedChannel channel;
    private NetworkUser user;
    private final AtomicInteger outboundNms = new AtomicInteger();
    private final AtomicInteger outboundBytes = new AtomicInteger();
    private final AtomicInteger inboundNms = new AtomicInteger();
    private final AtomicInteger inboundBytes = new AtomicInteger();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
        this.channel = NetworkManagerTestSupport.openChannel();
        this.channel.pipeline().replace("encoder", "encoder", new Encoder());
        this.channel.pipeline().replace("decoder", "decoder", new Decoder(this.manager));
        this.channel.pipeline().addAfter("encoder", "unbundler", new Unbundler());
        this.user = this.manager.injectConnectionChannel(this.channel);
        this.user.setConnectionState(ConnectionState.PLAY);
        this.manager.listenNMS(OUTBOUND, (u, e, p) -> this.outboundNms.incrementAndGet());
        this.manager.listenByteBuf(OUTBOUND, (u, e) -> this.outboundBytes.incrementAndGet());
        this.manager.listenNMS(INBOUND, (u, e, p) -> this.inboundNms.incrementAndGet());
        this.manager.listenByteBuf(INBOUND, (u, e) -> this.inboundBytes.incrementAndGet());
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
        NetworkManagerTestSupport.closeManager(this.manager);
        MockBukkit.unmock();
    }

    private WirePacket packet(PacketType type, int value) {
        return new WirePacket(this.manager.packetIds().nativeType(type), this.manager.packetIds().id(type), value);
    }

    private ByteBuf frame(PacketType type, int value) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(this.manager.packetIds().id(type));
        buffer.writeVarInt(value);
        return buffer.source();
    }

    private void assertOutbound(int value) {
        ByteBuf frame = this.channel.readOutbound();
        assertNotNull(frame);
        try {
            assertEquals(this.manager.packetIds().id(OUTBOUND), PacketBuf.readVarInt(frame));
            assertEquals(value, PacketBuf.readVarInt(frame));
            assertFalse(frame.isReadable());
        } finally {
            frame.release();
        }
    }

    private void assertInbound(int value) {
        WirePacket packet = this.channel.readInbound();
        assertNotNull(packet);
        assertEquals(value, packet.value);
        packet.release();
    }

    @Test
    void sendAndReceiveEndpointsVisitTheExpectedLayers() {
        this.user.sendPacket(this.packet(OUTBOUND, 1));
        this.user.sendPacket(this.packet(OUTBOUND, 2));
        this.user.sendByteBuf(this.frame(OUTBOUND, 3));
        this.user.sendByteBuf(this.frame(OUTBOUND, 4));
        for (int value = 1; value <= 4; value++) {
            this.assertOutbound(value);
        }
        assertEquals(2, this.outboundNms.get());
        assertEquals(4, this.outboundBytes.get());

        this.user.receivePacket(this.packet(INBOUND, 1));
        this.user.receivePacket(this.packet(INBOUND, 2));
        this.user.receiveByteBuf(this.frame(INBOUND, 3));
        this.user.receiveByteBuf(this.frame(INBOUND, 4));
        for (int value = 1; value <= 4; value++) {
            this.assertInbound(value);
        }
        assertEquals(4, this.inboundNms.get());
        assertEquals(2, this.inboundBytes.get());
    }

    @Test
    void delayedWritesPreserveRepeatedPacketOwnership() {
        DelayedWrites delayed = new DelayedWrites();
        this.channel.pipeline().addAfter("encoder", "delay", delayed);
        WirePacket packet = this.packet(OUTBOUND, 7);
        packet.retain();
        this.user.sendPacket(packet);
        this.user.sendPacket(packet);
        assertNull(this.channel.readOutbound());
        delayed.forward(1);
        delayed.forward(0);
        this.assertOutbound(7);
        this.assertOutbound(7);
        assertEquals(0, packet.refCnt());
        assertEquals(2, this.outboundNms.get());
        assertEquals(2, this.outboundBytes.get());
    }

    @Test
    void delayedDecodedPacketsReachListeners() {
        List<ChannelHandlerContext> contexts = new ArrayList<>();
        List<Object> messages = new ArrayList<>();
        this.channel.pipeline().addAfter("decoder", "delay", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                contexts.add(context);
                messages.add(message);
            }
        });
        this.user.receiveByteBuf(this.frame(INBOUND, 1));
        this.user.receiveByteBuf(this.frame(INBOUND, 2));
        assertNull(this.channel.readInbound());
        contexts.get(1).fireChannelRead(messages.get(1));
        contexts.get(0).fireChannelRead(messages.get(0));
        this.assertInbound(2);
        this.assertInbound(1);
        assertEquals(2, this.inboundBytes.get());
        assertEquals(2, this.inboundNms.get());
    }

    @Test
    void reentrantSendsBothVisitListeners() {
        this.channel.pipeline().addAfter("encoder", "reentrant", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (((WirePacket) message).value == 1) {
                    NetworkTransportTest.this.user.sendPacket(NetworkTransportTest.this.packet(OUTBOUND, 2));
                }
                context.write(message, promise);
            }
        });
        this.user.sendPacket(this.packet(OUTBOUND, 1));
        this.assertOutbound(2);
        this.assertOutbound(1);
        assertEquals(2, this.outboundNms.get());
        assertEquals(2, this.outboundBytes.get());
    }

    @Test
    void responseFromInboundVisitsOutboundListeners() {
        this.channel.pipeline().addBefore(this.manager.packetBridgeName, "respond", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                NetworkTransportTest.this.user.sendPacket(NetworkTransportTest.this.packet(OUTBOUND, 8));
                context.fireChannelRead(message);
            }
        });
        this.user.receiveByteBuf(this.frame(INBOUND, 3));
        this.assertInbound(3);
        this.assertOutbound(8);
        assertEquals(1, this.inboundNms.get());
        assertEquals(1, this.inboundBytes.get());
        assertEquals(1, this.outboundNms.get());
        assertEquals(1, this.outboundBytes.get());
    }

    @Test
    void callerCreatedBundlesVisitBothListenerLayers() {
        this.user.sendPacket(new ClientboundBundlePacket(List.of(this.packet(OUTBOUND, 1), this.packet(OUTBOUND, 2), this.packet(OUTBOUND, 3))));
        for (int value = 1; value <= 3; value++) {
            this.assertOutbound(value);
        }
        assertEquals(3, this.outboundNms.get());
        assertEquals(3, this.outboundBytes.get());
        this.user.sendPacket(new ClientboundBundlePacket(List.of(this.packet(OUTBOUND, 4), this.packet(OUTBOUND, 5))));
        this.assertOutbound(4);
        this.assertOutbound(5);
        assertEquals(5, this.outboundNms.get());
        assertEquals(5, this.outboundBytes.get());
    }

    @Test
    void bundleEncodingFailureCompletesTheOriginalPromiseAndReleasesPackets() {
        WirePacket first = this.packet(OUTBOUND, 1);
        WirePacket failing = this.packet(OUTBOUND, 99);
        ChannelPromise promise = this.channel.newPromise();
        this.channel.write(new ClientboundBundlePacket(List.of(first, failing)), promise);
        this.channel.flush();
        assertTrue(promise.isDone());
        assertFalse(promise.isSuccess());
        assertNotNull(promise.cause());
        assertEquals(0, first.refCnt());
        assertEquals(0, failing.refCnt());
        this.assertOutbound(1);
        assertNull(this.channel.readOutbound());
    }

    @Test
    void codecCanRemoveItselfBeforeForwardingItsOutput() {
        this.channel.pipeline().replace("encoder", "encoder", new Encoder() {
            @Override
            protected void encode(ChannelHandlerContext context, WirePacket packet, ByteBuf output) {
                super.encode(context, packet, output);
                context.pipeline().remove(context.name());
            }
        });
        this.user.sendPacket(this.packet(OUTBOUND, 6));
        this.assertOutbound(6);
        assertNull(this.channel.pipeline().get("encoder"));
        assertEquals(1, this.outboundBytes.get());
    }

    @Test
    void byteBufWriterOverloadsResolveIdsAndCleanUpFailedWriters() {
        this.user.sendPacket(OUTBOUND, b -> b.writeVarInt(3));
        this.user.receivePacket(INBOUND, b -> b.writeVarInt(4));
        this.assertOutbound(3);
        this.assertInbound(4);
        assertEquals(1, this.outboundBytes.get());
        assertEquals(1, this.inboundNms.get());
        assertThrows(IllegalArgumentException.class, () -> this.user.sendPacket(INBOUND, b -> fail()));
        assertThrows(IllegalArgumentException.class, () -> this.user.sendPacket(new PacketType("minecraft:missing", ConnectionState.PLAY, PacketFlow.CLIENTBOUND), b -> fail()));
        List<ByteBuf> allocated = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> this.user.sendPacket(OUTBOUND, b -> {
            allocated.add(b.source());
            throw new IllegalArgumentException("writer failure");
        }));
        assertEquals(0, allocated.getFirst().refCnt());
    }

    @Test
    void emptyFramesAreConsumedAtTheInjectionBoundary() {
        ByteBuf outbound = Unpooled.buffer();
        ByteBuf inbound = Unpooled.buffer();
        this.user.sendByteBuf(outbound);
        this.user.receiveByteBuf(inbound);
        assertEquals(0, outbound.refCnt());
        assertEquals(0, inbound.refCnt());
        assertNull(this.channel.readOutbound());
        assertNull(this.channel.readInbound());
    }

    static class Encoder extends MessageToByteEncoder<WirePacket> {
        @Override
        protected void encode(ChannelHandlerContext context, WirePacket packet, ByteBuf output) {
            if (packet.value == 99) throw new IllegalArgumentException("encoding failure");
            new PacketBuf(output).writeVarInt(packet.id).writeVarInt(packet.value);
        }
    }

    static final class Decoder extends ByteToMessageDecoder {
        private final NetworkManager manager;

        Decoder(NetworkManager manager) {
            this.manager = manager;
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
            int id = PacketBuf.readVarInt(input);
            output.add(new WirePacket(this.manager.packetIds().nativeType(INBOUND), id, PacketBuf.readVarInt(input)));
        }
    }

    static final class Unbundler extends MessageToMessageEncoder<ClientboundBundlePacket> {
        @Override
        protected void encode(ChannelHandlerContext context, ClientboundBundlePacket packet, List<Object> output) {
            packet.subPackets().forEach(output::add);
        }
    }

    static final class DelayedWrites extends ChannelOutboundHandlerAdapter {
        private final List<ChannelHandlerContext> contexts = new ArrayList<>();
        private final List<Object> messages = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            this.contexts.add(context);
            this.messages.add(message);
            this.promises.add(promise);
        }

        void forward(int index) {
            this.contexts.get(index).writeAndFlush(this.messages.get(index), this.promises.get(index));
        }
    }

    public static final class WirePacket extends AbstractReferenceCounted implements Packet<Object> {
        private final Object nativeType;
        final int id;
        final int value;

        WirePacket(Object nativeType, int id, int value) {
            this.nativeType = nativeType;
            this.id = id;
            this.value = value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public net.minecraft.network.protocol.PacketType<? extends Packet<Object>> type() {
            return (net.minecraft.network.protocol.PacketType<? extends Packet<Object>>) this.nativeType;
        }

        @Override
        protected void deallocate() {
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            return this;
        }
    }
}
