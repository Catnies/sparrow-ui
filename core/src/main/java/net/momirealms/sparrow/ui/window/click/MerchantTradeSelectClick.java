package net.momirealms.sparrow.ui.window.click;

import net.momirealms.sparrow.ui.window.MerchantWindow;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 选择村民交易 Window 左侧栏位时产生的事件.
 *
 * @param player 选择交易的玩家
 * @param window 所属 MerchantWindow
 * @param previousIndex 选择前的索引, -1 表示此前未选择
 * @param selectedIndex 本次选择的索引
 * @param previousTrade 之前索引在本次入口快照中对应的 Trade
 * @param selectedTrade 本次选择的 Trade
 */
public record MerchantTradeSelectClick(
        @NotNull Player player,
        @NotNull MerchantWindow window,
        int previousIndex,
        int selectedIndex,
        @Nullable MerchantWindow.Trade previousTrade,
        @NotNull MerchantWindow.Trade selectedTrade
) {
}
