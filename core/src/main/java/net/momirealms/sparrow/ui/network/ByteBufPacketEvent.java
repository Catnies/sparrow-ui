package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Experimental
public final class ByteBufPacketEvent {
    private final int packetId;
    private final PacketBuf buffer;
    private final int payloadIndex;
    private boolean changed;
    private boolean cancelled;

    ByteBufPacketEvent(int packetId, PacketBuf buffer, int payloadIndex) {
        this.packetId = packetId;
        this.buffer = buffer;
        this.payloadIndex = payloadIndex;
    }

    public int packetId() {
        return this.packetId;
    }

    @NotNull
    public PacketBuf getBuffer() {
        this.buffer.readerIndex(this.payloadIndex);
        return this.buffer;
    }

    public boolean changed() {
        return this.changed;
    }

    public void changed(boolean changed) {
        this.changed = changed;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    public void cancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
