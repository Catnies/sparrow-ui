package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.network.listener.configuration.FinishConfigurationListener;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import net.momirealms.sparrow.ui.network.packet.PacketTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class NetworkProtocolStateTest {

    private NetworkManager manager;
    private EmbeddedChannel channel;
    private NetworkUser user;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
        this.channel = NetworkManagerTestSupport.openChannel();
        this.user = this.manager.injectConnectionChannel(this.channel);

        assertNotNull(this.user);
        // 为这些协议测试补上字节到对象的边界; 其他网络测试仍可使用透传 decoder.
        this.channel.pipeline().replace("decoder", "decoder", new MessageToMessageDecoder<ByteBuf>() {
            @Override
            @SuppressWarnings("unchecked")
            protected void decode(ChannelHandlerContext context, ByteBuf frame, List<Object> output) {
                PacketBuf buffer = new PacketBuf(frame);
                int id = buffer.readVarInt();
                PacketType type = switch (NetworkProtocolStateTest.this.user.decoderState()) {
                    case HANDSHAKING -> PacketTypes.Handshaking.Serverbound.INTENTION;
                    case LOGIN -> PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED;
                    case CONFIGURATION -> PacketTypes.Configuration.Serverbound.FINISH_CONFIGURATION;
                    case PLAY -> PacketTypes.Play.Serverbound.CONFIGURATION_ACKNOWLEDGED;
                    default -> throw new AssertionError("Unexpected decoder state");
                };
                assertEquals(NetworkProtocolStateTest.this.manager.packetIds().id(type), id);
                Object nativeType = NetworkProtocolStateTest.this.manager.packetIds().nativeType(type);
                if (type == PacketTypes.Handshaking.Serverbound.INTENTION) {
                    buffer.readVarInt();
                    buffer.readUtf();
                    buffer.readUnsignedShort();
                    output.add(new ClientIntentionPacket(ClientIntent.byId(buffer.readVarInt()), (net.minecraft.network.protocol.PacketType) nativeType));
                } else {
                    output.add(new NetworkNmsChainTest.TestPacket(nativeType));
                }
                assertFalse(buffer.isReadable());
            }
        });
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
        NetworkManagerTestSupport.closeManager(this.manager);
        MockBukkit.unmock();
    }

    private int id(String name, ConnectionState state, PacketFlow flow) {
        return this.manager.packetIds().byName(name, state, flow);
    }

    private void inbound(String name, ConnectionState state) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(this.id(name, state, PacketFlow.SERVERBOUND));
        this.channel.writeInbound(buffer.source());
        this.drainInbound();
    }

    private void outbound(String name, ConnectionState state) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(this.id(name, state, PacketFlow.CLIENTBOUND));
        this.channel.writeOutbound(buffer.source());
        this.drainOutbound();
    }

    private void intention(int nextState) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(this.id("minecraft:intention", ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND));
        buffer.writeVarInt(772);
        buffer.writeUtf("localhost");
        buffer.writeShort(25565);
        buffer.writeVarInt(nextState);
        this.channel.writeInbound(buffer.source());
        this.drainInbound();
    }

    private void drainInbound() {
        Object packet;
        while ((packet = this.channel.readInbound()) != null) {
            ReferenceCountUtil.release(packet);
        }
    }

    private void drainOutbound() {
        ByteBuf frame;
        while ((frame = this.channel.readOutbound()) != null) {
            frame.release();
        }
    }

    @Test
    void startsInHandshaking() {
        assertEquals(ConnectionState.HANDSHAKING, this.user.decoderState());
        assertEquals(ConnectionState.HANDSHAKING, this.user.encoderState());
    }

    @Test
    void intentionOneMovesBothDirectionsToStatus() {
        this.intention(1);

        assertEquals(ConnectionState.STATUS, this.user.decoderState());
        assertEquals(ConnectionState.STATUS, this.user.encoderState());
    }

    @Test
    void intentionTwoAndThreeMoveBothDirectionsToLogin() {
        this.intention(2);

        assertEquals(ConnectionState.LOGIN, this.user.decoderState());
        assertEquals(ConnectionState.LOGIN, this.user.encoderState());
        this.user.setConnectionState(ConnectionState.HANDSHAKING);
        this.intention(3);

        assertEquals(ConnectionState.LOGIN, this.user.decoderState(), "transfer 也进登录阶段");
    }

    @Test
    void followsTheVanillaLoginHandshake() {
        this.intention(2);
        this.inbound("minecraft:login_acknowledged", ConnectionState.LOGIN);

        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());
        this.inbound("minecraft:finish_configuration", ConnectionState.CONFIGURATION);

        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        assertEquals(ConnectionState.PLAY, this.user.encoderState());
        this.outbound("minecraft:login", ConnectionState.PLAY);

        assertEquals(ConnectionState.PLAY, this.user.decoderState());
        assertEquals(ConnectionState.PLAY, this.user.encoderState());
    }

    @Test
    void followsTheReconfigurationRoundTrip() {
        this.user.setConnectionState(ConnectionState.PLAY);
        this.outbound("minecraft:start_configuration", ConnectionState.PLAY);

        assertEquals(ConnectionState.PLAY, this.user.decoderState(), "客户端确认之前入站仍是 PLAY");
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());
        this.inbound("minecraft:configuration_acknowledged", ConnectionState.PLAY);

        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());
        this.inbound("minecraft:finish_configuration", ConnectionState.CONFIGURATION);

        assertEquals(ConnectionState.PLAY, this.user.encoderState());
        this.outbound("minecraft:login", ConnectionState.PLAY);

        assertEquals(ConnectionState.PLAY, this.user.decoderState());
    }

    @Test
    void stateListenerUsesRegistrationOrderCancellationAndSubscriptionRemoval() {
        this.user.setConnectionState(ConnectionState.CONFIGURATION);
        PacketType type = PacketTypes.Configuration.Serverbound.CUSTOM_PAYLOAD;
        Subscription cancellation = this.manager.listenNMS(type, (user, event, packet) -> event.cancel());
        Subscription stateListener = this.manager.listenNMS(type, FinishConfigurationListener.INSTANCE);
        this.user.receivePacket(new NetworkNmsChainTest.TestPacket(this.manager.packetIds().nativeType(type)));
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());

        cancellation.close();
        this.user.receivePacket(new NetworkNmsChainTest.TestPacket(this.manager.packetIds().nativeType(type)));
        this.drainInbound();
        assertEquals(ConnectionState.PLAY, this.user.encoderState());

        this.user.encoderState(ConnectionState.CONFIGURATION);
        stateListener.close();
        this.user.receivePacket(new NetworkNmsChainTest.TestPacket(this.manager.packetIds().nativeType(type)));
        this.drainInbound();
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());
        assertTrue(this.channel.isOpen());
    }

    @Test
    void byteCancellationPreventsAllFourInboundTransitions() {
        PacketType[] types = {
                PacketTypes.Handshaking.Serverbound.INTENTION,
                PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED,
                PacketTypes.Configuration.Serverbound.FINISH_CONFIGURATION,
                PacketTypes.Play.Serverbound.CONFIGURATION_ACKNOWLEDGED
        };
        for (int index = 0; index < types.length; index++) {
            PacketType type = types[index];
            this.user.setConnectionState(type.state());
            Subscription cancellation = this.manager.listenByteBuf(type, (u, e) -> e.cancel());
            this.user.receivePacket(type, buffer -> {});
            assertEquals(type.state(), this.user.decoderState());
            assertEquals(type.state(), this.user.encoderState());
            assertNull(this.channel.readInbound());
            cancellation.close();
        }
    }

    @Test
    void objectAndByteReceivesRespectSilentStateListenerMode() {
        PacketType type = PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED;
        this.manager.listenNMS(type, (u, e, p) -> assertEquals(ConnectionState.CONFIGURATION, u.decoderState()));
        this.user.setConnectionState(ConnectionState.LOGIN);
        this.user.receivePacketSilently(new NetworkNmsChainTest.TestPacket(this.manager.packetIds().nativeType(type)));
        assertEquals(ConnectionState.LOGIN, this.user.decoderState());
        this.drainInbound();
        this.user.receivePacket(new NetworkNmsChainTest.TestPacket(this.manager.packetIds().nativeType(type)));
        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        this.drainInbound();

        this.user.setConnectionState(ConnectionState.LOGIN);
        this.user.receivePacketSilently(type, buffer -> {});
        assertEquals(ConnectionState.LOGIN, this.user.decoderState());
        this.drainInbound();
        this.user.receivePacket(type, buffer -> {});
        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        this.drainInbound();
    }

    @Test
    void nmsCancellationAndFailureKeepAlreadyAppliedState() {
        PacketType type = PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED;
        this.user.setConnectionState(ConnectionState.LOGIN);
        Subscription cancellation = this.manager.listenNMS(type, (u, e, p) -> e.cancel());
        this.user.receivePacket(type, buffer -> {});
        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        assertNull(this.channel.readInbound());
        cancellation.close();

        this.user.setConnectionState(ConnectionState.LOGIN);
        IllegalStateException failure = new IllegalStateException("state listener failed");
        this.manager.listenNMS(type, (u, e, p) -> { throw failure; });
        this.user.receivePacket(type, buffer -> {});
        assertSame(failure, assertThrows(IllegalStateException.class, this.channel::checkException));
        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
        assertNull(this.channel.readInbound());
    }

    @Test
    void malformedHandshakeFailsInDecoderBeforeNmsStateListener() {
        this.manager.listenNMS(PacketTypes.Handshaking.Serverbound.INTENTION, (user, event, packet) -> fail("invalid handshake continued"));
        ByteBuf frame = Unpooled.buffer();
        new PacketBuf(frame).writeVarInt(this.manager.packetIds().id(PacketTypes.Handshaking.Serverbound.INTENTION));
        frame.writeByte(0x80);
        assertThrows(DecoderException.class, () -> this.channel.writeInbound(frame));
        assertNull(this.channel.readInbound());
        assertEquals(0, frame.refCnt());
        assertEquals(ConnectionState.HANDSHAKING, this.user.decoderState());
    }

    @Test
    void unknownHandshakeIntentionFailsInDecoderWithoutAdvancingState() {
        this.manager.listenNMS(PacketTypes.Handshaking.Serverbound.INTENTION, (user, event, packet) -> fail("invalid handshake continued"));
        assertThrows(DecoderException.class, () -> this.intention(4));
        assertEquals(ConnectionState.HANDSHAKING, this.user.decoderState());
        assertEquals(ConnectionState.HANDSHAKING, this.user.encoderState());
    }
}
