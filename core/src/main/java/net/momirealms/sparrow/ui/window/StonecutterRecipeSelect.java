package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家从原生切石机配方列表中选择一个选项时产生的事件.
 *
 * <p>选择本身不会计算或写入真实结果槽. 处理器可依据 {@link #selectedOption()} 更新
 * {@link StonecutterWindow} 上部 GUI 的结果槽.</p>
 *
 * @param player 选择配方的玩家
 * @param window 所属切石机 Window
 * @param previousIndex 选择前的索引, -1 表示此前未选择
 * @param selectedIndex 本次选择的索引
 * @param previousOption 之前选择对应的显示选项, null代表未选择
 * @param selectedOption 本次选择对应的显示选项
 */
public record StonecutterRecipeSelect(
        @NotNull Player player,
        @NotNull StonecutterWindow window,
        int previousIndex,
        int selectedIndex,
        @Nullable StonecutterRecipeOption previousOption,
        @NotNull StonecutterRecipeOption selectedOption
) {
}
