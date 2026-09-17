package net.momirealms.sparrow.ui.network;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Experimental
public interface ByteBufPacketListener {

    /**
     * 客户端发往服务器的帧来到监听器手上.
     *
     * @param user 数据包所属连接
     * @param event 当前数据包
     */
    default void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
    }

    /**
     * 服务器要发给客户端的帧经过监听器, 这时它可以被改写或整个拦下.
     *
     * @param user 数据包所属连接
     * @param event 当前数据包
     */
    default void onPacketSend(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
    }
}
