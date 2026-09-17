package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.MerchantWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/**
 * 给各种 Window 类型建还没打开的协议菜单.
 * <p><strong>创建和之后所有 MenuHandle 调用都必须在玩家实体线程上做.</strong>
 * generation 用来把旧会话迟到的输入丢掉.
 */
@ApiStatus.Internal
public interface MenuFactory {

    /**
     * 给玩家建一个还没打开的普通箱子菜单.
     *
     * @param viewer 菜单的查看者
     * @param rows 顶部箱子行数
     * @param generation 这扇窗所在会话的代际
     * @return 能由 Window 生命周期驱动的菜单句柄
     */
    @NotNull MenuHandle normal(@NotNull Player viewer, int rows, long generation);

    // 下面这一串都是同形的入口: 给查看者和代际, 换一个对应的菜单类型
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
     * 给玩家建一个还没打开的商人菜单.
     *
     * @param viewer 菜单的查看者
     * @param generation 这扇窗所在会话的代际
     * @param window 渲染交易物品时用的那扇窗
     * @param reporter 渲染和清理失败往哪儿报
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
