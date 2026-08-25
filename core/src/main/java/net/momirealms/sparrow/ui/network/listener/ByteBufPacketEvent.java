package net.momirealms.sparrow.ui.network.listener;

import net.momirealms.sparrow.ui.network.PacketBuf;
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

    /**
     * 返回监听器是否改写过这一帧.
     *
     * @return 改写过时为 true
     */
    public boolean changed() {
        return this.changed;
    }

    /**
     * 声明这一帧已被改写.
     *
     * <p>改写要从 {@link #getBuffer()} 起完整重写整帧, 包含开头的包 ID; 未声明时框架会把读写指针还原成进入监听器之前的样子.
     * 声明之后帧的内容以 buffer 当前的可读区间为准, 因此改写结束时不要把读指针留在帧尾, 那会被当作取消而丢弃整帧.</p>
     *
     * @param changed 是否已改写
     */
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
