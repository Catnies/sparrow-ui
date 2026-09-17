package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.window.click.EnchantSelectClick;
import net.momirealms.sparrow.ui.pane.Pane;
import org.bukkit.enchantments.Enchantment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

public interface EnchantmentWindow extends Window {

    /**
     * 设置其中一个附魔按钮; 给 null 就是把这个按钮禁掉.
     *
     * @param index 选项索引, 范围为 [0, 3)
     * @param option 新选项, null 表示禁用
     * @throws IndexOutOfBoundsException 索引超出范围时
     */
    void setOption(int index, @Nullable EnchantOption option);

    /**
     * 最近一次在玩家实体线程上应用过的选项.
     *
     * @param index 选项索引, 范围为 [0, 3)
     * @return 选项; 这个按钮没开时是 null
     * @throws IndexOutOfBoundsException 索引超出范围时
     */
    @Nullable
    EnchantOption getOption(int index);

    /**
     * 设置附魔种子, 它只管客户端那几行符文文字怎么长.
     *
     * @param seed 附魔种子
     */
    void setEnchantmentSeed(int seed);

    /**
     * 最近一次在玩家实体线程上应用过的附魔种子.
     *
     * @return 附魔种子
     */
    int getEnchantmentSeed();

    /**
     * 整批换掉附魔选择处理器.
     *
     * @param handlers 新处理器列表
     */
    void setEnchantSelectHandlers(@NotNull List<? extends Consumer<? super EnchantSelectClick>> handlers);

    /**
     * 现在的附魔选择处理器, 给一份快照.
     *
     * @return 不可修改的处理器列表
     */
    @NotNull
    @Unmodifiable
    List<Consumer<EnchantSelectClick>> getEnchantSelectHandlers();

    /**
     * 在附魔选择处理器末尾追加一个.
     *
     * @param handler 新处理器
     */
    void addEnchantSelectHandler(@NotNull Consumer<? super EnchantSelectClick> handler);

    /**
     * 按 equals 摘掉第一个匹配的附魔选择处理器.
     *
     * @param handler 待移除的处理器
     */
    void removeEnchantSelectHandler(@NotNull Consumer<? super EnchantSelectClick> handler);

    /**
     * 一个附魔按钮在客户端上展示的数据.
     *
     * @param cost 客户端显示的等级, 它自己也拿这个校验, <strong>至少为 1</strong>
     * @param clue tooltip 上的附魔; null 表示不出 tooltip
     * @param clueLevel tooltip 上的附魔等级
     */
    record EnchantOption(int cost, @Nullable Enchantment clue, int clueLevel) {
    }

    @NotNull
    static Builder builder() {
        return new EnchantmentWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<EnchantmentWindow, Builder> {

        /**
         * 设置上部 Pane, 待附魔物品和青金石都摆在这上面, 尺寸必须是 2x1.
         *
         * @param upperPane 待附魔物品和青金石展示 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setUpperPane(@NotNull Pane upperPane);

        /**
         * 设置下部那个 9x4 的 Pane, 管玩家物品栏那一片; 给 null 就接玩家的 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置一个初始附魔按钮; 给 null 就是这个按钮一开始就禁用.
         *
         * @param index 选项索引, 范围为 [0, 3)
         * @param option 初始选项, null 表示禁用
         * @return 此 Builder
         * @throws IndexOutOfBoundsException 索引超出范围时
         */
        @NotNull
        Builder setOption(int index, @Nullable EnchantOption option);

        /**
         * 设置一开始的附魔种子.
         *
         * @param seed 附魔种子
         * @return 此 Builder
         */
        @NotNull
        Builder setEnchantmentSeed(int seed);

        /**
         * 整批换掉初始的附魔选择处理器.
         *
         * @param handlers 新处理器列表
         * @return 此 Builder
         */
        @NotNull
        Builder setEnchantSelectHandlers(
                @NotNull List<? extends Consumer<? super EnchantSelectClick>> handlers
        );

        /**
         * 追加一个初始的附魔选择处理器.
         *
         * @param handler 新处理器
         * @return 此 Builder
         */
        @NotNull
        Builder addEnchantSelectHandler(
                @NotNull Consumer<? super EnchantSelectClick> handler
        );

        /**
         * {@inheritDoc}
         */
        @Override
        @NotNull
        Builder clone();
    }
}
