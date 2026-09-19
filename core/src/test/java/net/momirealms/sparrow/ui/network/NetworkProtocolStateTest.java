package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        ByteBuf frame;
        while ((frame = this.channel.readInbound()) != null) {
            frame.release();
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
        Subscription cancellation = this.manager.listenByteBuf(type, (user, event) -> event.cancel());
        Subscription stateListener = this.manager.listenByteBuf(type, FinishConfigurationListener.INSTANCE);
        this.inbound(type.name(), type.state());
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());

        cancellation.close();
        this.inbound(type.name(), type.state());
        assertEquals(ConnectionState.PLAY, this.user.encoderState());

        this.user.encoderState(ConnectionState.CONFIGURATION);
        stateListener.close();
        this.inbound(type.name(), type.state());
        assertEquals(ConnectionState.CONFIGURATION, this.user.encoderState());
        assertTrue(this.channel.isOpen());
    }

    @Test
    void malformedHandshakeIsConsumedByItsListenerAndStopsLaterCallbacks() {
        this.manager.listenByteBuf(PacketTypes.Handshaking.Serverbound.INTENTION, (user, event) -> fail("invalid handshake continued"));
        ByteBuf frame = Unpooled.buffer();
        new PacketBuf(frame).writeVarInt(this.manager.packetIds().id(PacketTypes.Handshaking.Serverbound.INTENTION));
        frame.writeByte(0x80);
        assertFalse(this.channel.writeInbound(frame));
        assertNull(this.channel.readInbound());
        assertEquals(0, frame.refCnt());
        assertFalse(this.channel.isOpen());
        assertEquals(ConnectionState.HANDSHAKING, this.user.decoderState());
    }

    @Test
    void unknownHandshakeIntentionClosesWithoutAdvancingState() {
        this.manager.listenByteBuf(PacketTypes.Handshaking.Serverbound.INTENTION, (user, event) -> fail("invalid handshake continued"));
        this.intention(4);
        assertFalse(this.channel.isOpen());
        assertEquals(ConnectionState.HANDSHAKING, this.user.decoderState());
        assertEquals(ConnectionState.HANDSHAKING, this.user.encoderState());
    }
}
