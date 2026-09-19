package net.momirealms.sparrow.ui.network;

import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import net.momirealms.sparrow.ui.network.packet.PacketTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetworkNmsChainTest {
    private static final PacketType TYPE = PacketTypes.Play.Clientbound.MERCHANT_OFFERS;

    private NetworkManager manager;
    private EmbeddedChannel channel;
    private NetworkUser user;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        this.manager = NetworkManagerTestSupport.openManager();
        this.channel = NetworkManagerTestSupport.openChannel();
        this.user = this.manager.injectConnectionChannel(this.channel);
        this.user.setConnectionState(ConnectionState.PLAY);
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
        NetworkManagerTestSupport.closeManager(this.manager);
        MockBukkit.unmock();
    }

    private TestPacket packet(PacketType type) {
        return new TestPacket(this.manager.packetIds().nativeType(type));
    }

    @Test
    void emptyDirectionNeverQueriesTypeOrExpandsBundle() {
        TestPacket packet = new TestPacket(null);
        this.channel.writeOutbound(packet);
        assertSame(packet, this.channel.readOutbound());
        assertEquals(0, packet.queries);
        packet.release();
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(() -> { throw new AssertionError(); });
        this.channel.writeOutbound(bundle);
        assertSame(bundle, this.channel.readOutbound());
    }

    @Test
    void ordersHandlersAndSharesOneRootEventAcrossLeaves() {
        List<String> order = new ArrayList<>();
        TestPacket a = this.packet(TYPE);
        TestPacket b = this.packet(TYPE);
        ClientboundBundlePacket root = new ClientboundBundlePacket(List.of(a, b));
        AtomicReference<NMSPacketEvent> shared = new AtomicReference<>();
        this.manager.listenNMS(TYPE, (u, e, p) -> order.add(p == a ? "a1" : "b1"));
        this.manager.listenNMS(TYPE, (u, e, p) -> {
            order.add(p == a ? "a2" : "b2");
            if (shared.get() == null) shared.set(e);
            assertSame(shared.get(), e);
            assertSame(root, e.packet);
        });
        this.channel.writeOutbound(root);
        assertSame(root, this.channel.readOutbound());
        assertEquals(List.of("a1", "a2", "b1", "b2"), order);
        assertEquals(1, a.queries);
        assertEquals(1, b.queries);
        a.release();
        b.release();
    }

    @Test
    void cancellationStopsTheChainAndRemainingLeaves() {
        TestPacket a = this.packet(TYPE);
        TestPacket b = this.packet(TYPE);
        this.manager.listenNMS(TYPE, (u, e, p) -> e.cancel());
        this.manager.listenNMS(TYPE, (u, e, p) -> fail());
        assertFalse(this.channel.writeOutbound(new ClientboundBundlePacket(List.of(a, b))));
        assertEquals(1, a.queries);
        assertEquals(0, b.queries);
        // 桥只接管根消息, 子对象的引用计数由其拥有者处理.
        a.release();
        b.release();
    }

    @Test
    void sharedNativeTypeUsesCapturedStageAndDirection() {
        AtomicInteger play = new AtomicInteger();
        AtomicInteger configuration = new AtomicInteger();
        this.manager.listenNMS(PacketTypes.Play.Serverbound.CUSTOM_PAYLOAD, (u, e, p) -> play.incrementAndGet());
        this.manager.listenNMS(PacketTypes.Configuration.Serverbound.CUSTOM_PAYLOAD, (u, e, p) -> configuration.incrementAndGet());
        TestPacket packet = this.packet(PacketTypes.Play.Serverbound.CUSTOM_PAYLOAD);
        this.channel.writeInbound(packet);
        assertSame(packet, this.channel.readInbound());
        this.user.decoderState(ConnectionState.CONFIGURATION);
        this.channel.writeInbound(packet);
        assertSame(packet, this.channel.readInbound());
        this.channel.writeOutbound(packet);
        assertSame(packet, this.channel.readOutbound());
        assertEquals(1, play.get());
        assertEquals(1, configuration.get());
        packet.release();
    }

    @Test
    void transitionMatchesItsSourceAfterByteBufAdvancedTheUser() {
        this.user.setConnectionState(ConnectionState.CONFIGURATION);
        AtomicInteger calls = new AtomicInteger();
        this.manager.listenNMS(PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED, (u, e, p) -> calls.incrementAndGet());
        TestPacket packet = this.packet(PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED);
        this.channel.writeInbound(packet);
        assertSame(packet, this.channel.readInbound());
        assertEquals(1, calls.get());
        packet.release();
    }

    @Test
    void rootReplacementStopsWithoutRematchingAndReleasesOriginal() {
        TestPacket original = this.packet(TYPE);
        TestPacket replacement = this.packet(TYPE);
        this.manager.listenNMS(TYPE, (u, e, p) -> e.replaceRootAndStop(replacement));
        this.manager.listenNMS(TYPE, (u, e, p) -> fail());
        this.channel.writeOutbound(original);
        assertSame(replacement, this.channel.readOutbound());
        assertEquals(0, original.refCnt());
        assertEquals(0, replacement.queries);
        replacement.release();
    }

    @Test
    void replacingTwiceThenCancellingReleasesAllOwnedRootsOnce() {
        TestPacket original = this.packet(TYPE);
        TestPacket first = this.packet(TYPE);
        TestPacket second = this.packet(TYPE);
        this.manager.listenNMS(TYPE, (u, e, p) -> {
            e.replaceRootAndStop(first);
            e.replaceRootAndStop(second);
            assertEquals(0, first.refCnt());
            e.cancel();
        });
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(original, promise);
        assertTrue(promise.isSuccess());
        assertEquals(0, original.refCnt());
        assertEquals(0, second.refCnt());
        assertNull(this.channel.readOutbound());
    }

    @Test
    void replacementEqualToRootDoesNotReleaseItTwice() {
        TestPacket original = this.packet(TYPE);
        this.manager.listenNMS(TYPE, (u, e, p) -> e.replaceRootAndStop(p));
        this.channel.writeOutbound(original);
        assertSame(original, this.channel.readOutbound());
        assertEquals(1, original.refCnt());
        original.release();
    }

    @Test
    void failureAfterReplacementFailsPromiseAndReleasesBoth() {
        TestPacket original = this.packet(TYPE);
        TestPacket replacement = this.packet(TYPE);
        IllegalStateException failure = new IllegalStateException("callback failure");
        this.manager.listenNMS(TYPE, (u, e, p) -> {
            e.replaceRootAndStop(replacement);
            throw failure;
        });
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(original, promise);
        assertSame(failure, promise.cause());
        assertEquals(0, original.refCnt());
        assertEquals(0, replacement.refCnt());
    }

    @Test
    void removingLastHandlerRestoresEmptyDirectionFastPath() {
        Subscription subscription = this.manager.listenNMS(TYPE, (u, e, p) -> fail());
        subscription.close();
        subscription.close();
        TestPacket packet = new TestPacket(null);
        this.channel.writeOutbound(packet);
        assertSame(packet, this.channel.readOutbound());
        assertEquals(0, packet.queries);
        packet.release();
    }

    @Test
    void bundleUsesOneSnapshotAndStateAcrossReentry() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Subscription> subscription = new AtomicReference<>();
        TestPacket a = this.packet(TYPE);
        TestPacket b = this.packet(TYPE);
        subscription.set(this.manager.listenNMS(TYPE, (u, e, p) -> {
            calls.incrementAndGet();
            subscription.get().close();
            u.encoderState(ConnectionState.CONFIGURATION);
            TestPacket nested = new TestPacket(null);
            this.channel.writeOutbound(nested);
            assertSame(nested, this.channel.readOutbound());
            assertEquals(0, nested.queries);
            nested.release();
        }));
        ClientboundBundlePacket root = new ClientboundBundlePacket(List.of(a, b));
        this.channel.writeOutbound(root);
        assertSame(root, this.channel.readOutbound());
        assertEquals(2, calls.get());
        a.release();
        b.release();
    }

    @Test
    void sharedOutboundTypeKeepsRootStageAcrossNestedDispatch() {
        PacketType playType = PacketTypes.Play.Clientbound.CUSTOM_PAYLOAD;
        PacketType configurationType = PacketTypes.Configuration.Clientbound.CUSTOM_PAYLOAD;
        AtomicInteger playCalls = new AtomicInteger();
        AtomicInteger configurationCalls = new AtomicInteger();
        AtomicReference<NMSPacketEvent> outer = new AtomicReference<>();
        this.manager.listenNMS(playType, (u, e, p) -> {
            playCalls.incrementAndGet();
            if (outer.compareAndSet(null, e)) {
                u.encoderState(ConnectionState.CONFIGURATION);
                TestPacket nested = this.packet(configurationType);
                this.channel.writeOutbound(nested);
                assertSame(nested, this.channel.readOutbound());
                nested.release();
            } else {
                assertSame(outer.get(), e);
            }
        });
        this.manager.listenNMS(configurationType, (u, e, p) -> {
            configurationCalls.incrementAndGet();
            assertNotSame(outer.get(), e);
        });
        TestPacket a = this.packet(playType);
        TestPacket b = this.packet(playType);
        ClientboundBundlePacket root = new ClientboundBundlePacket(List.of(a, b));
        this.channel.writeOutbound(root);
        assertSame(root, this.channel.readOutbound());
        assertEquals(2, playCalls.get());
        assertEquals(1, configurationCalls.get());
        a.release();
        b.release();
    }

    @Test
    void statePacketCancellationConsumesInboundRootAndKeepsConnectionOpen() {
        PacketType type = PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED;
        this.manager.listenNMS(type, (u, e, p) -> e.cancel());
        TestPacket packet = this.packet(type);
        this.channel.writeInbound(packet);
        assertTrue(this.channel.isOpen());
        assertNull(this.channel.readInbound());
        assertEquals(0, packet.refCnt());
    }

    @Test
    void statePacketReplacementTransfersOwnershipAndStopsLaterCallbacks() {
        PacketType type = PacketTypes.Play.Clientbound.START_CONFIGURATION;
        TestPacket replacement = this.packet(type);
        this.manager.listenNMS(type, (u, e, p) -> e.replaceRootAndStop(replacement));
        this.manager.listenNMS(type, (u, e, p) -> fail("replaced chain continued"));
        TestPacket original = this.packet(type);
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(original, promise);
        assertTrue(promise.isSuccess());
        assertTrue(this.channel.isOpen());
        assertEquals(0, original.refCnt());
        assertSame(replacement, this.channel.readOutbound());
        assertEquals(1, replacement.refCnt());
        replacement.release();
    }

    @Test
    void statePacketFailureConsumesRootAndFailsPromiseWithoutClosingConnection() {
        PacketType type = PacketTypes.Play.Clientbound.START_CONFIGURATION;
        IllegalStateException failure = new IllegalStateException("NMS listener failure");
        this.manager.listenNMS(type, (u, e, p) -> { throw failure; });
        this.manager.listenNMS(type, (u, e, p) -> fail("failed chain continued"));
        TestPacket packet = this.packet(type);
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(packet, promise);
        assertSame(failure, promise.cause());
        assertEquals(0, packet.refCnt());
        assertNull(this.channel.readOutbound());
        assertTrue(this.channel.isOpen());
    }

    @Test
    void nonNmsMessagesPassThroughWithPopulatedTables() {
        this.manager.listenNMS(TYPE, (u, e, p) -> fail());
        Object message = new Object();
        this.channel.writeOutbound(message);
        assertSame(message, this.channel.readOutbound());
    }

    public static final class TestPacket extends AbstractReferenceCounted implements Packet<Object> {
        private final Object nativeType;
        private int queries;

        TestPacket(Object nativeType) {
            this.nativeType = nativeType;
        }

        @Override
        @SuppressWarnings("unchecked")
        public net.minecraft.network.protocol.PacketType<? extends Packet<Object>> type() {
            this.queries++;
            assertNotNull(this.nativeType, "unexpected type lookup");
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
