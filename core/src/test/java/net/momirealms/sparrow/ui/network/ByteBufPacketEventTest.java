package net.momirealms.sparrow.ui.network;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteBufPacketEventTest {

    private static ByteBufPacketEvent event(int packetId, int firstValue, int secondValue) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        buffer.writeVarInt(packetId);
        int payloadIndex = buffer.writerIndex();
        buffer.writeVarInt(firstValue);
        buffer.writeVarInt(secondValue);
        buffer.readerIndex(payloadIndex);
        return new ByteBufPacketEvent(packetId, buffer, payloadIndex);
    }

    @Test
    void everyBufferLookupRestartsAtThePayload() {
        ByteBufPacketEvent event = event(9, 11, 22);

        assertEquals(11, event.getBuffer().readVarInt());
        assertEquals(11, event.getBuffer().readVarInt(), "再次取 buffer 应当重新从 payload 起点读");
        PacketBuf buffer = event.getBuffer();
        buffer.readVarInt();
        buffer.readVarInt();

        assertEquals(11, event.getBuffer().readVarInt(), "读到帧尾之后再取 buffer 仍应复位");
    }

    @Test
    void readsFieldsInOrderWhileHoldingTheSameBuffer() {
        PacketBuf buffer = event(9, 11, 22).getBuffer();

        assertEquals(11, buffer.readVarInt());
        assertEquals(22, buffer.readVarInt());
    }

    @Test
    void keepsThePacketIdItWasRoutedBy() {
        assertEquals(9, event(9, 11, 22).packetId());
    }

    @Test
    void startsNeitherChangedNorCancelled() {
        ByteBufPacketEvent event = event(9, 11, 22);

        assertFalse(event.changed());
        assertFalse(event.cancelled());
    }

    @Test
    void carriesBackWhatTheListenerDeclared() {
        ByteBufPacketEvent event = event(9, 11, 22);
        event.changed(true);
        event.cancelled(true);

        assertTrue(event.changed());
        assertTrue(event.cancelled());
        event.changed(false);
        event.cancelled(false);

        assertFalse(event.changed());
        assertFalse(event.cancelled());
    }
}
