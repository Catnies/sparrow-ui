package net.momirealms.sparrow.ui.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * 在玩家实体线程创建由具体 Window 类型明确选择的协议菜单.
 */
@ApiStatus.Internal
public interface MenuFactory {

    /**
     * 为指定玩家创建尚未打开的普通箱子菜单.
     *
     * @param viewer 菜单观察者
     * @param rows 顶部箱子行数
     * @param generation 此 Window 会话的代际
     * @return 可由 Window 生命周期驱动的菜单句柄
     */
    @NotNull MenuHandle createNormal(@NotNull Player viewer, int rows, long generation);

    /**
     * 为指定玩家创建尚未打开的漏斗菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 漏斗菜单句柄
     */
    @NotNull MenuHandle createHopper(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的铁砧菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 铁砧菜单句柄
     */
    @NotNull AnvilMenuHandle createAnvil(@NotNull Player viewer, long generation);
}
