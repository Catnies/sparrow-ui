package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import net.minecraft.network.ProtocolSwapHandler;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NetworkTerminalInjectionTest {
    private NetworkManager manager;
    private EmbeddedChannel channel;
    private NetworkUser user;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
        this.channel = NetworkManagerTestSupport.openChannel();
        this.user = this.manager.injectConnectionChannel(this.channel);
        this.user.setConnectionState(ConnectionState.LOGIN);
    }

    @AfterEach
    void tearDown() {
        try {
            this.channel.finishAndReleaseAll();
        } finally {
            try {
                NetworkManagerTestSupport.closeManager(this.manager);
            } finally {
                MockBukkit.unmock();
            }
        }
    }

    private TerminalPacket packet() {
        return new TerminalPacket(this.manager.packetIds().nativeType(new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void objectInjectionPreparesThenInstallsTheNextDecoder(boolean silent) {
        AtomicInteger calls = new AtomicInteger();
        this.manager.listenNMS(new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND), (u, e, p) -> {
            this.assertWaitingForProtocol();
            calls.incrementAndGet();
        });
        TerminalPacket packet = this.packet();
        if (silent) {
            this.user.receivePacketSilently(packet);
        } else {
            this.user.receivePacket(packet);
        }
        assertSame(packet, this.channel.readInbound());
        packet.release();
        this.assertWaitingForProtocol();
        assertEquals(silent ? 0 : 1, calls.get());
        assertEquals(silent ? ConnectionState.LOGIN : ConnectionState.CONFIGURATION, this.user.decoderState());

        AtomicInteger decoded = new AtomicInteger();
        ChannelHandler nextDecoder = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                decoded.incrementAndGet();
                context.fireChannelRead(message);
            }
        };
        ChannelPromise configured = this.channel.newPromise();
        // 原版 Connection.setupInboundProtocol 写出任务, 由 inbound_config 安装下一阶段 decoder 并恢复读取.
        this.channel.pipeline().writeAndFlush((UnconfiguredPipelineHandler.InboundConfigurationTask) context -> {
            context.pipeline().replace(context.name(), "decoder", nextDecoder);
            context.channel().config().setAutoRead(true);
        }, configured);
        assertTrue(configured.isSuccess(), () -> String.valueOf(configured.cause()));
        assertNull(this.channel.readOutbound());
        assertNull(this.channel.pipeline().get("inbound_config"));
        assertSame(nextDecoder, this.channel.pipeline().get("decoder"));
        assertTrue(this.channel.config().isAutoRead());
        ByteBuf frame = Unpooled.buffer().writeByte(0);
        this.user.receiveByteBuf(frame);
        assertEquals(1, decoded.get());
        assertSame(frame, this.channel.readInbound());
        frame.release();
    }

    @Test
    void decodedTerminalCanBeInjectedWithAnExistingWaitingHandler() {
        TerminalPacket packet = this.packet();
        ProtocolSwapHandler.handleInboundTerminalPacket(this.channel.pipeline().context("decoder"), packet);
        ChannelHandler waiting = this.channel.pipeline().get("inbound_config");
        this.user.receivePacket(packet);
        assertSame(packet, this.channel.readInbound());
        assertSame(waiting, this.channel.pipeline().get("inbound_config"));
        this.assertWaitingForProtocol();
        packet.release();
    }

    @Test
    void cancelledTerminalKeepsVanillaWaitingStateAndReleasesPacket() {
        this.manager.listenNMS(new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND), (u, e, p) -> e.cancel());
        TerminalPacket packet = this.packet();
        this.user.receivePacket(packet);
        this.assertWaitingForProtocol();
        assertNull(this.channel.readInbound());
        assertEquals(0, packet.refCnt());
    }

    @Test
    void missingProtocolHandlerFailsBeforeDeliveringTerminal() {
        this.channel.pipeline().remove("decoder");
        TerminalPacket packet = this.packet();
        this.user.receivePacket(packet);
        assertThrows(IllegalStateException.class, this.channel::checkException);
        assertNull(this.channel.readInbound());
        assertEquals(0, packet.refCnt());
        assertEquals(ConnectionState.LOGIN, this.user.decoderState());
    }

    @Test
    void ordinaryObjectLeavesDecoderAndAutoReadUnchanged() {
        ChannelHandler decoder = this.channel.pipeline().get("decoder");
        this.channel.config().setAutoRead(false);
        NetworkNmsChainTest.TestPacket packet = new NetworkNmsChainTest.TestPacket(this.manager.packetIds().nativeType(new PacketType("minecraft:hello", ConnectionState.LOGIN, PacketFlow.SERVERBOUND)));
        this.user.receivePacket(packet);
        assertSame(packet, this.channel.readInbound());
        assertSame(decoder, this.channel.pipeline().get("decoder"));
        assertNull(this.channel.pipeline().get("inbound_config"));
        assertFalse(this.channel.config().isAutoRead());
        packet.release();
    }

    @Test
    void byteInjectionUsesDecoderTerminalHandlingOnce() {
        TerminalPacket packet = this.packet();
        this.channel.pipeline().replace("decoder", "decoder", new MessageToMessageDecoder<ByteBuf>() {
            @Override
            protected void decode(ChannelHandlerContext context, ByteBuf frame, List<Object> output) {
                assertEquals(NetworkTerminalInjectionTest.this.manager.packetIds().id(new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND)), PacketBuf.readVarInt(frame));
                output.add(packet);
                ProtocolSwapHandler.handleInboundTerminalPacket(context, packet);
            }
        });
        this.user.receivePacket(new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND), buffer -> {});
        assertSame(packet, this.channel.readInbound());
        this.assertWaitingForProtocol();
        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        packet.release();
    }

    private void assertWaitingForProtocol() {
        assertFalse(this.channel.config().isAutoRead());
        assertNull(this.channel.pipeline().get("decoder"));
        assertInstanceOf(UnconfiguredPipelineHandler.Inbound.class, this.channel.pipeline().get("inbound_config"));
    }

    public static final class TerminalPacket extends AbstractReferenceCounted implements Packet<Object> {
        private final Object nativeType;

        TerminalPacket(Object nativeType) {
            this.nativeType = nativeType;
        }

        @Override
        @SuppressWarnings("unchecked")
        public net.minecraft.network.protocol.PacketType<? extends Packet<Object>> type() {
            return (net.minecraft.network.protocol.PacketType<? extends Packet<Object>>) this.nativeType;
        }

        @Override
        public boolean isTerminal() {
            return true;
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            return this;
        }

        @Override
        protected void deallocate() {
        }
    }
}
