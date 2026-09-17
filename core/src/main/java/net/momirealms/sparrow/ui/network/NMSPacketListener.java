package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Experimental
public interface NMSPacketListener {

    /**
     * 客户端发来的包对象进入框架前经过这里.
     *
     * @param user 数据包所属连接
     * @param event 当前对象层事件, 出站 bundle 里的子包与根包共用同一个
     * @param packet 当前派发到监听器的 NMS 包对象
     */
    default void onPacketReceive(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
    }

    /**
     * 服务器要发给客户端的包对象经过这里, 这时它可以被替换或整个拦下.
     *
     * @param user 数据包所属连接
     * @param event 当前对象层事件, 出站 bundle 里的子包与根包共用同一个
     * @param packet 当前派发到监听器的 NMS 包对象
     */
    default void onPacketSend(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
    }
}
