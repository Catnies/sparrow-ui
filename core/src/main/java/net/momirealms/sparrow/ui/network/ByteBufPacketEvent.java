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

    /**
     * 拿到这一帧的缓冲, 每次调用都把读指针拨回 payload 开头.
     * <p>所以监听器可以反复从头读 payload, 不用自己记指针.
     *
     * @return 当前帧的缓冲
     */
    @NotNull
    public PacketBuf getBuffer() {
        this.buffer.readerIndex(this.payloadIndex);
        return this.buffer;
    }

    public boolean changed() {
        return this.changed;
    }

    /**
     * 声明这一帧已经改过, 派发方就不再还原读写指针.
     *
     * <p>改写要从 {@link #getBuffer()} 起完整重写整帧, 包 ID 也要一并写回去; 不声明的话框架会把读写指针
     * 还原成进入监听器之前的样子. 声明之后帧的内容取 buffer 当前的可读区间, 所以写完别把读指针留在帧尾,
     * 那看起来跟被取消了一样, 整帧都会被丢掉.
     *
     * @param changed 是否已改写
     */
    public void changed(boolean changed) {
        this.changed = changed;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    /**
     * 取消这一帧, 两个方向都是静默丢掉.
     * <p>入站帧不再传给后面的 handler, 出站帧的写入也算成功, 发起的调用方不会拿到失败.
     *
     * @param cancelled 是否取消
     */
    public void cancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
