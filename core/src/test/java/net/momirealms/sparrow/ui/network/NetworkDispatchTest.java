package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NetworkDispatchTest {

    private static final String LISTENED = "minecraft:container_close";
    private static final String IGNORED = "minecraft:accept_teleportation";
    private NetworkManager manager;
    private EmbeddedChannel channel;
    private NetworkUser user;
    private int listenedId;
    private int ignoredId;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
        this.channel = NetworkManagerTestSupport.openChannel();
        this.user = this.manager.injectConnectionChannel(this.channel);

        assertNotNull(this.user);
        this.user.setConnectionState(ConnectionState.PLAY);
        this.listenedId = this.manager.packetIds().byName(LISTENED, ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        this.ignoredId = this.manager.packetIds().byName(IGNORED, ConnectionState.PLAY, PacketFlow.SERVERBOUND);
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
        NetworkManagerTestSupport.closeManager(this.manager);
        MockBukkit.unmock();
    }

    private static ByteBuf frame(int packetId, int payload) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(packetId);
        buffer.writeVarInt(payload);
        return buffer.source();
    }

    private void listen(ByteBufPacketListener listener) {
        this.manager.registerByteBufPacketListener(listener, LISTENED, ConnectionState.PLAY, PacketFlow.SERVERBOUND);
    }

    @Test
    void passesUnroutedFrameThroughUntouched() {
        this.channel.writeInbound(frame(this.ignoredId, 77));
        ByteBuf received = this.channel.readInbound();

        assertNotNull(received);
        PacketBuf buffer = new PacketBuf(received);

        assertEquals(this.ignoredId, buffer.readVarInt());
        assertEquals(77, buffer.readVarInt());
        received.release();
    }

    @Test
    void restoresPointersAfterAReadOnlyListener() {
        AtomicInteger seen = new AtomicInteger(-1);
        this.listen(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                seen.set(event.getBuffer().readVarInt());
            }
        });
        this.channel.writeInbound(frame(this.listenedId, 42));

        assertEquals(42, seen.get());
        ByteBuf received = this.channel.readInbound();

        assertNotNull(received);
        PacketBuf buffer = new PacketBuf(received);

        assertEquals(this.listenedId, buffer.readVarInt());
        assertEquals(42, buffer.readVarInt());
        received.release();
    }

    @Test
    void dropsCancelledFrame() {
        this.listen(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                event.cancelled(true);
            }
        });
        this.channel.writeInbound(frame(this.listenedId, 42));

        assertNull(this.channel.readInbound());
    }

    @Test
    void forwardsRewrittenFrame() {
        this.listen(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                PacketBuf buffer = event.getBuffer();
                event.changed(true);
                buffer.clear();
                buffer.writeVarInt(event.packetId());
                buffer.writeVarInt(99);
            }
        });
        this.channel.writeInbound(frame(this.listenedId, 42));
        ByteBuf received = this.channel.readInbound();

        assertNotNull(received);
        PacketBuf buffer = new PacketBuf(received);

        assertEquals(this.listenedId, buffer.readVarInt());
        assertEquals(99, buffer.readVarInt());
        received.release();
    }

    @Test
    void isolatesListenerFailureAndKeepsTheOriginalFrame() {
        this.listen(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                event.getBuffer().readVarInt();
                throw new IllegalStateException("boom");
            }
        });
        this.channel.writeInbound(frame(this.listenedId, 42));
        ByteBuf received = this.channel.readInbound();

        assertNotNull(received);
        PacketBuf buffer = new PacketBuf(received);

        assertEquals(this.listenedId, buffer.readVarInt());
        assertEquals(42, buffer.readVarInt());
        received.release();
    }

    @Test
    void dropsHalfRewrittenFrameWhenTheListenerFails() {
        this.listen(new ByteBufPacketListener() {
            @Override
            public void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                PacketBuf buffer = event.getBuffer();
                event.changed(true);
                buffer.clear();
                buffer.writeVarInt(event.packetId());
                throw new IllegalStateException("boom");
            }
        });
        this.channel.writeInbound(frame(this.listenedId, 42));

        assertNull(this.channel.readInbound());
    }

    @Test
    void refusesASecondListenerOnTheSameRoute() {
        this.listen(new ByteBufPacketListener() {
        });
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> this.listen(new ByteBufPacketListener() {
        }));
    }

    @Test
    void dispatchesOutboundFramesByTheEncoderState() {
        AtomicReference<Integer> seen = new AtomicReference<>();
        int packetId = this.manager.packetIds().byName("minecraft:merchant_offers", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        this.manager.registerByteBufPacketListener(new ByteBufPacketListener() {
            @Override
            public void onPacketSend(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
                seen.set(event.getBuffer().readVarInt());
                event.cancelled(true);
            }
        }, "minecraft:merchant_offers", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
        this.channel.writeOutbound(frame(packetId, 5));

        assertEquals(5, seen.get());
        assertNull(this.channel.readOutbound(), "出站取消后不应有帧写出");
    }
}
