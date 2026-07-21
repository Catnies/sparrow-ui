package net.momirealms.sparrow.ui.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 在玩家实体线程创建一个普通箱子菜单.
 */
@FunctionalInterface
@ApiStatus.Internal
public interface MenuFactory {

    /**
     * 为指定玩家创建尚未打开的协议菜单.
     *
     * @param viewer 菜单观察者
     * @param topSlots 顶部箱子槽位数量
     * @param generation 此 Window 会话的代际
     * @param incoming 接收 Netty 入站消息的队列
     * @return 可由 Window 生命周期驱动的菜单句柄
     */
    @NotNull MenuHandle create(
            @NotNull Player viewer,
            int topSlots,
            long generation,
            @NotNull IncomingPacketQueue<MenuInput> incoming
    );
}
