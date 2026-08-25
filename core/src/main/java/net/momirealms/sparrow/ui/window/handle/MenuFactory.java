package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.MerchantWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/**
 * 为具体 Window 类型创建尚未打开的协议菜单.
 * <p><strong>创建和后续 MenuHandle 调用必须在玩家实体线程执行.</strong> generation 用于丢弃旧会话的迟到输入.
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

    @NotNull MenuHandle hopper(@NotNull Player viewer, long generation);

    @NotNull AnvilMenuHandle anvil(@NotNull Player viewer, long generation);

    @NotNull
    MenuHandle dispenser(@NotNull Player viewer, long generation);

    @NotNull
    MenuHandle dropper(@NotNull Player viewer, long generation);

    @NotNull
    MenuHandle grindstone(@NotNull Player viewer, long generation);

    @NotNull
    MenuHandle smithing(@NotNull Player viewer, long generation);

    @NotNull
    BrewingMenuHandle brewing(@NotNull Player viewer, long generation);

    @NotNull
    CartographyMenuHandle cartography(@NotNull Player viewer, long generation);

    @NotNull
    CrafterMenuHandle crafter(@NotNull Player viewer, long generation);

    @NotNull
    RecipeBookMenuHandle crafting(@NotNull Player viewer, long generation);

    @NotNull
    FurnaceMenuHandle furnace(@NotNull Player viewer, long generation);

    @NotNull
    FurnaceMenuHandle smoker(@NotNull Player viewer, long generation);

    @NotNull
    FurnaceMenuHandle blastFurnace(@NotNull Player viewer, long generation);

    @NotNull
    EnchantmentMenuHandle enchantment(@NotNull Player viewer, long generation);

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
