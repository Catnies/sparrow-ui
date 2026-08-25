package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Experimental
public interface NMSPacketListener {

    /**
     * 处理客户端发往服务器的数据包对象.
     *
     * @param user 数据包所属连接
     * @param event 当前对象层事件
     * @param packet 当前派发到监听器的 NMS 包对象
     */
    default void onPacketReceive(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
    }

    /**
     * 处理服务器发往客户端的数据包对象.
     *
     * @param user 数据包所属连接
     * @param event 当前对象层事件
     * @param packet 当前派发到监听器的 NMS 包对象
     */
    default void onPacketSend(@NotNull NetworkUser user, @NotNull NMSPacketEvent event, @NotNull Object packet) {
    }
}
