package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketType;
import net.momirealms.sparrow.ui.network.packet.PacketTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.*;

class NetworkAdapterTest {
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

    private ByteBuf frame() {
        PacketBuf frame = new PacketBuf(Unpooled.buffer());
        frame.writeVarInt(this.manager.packetIds().id(TYPE));
        frame.writeVarInt(42);
        return frame.source();
    }

    @Test
    void forwardsOriginalIdentityAndReferenceCount() {
        ByteBuf frame = this.frame();
        this.channel.writeOutbound(frame);
        assertSame(frame, this.channel.readOutbound());
        assertEquals(1, frame.refCnt());
        assertEquals(0, frame.readerIndex());
        frame.release();
    }

    @Test
    void emptyFramesAreConsumedAndPromisesCompleteSuccessfully() {
        ByteBuf inbound = Unpooled.buffer();
        assertFalse(this.channel.writeInbound(inbound));
        assertEquals(0, inbound.refCnt());
        ByteBuf outbound = Unpooled.buffer();
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(outbound, promise);
        assertTrue(promise.isSuccess());
        assertEquals(0, outbound.refCnt());
    }

    @Test
    void cancelledOutboundCompletesSuccessfullyAndReleasesOnce() {
        this.manager.listenByteBuf(TYPE, (u, e) -> e.cancel());
        ByteBuf frame = this.frame();
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(frame, promise);
        assertTrue(promise.isSuccess());
        assertEquals(0, frame.refCnt());
        assertNull(this.channel.readOutbound());
    }

    @Test
    void failedWriteAccessConsumesFrameAndFailsOriginalPromise() {
        IllegalStateException failure = new IllegalStateException("write failure");
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            e.buffer().setByte(0, 9);
            throw failure;
        });
        ByteBuf frame = this.frame();
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(frame, promise);
        assertSame(failure, promise.cause());
        assertEquals(0, frame.refCnt());
        assertNull(this.channel.readOutbound());
    }

    @Test
    void exceptionAfterCancellationWinsOverSuccessfulCancellation() {
        IllegalStateException failure = new IllegalStateException("cancel failure");
        this.manager.listenByteBuf(TYPE, (u, e) -> {
            e.cancel();
            throw failure;
        });
        ChannelPromise promise = this.channel.newPromise();
        ByteBuf frame = this.frame();
        this.channel.pipeline().writeAndFlush(frame, promise);
        assertSame(failure, promise.cause());
        assertEquals(0, frame.refCnt());
    }

    @Test
    void malformedIdFailsAndReleasesWhileEmptyStateSkipsIdParsing() {
        ByteBuf malformed = Unpooled.buffer().writeBytes(new byte[]{-1, -1, -1, -1, -1, -1});
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(malformed, promise);
        assertFalse(promise.isSuccess());
        assertNotNull(promise.cause());
        assertEquals(0, malformed.refCnt());
        this.user.setConnectionState(ConnectionState.STATUS);
        ByteBuf noRoutes = Unpooled.buffer().writeByte(-1);
        this.channel.writeOutbound(noRoutes);
        assertSame(noRoutes, this.channel.readOutbound());
        assertEquals(0, noRoutes.readerIndex());
        noRoutes.release();
    }

    @Test
    void errorConsumesFrameWithoutContinuingTheChain() {
        AssertionError failure = new AssertionError("fatal");
        this.manager.listenByteBuf(TYPE, (u, e) -> { throw failure; });
        this.manager.listenByteBuf(TYPE, (u, e) -> fail());
        ByteBuf frame = this.frame();
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(frame, promise);
        assertSame(failure, promise.cause());
        assertEquals(0, frame.refCnt());
    }

    @Test
    void downstreamFailureDoesNotCauseAnotherRelease() {
        IllegalStateException failure = new IllegalStateException("downstream");
        this.channel.pipeline().addBefore(this.manager.encoderName, "consume", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ((ByteBuf) message).release();
                promise.setFailure(failure);
            }
        });
        ByteBuf frame = this.frame();
        ChannelPromise promise = this.channel.newPromise();
        this.channel.pipeline().writeAndFlush(frame, promise);
        assertSame(failure, promise.cause());
        assertEquals(0, frame.refCnt());
    }

    @Test
    void nestedByteBufSendsBothVisitListeners() {
        this.channel.pipeline().addLast("nested", new ChannelOutboundHandlerAdapter() {
            private boolean sending;

            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (!this.sending) {
                    this.sending = true;
                    NetworkAdapterTest.this.user.sendByteBuf(NetworkAdapterTest.this.frame());
                    this.sending = false;
                }
                context.write(message, promise);
            }
        });
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        this.manager.listenByteBuf(TYPE, (u, e) -> calls.incrementAndGet());
        this.user.sendByteBuf(this.frame());
        assertEquals(2, calls.get());
        ((ByteBuf) this.channel.readOutbound()).release();
        ((ByteBuf) this.channel.readOutbound()).release();
    }
}
