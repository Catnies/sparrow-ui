package net.momirealms.sparrow.ui.window.handle;

import net.momirealms.sparrow.ui.window.MerchantWindow;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@ApiStatus.Internal
public interface MerchantMenuHandle extends MenuHandle {

    /**
     * 设置商人等级.
     *
     * @param level 0 到 5 的等级
     */
    void setLevel(int level);

    /**
     * 设置经验条进度.
     *
     * @param progress -1.0 或 0.0 到 1.0
     */
    void setProgress(double progress);

    /**
     * 设置补货提示状态.
     *
     * @param enabled 是否显示补货提示
     */
    void setRestockMessageEnabled(boolean enabled);

    /**
     * 事务性替换本次菜单显示的交易快照.
     *
     * @param trades 有序交易快照
     */
    void setTrades(@NotNull List<MerchantWindow.Trade> trades);

    /**
     * 纠正客户端选择交易时, 自动将付款槽的物品返还至背包产生的预测.
     */
    void invalidateClientContents();

    /**
     * 检查是否存在尚未发送的 offers revision.
     * <p>调用只读取刷新状态, 不渲染或发送数据包.
     *
     * @return 是否需要进入本 tick 的菜单同步批次
     */
    boolean tickOffers();
}
