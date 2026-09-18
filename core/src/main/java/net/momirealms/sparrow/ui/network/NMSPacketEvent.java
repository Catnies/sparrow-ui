package net.momirealms.sparrow.ui.network;

import io.netty.util.ReferenceCountUtil;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.PacketProxy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 整个根包的同步处理结果, bundle 叶子包共用此事件. */
public final class NMSPacketEvent {
    public final Object packet;
    private @Nullable Object replacement;
    private boolean cancelled;
    private @Nullable Throwable failure;

    NMSPacketEvent(@NotNull Object packet) {
        this.packet = packet;
    }

    /**
     * 接管替换包并终止当前链和剩余 bundle 叶子, 最终仅发送最后一次接受的替换包.
     * <p><strong>替换包必须适用于当前协议阶段和方向; 接受后其所有权交给框架.</strong>
     * @param replacement 替换整个根包的 NMS 对象
     * @throws IllegalArgumentException 替换对象不是 NMS 包
     */
    public void replaceRootAndStop(@NotNull Object replacement) {
        if (!PacketProxy.CLASS.isInstance(replacement)) {
            throw new IllegalArgumentException("Replacement must be an NMS packet");
        }
        Object previous = this.replacement;
        this.replacement = replacement;
        // 回调可以连续设置替换, 每次消费上一份候选; 原始根由对象桥统一处理.
        if (previous != null && previous != this.packet && previous != replacement) {
            ReferenceCountUtil.release(previous);
        }
    }

    public boolean cancelled() {
        return this.cancelled;
    }

    /** 取消整个根包并终止后续节点与叶子包; 取消优先于替换. */
    public void cancel() {
        this.cancelled = true;
    }

    boolean stopped() {
        return this.cancelled || this.replacement != null || this.failure != null;
    }

    @Nullable
    Object replacement() {
        return this.replacement;
    }

    @Nullable
    Throwable failure() {
        return this.failure;
    }

    void failure(Throwable failure) {
        this.failure = failure;
    }

    void discardReplacement() {
        // 取消或失败时释放已接受的替换, 与原根相同的对象留给桥释放一次.
        if (this.replacement != null && this.replacement != this.packet) {
            ReferenceCountUtil.release(this.replacement);
        }
    }
}
