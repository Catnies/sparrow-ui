package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.momirealms.sparrow.ui.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetworkListenerChainTest {
    private static final PacketType TYPE = PacketTypes.Play.Serverbound.RENAME_ITEM;

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

    private ByteBuf frame(PacketType type, int payload) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(this.manager.packetIds().id(type));
        buffer.writeVarInt(payload);
        return buffer.source();
    }

    private void inbound() {
        this.channel.writeInbound(this.frame(TYPE, 42));
        ByteBuf result = this.channel.readInbound();
        if (result != null) {
            result.release();
        }
    }

    @Test
    void dispatchesInRegistrationOrder() {
        List<Integer> order = new ArrayList<>();
        this.manager.listenByteBuf(TYPE, (u, e) -> order.add(2));
        this.manager.listenByteBuf(TYPE, (u, e) -> order.add(5));
        this.manager.listenByteBuf(TYPE, (u, e) -> order.add(1));
        this.manager.listenByteBuf(TYPE, (u, e) -> order.add(3));
        this.manager.listenByteBuf(TYPE, (u, e) -> order.add(0));
        this.manager.listenByteBuf(TYPE, (u, e) -> order.add(4));
        this.inbound();
        assertEquals(List.of(2, 5, 1, 3, 0, 4), order);
    }

    @Test
    void duplicateInstancesHaveIndependentSubscriptions() {
        AtomicInteger calls = new AtomicInteger();
        ByteBufPacketHandler handler = (u, e) -> calls.incrementAndGet();
        Subscription first = this.manager.listenByteBuf(TYPE, handler);
        Subscription second = this.manager.listenByteBuf(TYPE, handler);
        this.inbound();
        assertEquals(2, calls.get());
        first.close();
        first.close();
        this.inbound();
        assertEquals(3, calls.get());
        assertTrue(first.isClosed());
        assertFalse(second.isClosed());
        second.close();
        this.inbound();
        assertEquals(3, calls.get());
    }

    @Test
    void callbackChangesAffectOnlySubsequentSnapshots() {
        List<String> calls = new ArrayList<>();
        AtomicReference<Subscription> second = new AtomicReference<>();
        AtomicReference<Subscription> first = new AtomicReference<>();
        first.set(this.manager.listenByteBuf(TYPE, (u, e) -> {
            calls.add("a");
            second.get().close();
            first.get().close();
            this.manager.listenByteBuf(TYPE, (nestedUser, nestedEvent) -> calls.add("c"));
        }));
        second.set(this.manager.listenByteBuf(TYPE, (u, e) -> calls.add("b")));
        this.inbound();
        this.inbound();
        assertEquals(List.of("a", "b", "c"), calls);
    }

    @Test
    void cancellationStopsAllLaterCallbacks() {
        this.manager.listenByteBuf(TYPE, (u, e) -> e.cancel());
        this.manager.listenByteBuf(TYPE, (u, e) -> fail("cancelled chain continued"));
        ByteBuf frame = this.frame(TYPE, 42);
        assertFalse(this.channel.writeInbound(frame));
        assertEquals(0, frame.refCnt());
    }

    @Test
    void rewritesGrowAndShrinkWithNonzeroFrameStartAndLastReaderAtEnd() {
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            e.buffer().clear();
            e.buffer().writeUtf("longer payload");
        });
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            assertEquals("longer payload", e.buffer().readUtf());
            assertTrue(e.changed());
            e.buffer().clear();
            e.buffer().writeUtf("x");
        });
        this.manager.listenByteBuf(TYPE, (u, e) -> assertEquals("x", e.buffer().readUtf()));
        PacketBuf frame = new PacketBuf(Unpooled.buffer());
        frame.writeInt(1234);
        frame.writeVarInt(this.manager.packetIds().id(TYPE));
        frame.writeUtf("original");
        frame.readerIndex(4);
        this.channel.writeInbound(frame.source());
        ByteBuf result = this.channel.readInbound();
        assertSame(frame.source(), result);
        assertEquals(4, result.readerIndex());
        assertEquals(this.manager.packetIds().id(TYPE), PacketBuf.readVarInt(result));
        assertEquals("x", new PacketBuf(result).readUtf());
        assertFalse(result.isReadable());
        result.release();
    }

    @Test
    void reusesThePayloadViewAndCommitsAbsoluteWritesAndLengthChanges() {
        AtomicReference<PacketBuf> view = new AtomicReference<>();
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            view.set(e.buffer());
            assertEquals(0, e.buffer().readerIndex());
            assertEquals(42, e.buffer().readVarInt());
            assertFalse(e.changed());
            e.buffer().setByte(0, 77);
            e.buffer().writeVarInt(88);
        });
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            assertSame(view.get(), e.buffer());
            assertEquals(0, e.buffer().readerIndex());
            assertEquals(77, e.buffer().readVarInt());
            assertEquals(88, e.buffer().readVarInt());
            assertTrue(e.changed());
            e.buffer().setIndex(0, 1);
        });
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            assertEquals(77, e.buffer().readVarInt());
            assertFalse(e.buffer().isReadable());
        });
        this.channel.writeInbound(this.frame(TYPE, 42));
        ByteBuf result = this.channel.readInbound();
        assertEquals(this.manager.packetIds().id(TYPE), PacketBuf.readVarInt(result));
        assertEquals(77, PacketBuf.readVarInt(result));
        assertFalse(result.isReadable());
        result.release();
    }

    @Test
    void readOnlyFailureKeepsEarlierRewriteAndContinues() {
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            e.buffer().clear();
            e.buffer().writeVarInt(99);
        });
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            assertEquals(99, e.buffer().readVarInt());
            throw new IllegalStateException("read failure");
        });
        this.manager.listenByteBuf(TYPE, (u, e) -> assertEquals(99, e.buffer().readVarInt()));
        this.channel.writeInbound(this.frame(TYPE, 42));
        ByteBuf result = this.channel.readInbound();
        PacketBuf.readVarInt(result);
        assertEquals(99, PacketBuf.readVarInt(result));
        result.release();
    }

    @Test
    void nestedDispatchUsesAnIndependentEventAndFreshSnapshot() {
        AtomicReference<ByteBufPacketEvent> outer = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            if (outer.compareAndSet(null, e)) {
                this.manager.listenByteBuf(TYPE, (nu, ne) -> calls.incrementAndGet());
                this.inbound();
                assertEquals(42, e.buffer().readVarInt());
            } else {
                assertNotSame(outer.get(), e);
            }
        });
        this.inbound();
        assertEquals(1, calls.get());
    }

    @Test
    void missingTypesAndClosedManagerHaveExplicitResults() {
        PacketType missing = new PacketType("minecraft:missing", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
        assertThrows(IllegalArgumentException.class, () -> this.manager.listenByteBuf(missing, (u, e) -> {}));
        assertThrows(IllegalArgumentException.class, () -> this.manager.listenNMS(missing, (u, e, p) -> {}));
        Subscription live = this.manager.listenByteBuf(TYPE, (u, e) -> {});
        Subscription nms = this.manager.listenNMS(TYPE, (u, e, p) -> {});
        this.manager.close();
        assertTrue(nms.isClosed());
        assertTrue(live.isClosed());
        live.close();
        assertThrows(IllegalStateException.class, () -> this.manager.listenByteBuf(missing, (u, e) -> {}));
        assertThrows(IllegalStateException.class, () -> this.manager.listenNMS(missing, (u, e, p) -> {}));
    }

    @Test
    void concurrentRegistrationAndClosureDoNotLoseEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Callable<Subscription>> registrations = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            registrations.add(() -> this.manager.listenByteBuf(TYPE, (u, e) -> calls.incrementAndGet()));
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            var registered = executor.invokeAll(registrations);
            this.inbound();
            assertEquals(64, calls.get());
            List<Callable<Void>> closures = new ArrayList<>();
            for (var result : registered) {
                closures.add(() -> {
                    result.get().close();
                    return null;
                });
            }
            for (var result : executor.invokeAll(closures)) {
                result.get();
            }
            this.inbound();
            assertEquals(64, calls.get());
        }
    }

    @Test
    void closeRacingRegistrationCannotRepublishHandlers() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var registration = executor.submit(() -> {
                start.await();
                try {
                    return this.manager.listenByteBuf(TYPE, (u, e) -> fail());
                } catch (IllegalStateException expected) {
                    return null;
                }
            });
            start.countDown();
            this.manager.close();
            Subscription result = registration.get();
            if (result != null) {
                assertTrue(result.isClosed());
                result.close();
            }
            assertThrows(IllegalStateException.class, () -> this.manager.listenByteBuf(TYPE, (u, e) -> {}));
        }
    }

    @Test
    void routesAreIsolatedAcrossStatesAndDirections() {
        AtomicInteger play = new AtomicInteger();
        AtomicInteger configuration = new AtomicInteger();
        PacketType playType = PacketTypes.Play.Serverbound.CUSTOM_PAYLOAD;
        PacketType configurationType = PacketTypes.Configuration.Serverbound.CUSTOM_PAYLOAD;
        this.manager.listenByteBuf(playType, (u, e) -> play.incrementAndGet());
        this.manager.listenByteBuf(configurationType, (u, e) -> configuration.incrementAndGet());
        this.channel.writeInbound(this.frame(playType, 42));
        ((ByteBuf) this.channel.readInbound()).release();
        this.user.decoderState(ConnectionState.CONFIGURATION);
        this.channel.writeInbound(this.frame(configurationType, 42));
        ((ByteBuf) this.channel.readInbound()).release();
        this.channel.writeOutbound(this.frame(PacketTypes.Play.Clientbound.CUSTOM_PAYLOAD, 42));
        ((ByteBuf) this.channel.readOutbound()).release();
        assertEquals(1, play.get());
        assertEquals(1, configuration.get());
    }

    @Test
    void stateCallbacksObserveCapturedStageAndCancellationKeepsConnectionOpen() {
        this.user.setConnectionState(ConnectionState.LOGIN);
        PacketType type = PacketTypes.Login.Serverbound.LOGIN_ACKNOWLEDGED;
        this.manager.listenByteBuf(type, (u, e) -> {
            assertEquals(ConnectionState.LOGIN, e.state);
            assertEquals(ConnectionState.CONFIGURATION, u.decoderState());
            e.cancel();
        });
        this.manager.listenByteBuf(type, (u, e) -> fail("cancelled chain continued"));
        ByteBuf frame = this.frame(type, 0);
        assertFalse(this.channel.writeInbound(frame));
        assertTrue(this.channel.isOpen());
        assertEquals(0, frame.refCnt());
        assertEquals(ConnectionState.CONFIGURATION, this.user.decoderState());
    }

    @Test
    void bypassSkipsStateListenersTogetherWithTheRestOfTheChain() {
        this.manager.listenByteBuf(PacketTypes.Play.Clientbound.START_CONFIGURATION, (u, e) -> fail());
        this.manager.sendByteBuf(this.user, this.frame(PacketTypes.Play.Clientbound.START_CONFIGURATION, 0));
        assertEquals(ConnectionState.PLAY, this.user.encoderState());
        ByteBuf sent = this.channel.readOutbound();
        assertNotNull(sent);
        sent.release();
    }

    @Test
    void statePacketsCanBeRewrittenAndObservedByLaterCallbacks() {
        PacketType type = PacketTypes.Play.Serverbound.CONFIGURATION_ACKNOWLEDGED;
        this.manager.listenByteBuf(type, (u, e) -> {
            assertEquals(ConnectionState.CONFIGURATION, u.decoderState());
            assertFalse(e.buffer().isReadOnly());
            e.buffer().clear();
            e.buffer().writeVarInt(99);
        });
        this.manager.listenByteBuf(type, (u, e) -> assertEquals(99, e.buffer().readVarInt()));
        assertTrue(this.channel.writeInbound(this.frame(type, 0)));
        ByteBuf result = this.channel.readInbound();
        assertEquals(this.manager.packetIds().id(type), PacketBuf.readVarInt(result));
        assertEquals(99, PacketBuf.readVarInt(result));
        assertFalse(result.isReadable());
        assertTrue(this.channel.isOpen());
        result.release();
    }

    @Test
    void readOnlyFailureOnStatePacketsContinuesTheChain() {
        PacketType type = PacketTypes.Play.Serverbound.CONFIGURATION_ACKNOWLEDGED;
        this.manager.listenByteBuf(type, (u, e) -> {
            e.buffer().readVarInt();
            throw new IllegalStateException("read failure");
        });
        AtomicInteger calls = new AtomicInteger();
        this.manager.listenByteBuf(type, (u, e) -> calls.incrementAndGet());
        assertTrue(this.channel.writeInbound(this.frame(type, 0)));
        ((ByteBuf) this.channel.readInbound()).release();
        assertEquals(1, calls.get());
        assertTrue(this.channel.isOpen());
    }

    @Test
    void writeFailureOnStatePacketsDropsTheFrameWithoutClosingTheConnection() {
        PacketType type = PacketTypes.Play.Serverbound.CONFIGURATION_ACKNOWLEDGED;
        this.manager.listenByteBuf(type, (u, e) -> {
            e.buffer().setByte(0, 99);
            throw new IllegalStateException("write failure");
        });
        this.manager.listenByteBuf(type, (u, e) -> fail("failed chain continued"));
        ByteBuf frame = this.frame(type, 0);
        assertThrows(IllegalStateException.class, () -> this.channel.writeInbound(frame));
        assertNull(this.channel.readInbound());
        assertEquals(0, frame.refCnt());
        assertTrue(this.channel.isOpen());
    }
}
