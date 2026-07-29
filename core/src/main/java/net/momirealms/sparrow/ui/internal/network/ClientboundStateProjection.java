package net.momirealms.sparrow.ui.internal.network;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 表示某个 Window 临时改写了客户端会一直记住的内容.
 * <p>普通的菜单包会在界面关闭后自然失效, 但是切石机等菜单的配方缓存会留在
 * 客户端里, 需要在最后一个 Window 关闭时发回正确的原版数据.
 */
public interface ClientboundStateProjection extends ClientboundPacketFilter {

    /**
     * 判断新 Window 是否复用了当前投影实例.
     *
     * @param successorFilter 新会话声明的拦包规则; 没有新会话时为 null
     * @return 新会话继续维护当前投影时返回 true
     */
    default boolean continuedBy(@Nullable ClientboundPacketFilter successorFilter) {
        return successorFilter == this;
    }

    /**
     * 创建客户端应恢复的原版数据包.
     * <p>只有新 Window 没有复用当前投影实例时才会调用. 调用方会在旧菜单关闭后发送这个包.
     *
     * @return 恢复客户端状态的原版数据包
     */
    @NotNull
    Object createNativeRestorePacket();
}
