package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.window.click.MerchantTradeSelectClick;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

public interface MerchantWindow extends Window {

    /**
     * 设置商人等级.
     *
     * @param level 0 到 5 的等级
     */
    void setLevel(int level);

    /**
     * 返回最近一次已应用的商人等级.
     *
     * @return 0 到 5 的等级
     */
    int getLevel();

    /**
     * 设置经验条进度.
     *
     * @param progress -1.0 表示隐藏, 否则必须位于 0.0 到 1.0
     */
    void setProgress(double progress);

    /**
     * 返回最近一次已应用的经验条进度.
     *
     * @return -1.0 或 0.0 到 1.0
     */
    double getProgress();

    /**
     * 设置禁用交易箭头是否显示补货提示.
     *
     * @param enabled 是否显示补货提示
     */
    void setRestockMessageEnabled(boolean enabled);

    /**
     * 返回补货提示是否启用.
     *
     * @return 启用时为 true
     */
    boolean isRestockMessageEnabled();

    /**
     * 替换原版商人界面的有序展示交易.
     *
     * @param trades 新交易快照
     */
    void setTrades(@NotNull List<? extends Trade> trades);

    /**
     * 返回最近一次已应用的交易快照.
     *
     * @return 不可修改的有序交易列表
     */
    @NotNull
    @Unmodifiable
    List<Trade> getTrades();

    /**
     * 替换玩家改变交易选择时调用的处理器.
     *
     * @param handlers 新处理器列表
     */
    void setTradeSelectionHandlers(@NotNull List<? extends Consumer<? super MerchantTradeSelectClick>> handlers);

    /**
     * 返回交易选择处理器快照.
     *
     * @return 不可修改的处理器列表
     */
    @NotNull
    @Unmodifiable
    List<Consumer<MerchantTradeSelectClick>> getTradeSelectionHandlers();

    /**
     * 添加交易选择处理器.
     *
     * @param handler 要添加的处理器
     */
    void addTradeSelectionHandler(@NotNull Consumer<? super MerchantTradeSelectClick> handler);

    /**
     * 移除一个与给定对象相等的交易选择处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeTradeSelectionHandler(@NotNull Consumer<? super MerchantTradeSelectClick> handler);

    /**
     * 商人界面中的一项纯展示交易.
     */
    sealed interface Trade permits MerchantWindowImpl.TradeImpl {

        /**
         * 创建 Trade Builder.
         *
         * @return 新 Builder
         */
        @NotNull
        static Trade.Builder builder() {
            return new MerchantWindowImpl.TradeImpl.BuilderImpl();
        }

        /**
         * 返回第一输入 Item.
         *
         * @return 构建时固定的 Item
         */
        @NotNull
        Item getFirstInput();

        /**
         * 返回第二输入 Item.
         *
         * @return 构建时固定的 Item
         */
        @NotNull
        Item getSecondInput();

        /**
         * 返回结果 Item.
         *
         * @return 构建时固定的 Item
         */
        @NotNull
        Item getResult();

        /**
         * 返回第一输入的展示折扣.
         *
         * @return 正数表示折扣, 负数表示加价
         */
        int getDiscount();

        /**
         * 设置第一输入的展示折扣.
         *
         * @param discount 正数表示折扣, 负数表示加价
         */
        void setDiscount(int discount);

        /**
         * 返回交易是否显示为可用.
         *
         * @return 可用时为 true
         */
        boolean isAvailable();

        /**
         * 设置交易是否显示为可用.
         *
         * @param available 可用状态
         */
        void setAvailable(boolean available);

        /**
         * 订阅折扣与可用状态的变化.
         * <p>订阅由 Trade 保活, 直到凭证被显式关闭, 见 {@link Observer}.
         *
         * @param observer 收到 {@link TradeChange} 的观察者
         * @return 订阅凭证, 用于显式退订
         */
        @NotNull
        Subscription subscribe(@NotNull Observer<? super TradeChange> observer);

        /**
         * Trade 的可重复使用的 Builder.
         */
        sealed interface Builder permits MerchantWindowImpl.TradeImpl.BuilderImpl {

            /**
             * 设置第一输入 Item.
             *
             * @param item 第一输入
             * @return 此 Builder
             */
            @NotNull
            Builder setFirstInput(@NotNull Item item);

            /**
             * 设置第二输入 Item.
             *
             * @param item 第二输入
             * @return 此 Builder
             */
            @NotNull
            Builder setSecondInput(@NotNull Item item);

            /**
             * 设置结果 Item.
             *
             * @param item 结果
             * @return 此 Builder
             */
            @NotNull
            Builder setResult(@NotNull Item item);

            /**
             * 设置初始折扣.
             *
             * @param discount 正数表示折扣, 负数表示加价
             * @return 此 Builder
             */
            @NotNull
            Builder setDiscount(int discount);

            /**
             * 设置初始可用状态.
             *
             * @param available 可用状态
             * @return 此 Builder
             */
            @NotNull
            Builder setAvailable(boolean available);

            /**
             * 构建 Trade.
             *
             * @return 新 Trade
             */
            @NotNull
            Trade build();
        }
    }

    /**
     * Trade 可以发布的状态变化.
     */
    enum TradeChange {
        DISCOUNT,
        AVAILABLE
    }

    @NotNull
    static Builder builder() {
        return new MerchantWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<MerchantWindow, Builder> {

        /**
         * 设置必须为 3x1 的上部 Pane.
         *
         * @param upperPane 商人三个协议槽位(raw slot)对应的 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane; null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置初始商人等级.
         *
         * @param level 0 到 5 的等级
         * @return 此 Builder
         */
        @NotNull
        Builder setLevel(int level);

        /**
         * 设置初始经验条进度.
         *
         * @param progress -1.0 表示隐藏, 否则必须位于 0.0 到 1.0
         * @return 此 Builder
         */
        @NotNull
        Builder setProgress(double progress);

        /**
         * 设置初始补货提示状态.
         *
         * @param enabled 是否显示补货提示
         * @return 此 Builder
         */
        @NotNull
        Builder setRestockMessageEnabled(boolean enabled);

        /**
         * 设置初始交易列表.
         *
         * @param trades 有序交易快照
         * @return 此 Builder
         */
        @NotNull
        Builder setTrades(@NotNull List<? extends Trade> trades);

        /**
         * 替换初始交易选择处理器.
         *
         * @param handlers 新处理器列表
         * @return 此 Builder
         */
        @NotNull
        Builder setTradeSelectionHandlers(@NotNull List<? extends Consumer<? super MerchantTradeSelectClick>> handlers);

        /**
         * 添加初始交易选择处理器.
         *
         * @param handler 要添加的处理器
         * @return 此 Builder
         */
        @NotNull
        Builder addTradeSelectionHandler(@NotNull Consumer<? super MerchantTradeSelectClick> handler);

        @Override
        @NotNull
        Builder clone();
    }
}
