package net.momirealms.sparrow.ui.network;

import io.netty.buffer.Unpooled;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteBufPacketEventTest {

    private final List<ByteBuf> buffers = new ArrayList<>();

    @AfterEach
    void releaseBuffers() {
        this.buffers.forEach(ByteBuf::release);
    }

    private ByteBufPacketEvent event(int packetId, int firstValue, int secondValue) {
        PacketBuf buffer = new PacketBuf(Unpooled.buffer());
        this.buffers.add(buffer.source());
        buffer.writeVarInt(packetId);
        int payloadIndex = buffer.writerIndex();
        buffer.writeVarInt(firstValue);
        buffer.writeVarInt(secondValue);
        buffer.readerIndex(payloadIndex);
        return new ByteBufPacketEvent(packetId, ConnectionState.PLAY, PacketFlow.SERVERBOUND, buffer.source(), payloadIndex);
    }

    @Test
    void repeatedBufferLookupKeepsTheCursorAndNextListenerRestartsIt() {
        ByteBufPacketEvent event = event(9, 11, 22);

        assertEquals(11, event.buffer().readVarInt());
        assertEquals(22, event.buffer().readVarInt());
        PacketBuf buffer = event.buffer();
        assertFalse(buffer.isReadable());
        event.begin();
        assertEquals(0, event.buffer().readerIndex());
        assertEquals(11, event.buffer().readVarInt());
        org.junit.jupiter.api.Assertions.assertSame(buffer, event.buffer());
        assertFalse(event.changed());
    }

    @Test
    void readsFieldsInOrderWhileHoldingTheSameBuffer() {
        PacketBuf buffer = event(9, 11, 22).buffer();

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
        event.buffer().setByte(0, 33);
        event.cancel();

        assertTrue(event.changed());
        assertTrue(event.cancelled());
        event.begin();
        event.cancel();
        assertTrue(event.changed());
        assertTrue(event.cancelled());
    }
}
