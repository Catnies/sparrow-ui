package net.momirealms.sparrow.ui.network.filter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 给客户端会一直记着的内容做一次临时改写.
 * <p>普通的菜单包关掉界面就跟着失效了, 但切石机这类菜单的配方缓存会留在客户端里,
 * 所以最后一个正在用它的 Window 关闭时要发回一份原版数据, 把客户端上的状态恢复原样.
 */
public interface ClientboundStateProjection extends ClientboundPacketFilter {

    /**
     * 判断新 Window 还在不在用同一个实例维护这份客户端状态.
     *
     * @param successorFilter 新会话声明的拦包规则; 没有新会话时为 null
     * @return 新会话接着维护这份状态时返回 true, 旧菜单关闭时就不用发恢复包
     */
    default boolean continuedBy(@Nullable ClientboundPacketFilter successorFilter) {
        return successorFilter == this;
    }

    /**
     * 造一个客户端收到之后能把状态恢复成原版的数据包.
     * <p>只有新 Window 没有接着维护这份状态时才需要它. 调用方会在旧菜单关闭之后把包发出去.
     *
     * @return 恢复客户端状态的原版数据包
     */
    @NotNull
    Object createNativeRestorePacket();
}
