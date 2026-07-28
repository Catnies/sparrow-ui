package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Observable;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

public interface MerchantWindow extends Window {

    /**
     * 创建使用 3x1 上部 GUI 的 Builder.
     *
     * @return 商人 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new MerchantWindowImpl.BuilderImpl();
    }

    /**
     * 设置商人等级.
     *
     * @param level 0 到 5 的等级
     */
    void setLevel(int level);

    /**
     * 返回最近一次已提交的商人等级.
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
     * 返回最近一次已提交的经验条进度.
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
     * 返回最近一次已提交的交易快照.
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
    void setTradeSelectionHandlers(@NotNull List<? extends Consumer<? super MerchantTradeSelection>> handlers);

    /**
     * 返回交易选择处理器快照.
     *
     * @return 不可修改的处理器列表
     */
    @NotNull
    @Unmodifiable
    List<Consumer<MerchantTradeSelection>> getTradeSelectionHandlers();

    /**
     * 添加交易选择处理器.
     *
     * @param handler 要添加的处理器
     */
    void addTradeSelectionHandler(@NotNull Consumer<? super MerchantTradeSelection> handler);

    /**
     * 移除一个与给定对象相等的交易选择处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeTradeSelectionHandler(@NotNull Consumer<? super MerchantTradeSelection> handler);

    /**
     * 商人界面中的一项纯展示交易.
     */
    sealed interface Trade extends Observable<TradeChange> permits MerchantWindowImpl.TradeImpl {

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
         * Trade 的可重复 Builder.
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

    /**
     * MerchantWindow 的可重复 Builder.
     */
    interface Builder extends Window.Builder<MerchantWindow, Builder> {

        /**
         * 设置必须为 3x1 的上部 GUI.
         *
         * @param upperGui 商人三个真实槽位的 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperGui(@NotNull Gui upperGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示映射玩家真实物品栏.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

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
        Builder setTradeSelectionHandlers(@NotNull List<? extends Consumer<? super MerchantTradeSelection>> handlers);

        /**
         * 添加初始交易选择处理器.
         *
         * @param handler 要添加的处理器
         * @return 此 Builder
         */
        @NotNull
        Builder addTradeSelectionHandler(@NotNull Consumer<? super MerchantTradeSelection> handler);

        @Override
        @NotNull
        Builder clone();
    }
}
