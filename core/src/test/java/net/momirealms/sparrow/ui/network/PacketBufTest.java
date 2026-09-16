package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketBufTest {

    private static PacketBuf buffer() {
        return new PacketBuf(Unpooled.buffer());
    }

    @Test
    void readsBackEveryVarIntBoundary() {
        int[] values = {0, 1, 127, 128, 255, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int value : values) {
            PacketBuf buffer = buffer();
            buffer.writeVarInt(value);

            assertEquals(value, buffer.readVarInt(), "VarInt 往返失败: " + value);
        }
    }

    @Test
    void reportsEncodedSizeThatMatchesWrittenBytes() {
        int[] values = {0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int value : values) {
            PacketBuf buffer = buffer();
            buffer.writeVarInt(value);

            assertEquals(buffer.readableBytes(), PacketBuf.getVarIntSize(value), "长度估算与实际写入不一致: " + value);
        }
    }

    @Test
    void rejectsVarIntLongerThanFiveBytes() {
        PacketBuf buffer = buffer();
        for (int index = 0; index < 5; index++) {
            buffer.writeByte(0x80);
        }
        buffer.writeByte(0x01);

        assertThrows(DecoderException.class, buffer::readVarInt);
    }

    @Test
    void readsBackEveryVarLongBoundary() {
        long[] values = {0L, 1L, 127L, 128L, 2147483647L, 2147483648L, Long.MAX_VALUE, -1L, Long.MIN_VALUE};
        for (long value : values) {
            PacketBuf buffer = buffer();
            buffer.writeVarLong(value);

            assertEquals(value, buffer.readVarLong(), "VarLong 往返失败: " + value);
        }
    }

    @Test
    void rejectsVarLongLongerThanTenBytes() {
        PacketBuf buffer = buffer();
        for (int index = 0; index < 10; index++) {
            buffer.writeByte(0x80);
        }
        buffer.writeByte(0x01);

        assertThrows(DecoderException.class, buffer::readVarLong);
    }

    @Test
    void readsBackUtfIncludingMultiByteCharacters() {
        PacketBuf buffer = buffer();
        buffer.writeUtf("菜单 · menu ✔");

        assertEquals("菜单 · menu ✔", buffer.readUtf());
    }

    @Test
    void rejectsUtfDeclaringMoreBytesThanTheCharacterLimitAllows() {
        PacketBuf buffer = buffer();
        buffer.writeVarInt(4);
        buffer.writeBytes("abcd".getBytes(StandardCharsets.UTF_8));

        assertThrows(DecoderException.class, () -> buffer.readUtf(1));
    }

    @Test
    void rejectsUtfDeclaringNegativeLength() {
        PacketBuf buffer = buffer();
        buffer.writeVarInt(-1);

        assertThrows(DecoderException.class, buffer::readUtf);
    }

    @Test
    void rejectsUtfWhoseCharacterCountExceedsTheLimitAfterDecoding() {
        PacketBuf buffer = buffer();
        buffer.writeVarInt(3);
        buffer.writeBytes("abc".getBytes(StandardCharsets.UTF_8));

        assertThrows(DecoderException.class, () -> buffer.readUtf(2));
    }

    @Test
    void rejectsWritingUtfLongerThanTheCharacterLimit() {
        PacketBuf buffer = buffer();

        assertThrows(EncoderException.class, () -> buffer.writeUtf("abc", 2));
    }

    @Test
    void readsBackByteArrayAndUuid() {
        PacketBuf buffer = buffer();
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        buffer.writeByteArray(new byte[]{1, 2, 3});
        buffer.writeUUID(uuid);

        assertArrayEquals(new byte[]{1, 2, 3}, buffer.readByteArray());
        assertEquals(uuid, buffer.readUUID());
    }

    @Test
    void rejectsByteArrayLongerThanTheGivenLimit() {
        PacketBuf buffer = buffer();
        buffer.writeByteArray(new byte[]{1, 2, 3});

        assertThrows(DecoderException.class, () -> buffer.readByteArray(2));
    }

    @Test
    void delegatesIndexAndSliceOperationsToTheWrappedBuffer() {
        ByteBuf source = Unpooled.buffer();
        PacketBuf buffer = new PacketBuf(source);
        buffer.writeVarInt(7);
        buffer.writeInt(42);

        assertSame(source, buffer.source());
        assertEquals(source.writerIndex(), buffer.writerIndex());
        buffer.readerIndex(1);

        assertEquals(1, source.readerIndex());
        assertEquals(42, buffer.readInt());
        buffer.setIndex(0, source.writerIndex());

        assertEquals(7, buffer.readVarInt());
        assertEquals(4, buffer.readSlice(4).readableBytes());
        buffer.clear();

        assertTrue(source.readableBytes() == 0);
    }
}
