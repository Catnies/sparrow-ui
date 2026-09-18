package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PacketPayloadBufTest {
    private final List<ByteBuf> frames = new ArrayList<>();

    @AfterEach
    void releaseFrames() {
        this.frames.forEach(ByteBuf::release);
    }

    private ByteBufPacketEvent event(ByteBuf frame) {
        this.frames.add(frame);
        frame.writeShort(0x1234);
        new PacketBuf(frame).writeVarInt(300);
        int offset = frame.writerIndex();
        frame.writeInt(0x11223344);
        frame.readerIndex(offset);
        return new ByteBufPacketEvent(300, ConnectionState.PLAY, PacketFlow.SERVERBOUND, frame, offset);
    }

    private ByteBufPacketEvent event() {
        return this.event(Unpooled.buffer(8, 1024));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rewritesAndCompactsHeapDirectAndCompositeFrames(int kind) {
        ByteBuf frame = switch (kind) {
            case 0 -> Unpooled.buffer(8, 1024);
            case 1 -> Unpooled.directBuffer(8, 1024);
            default -> Unpooled.compositeBuffer().addComponents(false, Unpooled.buffer(5).writeZero(5), Unpooled.buffer(3).writeZero(3));
        };
        ByteBufPacketEvent event = this.event(frame);
        PacketBuf payload = event.buffer();
        assertEquals(0, payload.readerIndex());
        assertEquals(4, payload.writerIndex());
        assertEquals(0x11223344, payload.readInt());
        assertFalse(event.changed());
        payload.clear();
        payload.writeUtf("a much longer payload");
        payload.readerIndex(0);
        assertEquals("a much longer payload", payload.readUtf());
        assertTrue(event.changed());
        assertEquals(4 + payload.writerIndex(), event.frameEnd());

        payload.clear();
        payload.writeBytes(new byte[]{10, 20, 30, 40});
        payload.readerIndex(2);
        payload.discardReadBytes();
        assertEquals(0, payload.readerIndex());
        assertEquals(2, payload.writerIndex());
        assertEquals(30, payload.readByte());
        assertEquals(40, payload.readByte());
        payload.capacity(2);
        assertEquals(6, event.frameEnd());
        assertEquals(0x1234, frame.getUnsignedShort(0));
        assertEquals(0xAC, frame.getUnsignedByte(2));
        assertEquals(0x02, frame.getUnsignedByte(3));
    }

    @Test
    void primitiveAndBulkWritesAreTrackedThroughTheSameView() throws IOException {
        ByteBufPacketEvent event = this.event();
        PacketBuf payload = event.buffer();
        payload.clear();
        payload.writeShort(0x1122);
        payload.writeMediumLE(0x334455);
        payload.writeIntLE(0x66778899);
        payload.writeLong(0x1122334455667788L);
        payload.writeBytes(ByteBuffer.wrap(new byte[]{9, 8}));
        payload.writeBytes(new ByteArrayInputStream(new byte[]{7, 6}), 2);
        assertTrue(event.changed());
        payload.readerIndex(0);
        assertEquals(0x1122, payload.readShort());
        assertEquals(0x334455, payload.readUnsignedMediumLE());
        assertEquals(0x66778899, payload.readIntLE());
        assertEquals(0x1122334455667788L, payload.readLong());
        assertEquals(0x09080706, payload.readInt());
    }

    @Test
    @SuppressWarnings("deprecation")
    void sharedViewsTrackWritesWithoutChangingThePayloadLength() {
        ByteBufPacketEvent event = this.event();
        PacketBuf payload = event.buffer();
        ByteBuf slice = payload.slice(1, 2);
        slice.setShort(0, 0x5566);
        assertTrue(event.changed());
        assertEquals(0x11556644, payload.getInt(0));
        assertEquals(4, payload.writerIndex());
        event.begin();
        assertFalse(event.writing());
        ByteBuf duplicate = payload.duplicate();
        duplicate.clear().writeByte(0x77);
        assertTrue(event.writing());
        assertEquals(0x77556644, payload.getInt(0));
        assertEquals(4, payload.writerIndex());
        payload.order(ByteOrder.LITTLE_ENDIAN).setInt(0, 0x12345678);
        assertEquals(0x78563412, payload.getInt(0));
        assertEquals(8, event.frameEnd());
    }

    @Test
    void sourcesUnwrapAndFluentResultsStayInsideThePayload() {
        ByteBufPacketEvent event = this.event();
        PacketBuf payload = event.buffer();
        assertSame(payload.source(), payload.unwrap());
        assertNull(payload.source().unwrap());
        assertSame(payload.source(), payload.readerIndex(0));
        assertSame(payload.source(), payload.setInt(0, 99));
        assertTrue(event.changed());
        assertThrows(IndexOutOfBoundsException.class, () -> payload.getByte(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> payload.slice(-1, 1));
        assertEquals(0x1234, this.frames.getFirst().getUnsignedShort(0));
    }

    @Test
    void nioAndArrayAccessCannotBypassTrackingOrReachTheHeader() {
        ByteBufPacketEvent event = this.event();
        PacketBuf payload = event.buffer();
        for (ByteBuffer nio : new ByteBuffer[]{payload.nioBuffer(), payload.internalNioBuffer(1, 2), payload.nioBuffers()[0]}) {
            assertTrue(nio.isReadOnly());
            assertFalse(nio.hasArray());
            assertThrows(ReadOnlyBufferException.class, () -> nio.put(0, (byte) 99));
        }
        ByteBuffer subrange = payload.internalNioBuffer(1, 2);
        subrange.clear();
        assertEquals(2, subrange.capacity());
        assertEquals(0x2233, subrange.getShort());
        assertFalse(payload.hasArray());
        assertFalse(payload.hasMemoryAddress());
        assertThrows(UnsupportedOperationException.class, payload::array);
        assertThrows(UnsupportedOperationException.class, payload::arrayOffset);
        assertThrows(UnsupportedOperationException.class, payload::memoryAddress);
        assertFalse(event.changed());
    }

    @Test
    void copiesHaveIndependentOwnershipAndSharedViewsAreBorrowed() {
        ByteBufPacketEvent event = this.event();
        PacketBuf payload = event.buffer();
        ByteBuf copy = payload.copy();
        try {
            copy.setInt(0, 99);
            assertEquals(0x11223344, payload.getInt(0));
            assertFalse(event.changed());
        } finally {
            assertTrue(copy.release());
        }
        assertThrows(UnsupportedOperationException.class, payload::retain);
        assertThrows(UnsupportedOperationException.class, payload::release);
        assertThrows(UnsupportedOperationException.class, payload::retainedSlice);
        assertThrows(UnsupportedOperationException.class, () -> payload.duplicate().release());
        assertEquals(1, this.frames.getFirst().refCnt());
    }

    @Test
    void writerMarksAndLengthChangesAreTrackedWhileReaderMarksAreNot() {
        ByteBufPacketEvent event = this.event();
        PacketBuf payload = event.buffer();
        payload.markReaderIndex();
        payload.readByte();
        payload.resetReaderIndex();
        payload.writerIndex(4);
        payload.setIndex(1, 4);
        assertFalse(event.changed());
        payload.markWriterIndex();
        payload.writeByte(5);
        payload.resetWriterIndex();
        assertTrue(event.writing());
        assertEquals(8, event.frameEnd());
        event.begin();
        assertFalse(event.writing());
        assertEquals(0, payload.readerIndex());
        payload.writerIndex(2);
        assertTrue(event.writing());
        assertEquals(6, event.frameEnd());
    }

    @Test
    void partialStreamFailureIsClassifiedAsWriting() {
        ByteBufPacketEvent event = this.event();
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream failed");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                bytes[offset] = 99;
                throw new IOException("partial stream failed");
            }
        };
        assertThrows(IOException.class, () -> event.buffer().setBytes(0, failing, 4));
        assertTrue(event.writing());
    }

    @ParameterizedTest
    @MethodSource("mutations")
    void mutationsAutomaticallyMarkPayloadChanged(Consumer<PacketBuf> mutation) {
        ByteBufPacketEvent event = this.event(Unpooled.buffer(16, 1024));
        PacketBuf payload = event.buffer();
        assertFalse(payload.isReadOnly());
        assertTrue(payload.isWritable());
        assertTrue(payload.isWritable(1));
        mutation.accept(payload);
        assertTrue(event.writing());
        assertTrue(event.changed());
        assertEquals(0x1234, this.frames.getFirst().getUnsignedShort(0));
    }

    static Stream<Consumer<PacketBuf>> mutations() {
        return Stream.of(
                b -> b.clear(),
                b -> b.setByte(0, 1),
                b -> b.setShortLE(0, 1),
                b -> b.setMedium(0, 1),
                b -> b.setInt(0, 1),
                b -> b.writeLong(1),
                b -> b.setBytes(0, new byte[]{1}),
                b -> b.setBytes(0, ByteBuffer.wrap(new byte[]{1})),
                b -> b.setCharSequence(0, "a", StandardCharsets.UTF_8),
                b -> b.setZero(0, 4),
                b -> b.writerIndex(2),
                b -> b.setIndex(0, 2),
                b -> b.capacity(20),
                b -> b.readerIndex(1).discardReadBytes(),
                b -> b.slice().setByte(0, 1),
                b -> b.duplicate().setInt(0, 1)
        );
    }
}
