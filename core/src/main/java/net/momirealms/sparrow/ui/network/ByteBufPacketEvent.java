package net.momirealms.sparrow.ui.network;

import io.netty.buffer.ByteBuf;
import net.momirealms.sparrow.ui.network.packet.ConnectionState;
import net.momirealms.sparrow.ui.network.packet.PacketFlow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ByteBufPacketEvent {
    public final int packetId;
    public final ConnectionState state;
    public final PacketFlow flow;
    private final ByteBuf source;
    private final int payloadStart; // 原始帧中包 ID 之后的位置, 重写时保留此前的字节
    private @Nullable PacketBuf buffer;
    private @Nullable PacketPayloadBuf payload;
    private boolean changed;        // 整条链累计执行过写操作
    private boolean cancelled;
    private boolean writing;        // 当前节点执行过写操作, 决定它失败时整帧是否必须丢弃

    ByteBufPacketEvent(int packetId, ConnectionState state, PacketFlow flow, ByteBuf source, int payloadStart) {
        this.packetId = packetId;
        this.state = state;
        this.flow = flow;
        this.source = source;
        this.payloadStart = payloadStart;
    }

    public int packetId() {
        return this.packetId;
    }

    /**
     * 返回以 payload 为下标 0 的共享缓冲, 重复获取保持当前读取位置.
     * <p>每个监听器开始时读取位置复位为 0. clear() 只清空 payload, 写操作自动标记修改.
     * <p><strong>缓冲及共享派生视图仅在当前回调内借用, 不得 retain/release 或保存到回调之外.</strong>
     * NIO 视图只读, 不暴露数组或内存地址; copy() 返回的独立缓冲由调用方负责释放.
     * @return 惰性创建、整条链复用的 payload 缓冲
     */
    @NotNull
    public PacketBuf buffer() {
        if (this.buffer == null) {
            this.payload = new PacketPayloadBuf(this.source, this.payloadStart, this);
            this.buffer = new PacketBuf(this.payload);
        }
        return this.buffer;
    }

    public boolean changed() {
        return this.changed;
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    /** 取消整帧并终止后续节点. */
    public void cancel() {
        this.cancelled = true;
    }

    void begin() {
        // changed 和 cancelled 是整帧累计状态, 当前节点的写操作重新开始记录.
        this.writing = false;
        if (this.payload != null) {
            this.payload.begin(this.source.writerIndex() - this.payloadStart);
        }
    }

    // 所有共享视图的实际写入都会回到这里, 在字节或长度改变前记录当前节点可能修改过内容.
    void beforeWrite() {
        this.writing = true;
        this.changed = true;
    }

    boolean writing() {
        return this.writing;
    }

    // payload 的读写下标独立于原帧, 成功回调后只提交它的写位置, 保留包 ID 前缀.
    int frameEnd() {
        return this.payload == null ? this.source.writerIndex() : this.payloadStart + this.payload.writerIndex();
    }
}
