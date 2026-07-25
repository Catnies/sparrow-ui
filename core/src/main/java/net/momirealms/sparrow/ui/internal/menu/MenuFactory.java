package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.window.MerchantWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

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
    @NotNull MenuHandle normal(@NotNull Player viewer, int rows, long generation);

    /**
     * 为指定玩家创建尚未打开的漏斗菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 漏斗菜单句柄
     */
    @NotNull MenuHandle hopper(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的铁砧菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 铁砧菜单句柄
     */
    @NotNull AnvilMenuHandle anvil(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的发射器菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 发射器菜单句柄
     */
    @NotNull
    MenuHandle dispenser(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的投掷器菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 投掷器菜单句柄
     */
    @NotNull
    MenuHandle dropper(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的砂轮菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 砂轮菜单句柄
     */
    @NotNull
    MenuHandle grindstone(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的锻造台菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 锻造台菜单句柄
     */
    @NotNull
    MenuHandle smithing(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的酿造台菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 酿造台菜单句柄
     */
    @NotNull
    BrewingMenuHandle brewing(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的制图台菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 制图台菜单句柄
     */
    @NotNull
    CartographyMenuHandle cartography(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的合成器菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 合成器菜单句柄
     */
    @NotNull
    CrafterMenuHandle crafter(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的工作台菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 工作台菜单句柄
     */
    @NotNull
    RecipeBookMenuHandle crafting(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的熔炉菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 熔炉菜单句柄
     */
    @NotNull
    FurnaceMenuHandle furnace(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的烟熏炉菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 烟熏炉菜单句柄
     */
    @NotNull
    FurnaceMenuHandle smoker(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的高炉菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 高炉菜单句柄
     */
    @NotNull
    FurnaceMenuHandle blastFurnace(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的切石机菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @return 切石机菜单句柄
     */
    @NotNull
    StonecutterMenuHandle stonecutter(@NotNull Player viewer, long generation);

    /**
     * 为指定玩家创建尚未打开的商人菜单.
     *
     * @param viewer 菜单观察者
     * @param generation 此 Window 会话的代际
     * @param window 渲染 Trade Item 时使用的 Window
     * @param reporter 渲染与清理失败的上报目标
     * @return 商人菜单句柄
     */
    @NotNull
    MerchantMenuHandle merchant(
            @NotNull Player viewer,
            long generation,
            @NotNull MerchantWindow window,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    );
}
