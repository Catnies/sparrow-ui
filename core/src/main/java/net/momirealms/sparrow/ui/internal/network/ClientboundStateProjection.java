package net.momirealms.sparrow.ui.internal.network;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 表示某个 Window 临时改写了客户端会一直记住的内容.
 * <p>普通的菜单包会在界面关闭后自然失效, 但是切石机等菜单的配方缓存会留在
 * 客户端里, 需要在最后一个 Window 关闭时发回正确的原版数据.
 */
public interface ClientboundStateProjection extends ClientboundPacketFilter {

    /**
     * 给这份客户端内容返回一个稳定的标识.
     *
     * <p>旧 Window 关闭时, 如果新 Window 返回相等的标识, 说明新 Window 已接手这份内容,
     * 旧 Window 就不应再把原版数据发回去.
     *
     * @return 客户端状态标识
     */
    @NotNull
    Object stateKey();

    /**
     * 把客户端应恢复的原版数据包加入发送列表.
     * <p>只有没有新 Window 接手这份内容时才会调用. 调用方会在旧菜单关闭后统一发送这些包.
     *
     * @param player 接收恢复包的玩家
     * @param packets 本次关闭要发送的数据包列表
     */
    void appendNativeRestore(@NotNull Player player, @NotNull List<Object> packets);
}
