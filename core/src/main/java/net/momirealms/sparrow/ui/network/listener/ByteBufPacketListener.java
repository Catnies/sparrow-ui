package net.momirealms.sparrow.ui.network.listener;

import net.momirealms.sparrow.ui.network.NetworkUser;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Experimental
public interface ByteBufPacketListener {

    /**
     * 处理客户端发往服务器的数据包.
     *
     * @param user 数据包所属连接
     * @param event 当前数据包
     */
    default void onPacketReceive(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
    }

    /**
     * 处理服务器发往客户端的数据包.
     *
     * @param user 数据包所属连接
     * @param event 当前数据包
     */
    default void onPacketSend(@NotNull NetworkUser user, @NotNull ByteBufPacketEvent event) {
    }
}
