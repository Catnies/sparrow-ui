package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.util.QuadConsumer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * 使用原版附魔台界面的 Window.
 * <p>上部 GUI 的两个 raw slot 分别为待附魔物品和青金石展示位. 三个附魔选项由
 * container data slot 投影到客户端, 选择只发布事件, 不执行原版附魔、经验或物品消耗.
 */
public interface EnchantmentWindow extends Window {

    /**
     * 创建使用 2x1 上部 GUI、真实玩家背包和三个禁用选项的 Builder.
     *
     * @return 附魔台 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new EnchantmentWindowImpl.BuilderImpl();
    }

    /**
     * 设置一个附魔选项; null 表示禁用该按钮.
     *
     * @param index 选项索引, 范围为 [0, 3)
     * @param option 新选项或 null
     */
    void setOption(int index, @Nullable EnchantOption option);

    /**
     * 返回最近已在玩家实体线程应用的选项.
     *
     * @param index 选项索引, 范围为 [0, 3)
     * @return 当前选项或 null
     */
    @Nullable
    EnchantOption getOption(int index);

    /**
     * 设置只影响客户端符文文字的随机种子.
     *
     * @param seed 附魔种子
     */
    void setEnchantmentSeed(int seed);

    /**
     * 返回最近已在玩家实体线程应用的附魔种子.
     *
     * @return 附魔种子
     */
    int getEnchantmentSeed();

    /**
     * 替换选择处理器. 每个处理器依次接收玩家、Window、选项索引和点击时的选项快照.
     *
     * @param handlers 新处理器列表
     */
    void setEnchantSelectionHandlers(@NotNull List<? extends QuadConsumer<Player, EnchantmentWindow, Integer, EnchantOption>> handlers);

    /**
     * 返回当前选择处理器快照.
     *
     * @return 不可修改的处理器列表
     */
    @NotNull
    @Unmodifiable
    List<QuadConsumer<Player, EnchantmentWindow, Integer, EnchantOption>> getEnchantSelectionHandlers();

    /**
     * 追加选择处理器. 处理器依次接收玩家、Window、选项索引和点击时的选项快照.
     *
     * @param handler 新处理器
     */
    void addEnchantSelectionHandler(@NotNull QuadConsumer<Player, EnchantmentWindow, Integer, EnchantOption> handler);

    /**
     * 移除首个匹配的选择处理器.
     *
     * @param handler 待移除的处理器
     */
    void removeEnchantSelectionHandler(@NotNull QuadConsumer<Player, EnchantmentWindow, Integer, EnchantOption> handler);

    /**
     * 一个附魔按钮的客户端展示数据.
     *
     * @param cost 客户端显示和校验的经验等级, 必须至少为 1
     * @param clue tooltip 使用的附魔, null 表示不显示 tooltip
     * @param clueLevel tooltip 使用的附魔等级
     */
    record EnchantOption(int cost, @Nullable Enchantment clue, int clueLevel) {
    }

    /**
     * 附魔台 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<EnchantmentWindow, Builder> {

        /**
         * 设置必须为 2x1 的上部 GUI.
         *
         * @param upperGui 待附魔物品和青金石展示 GUI
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
         * 设置一个初始附魔选项; null 表示禁用该按钮.
         *
         * @param index 选项索引, 范围为 [0, 3)
         * @param option 初始选项或 null
         * @return 此 Builder
         */
        @NotNull
        Builder setOption(int index, @Nullable EnchantOption option);

        /**
         * 设置初始附魔种子.
         *
         * @param seed 附魔种子
         * @return 此 Builder
         */
        @NotNull
        Builder setEnchantmentSeed(int seed);

        /**
         * 替换初始选择处理器. 每个处理器依次接收玩家、Window、选项索引和点击时的选项快照.
         *
         * @param handlers 新处理器列表
         * @return 此 Builder
         */
        @NotNull
        Builder setEnchantSelectionHandlers(
                @NotNull List<? extends QuadConsumer<Player, EnchantmentWindow, Integer, EnchantOption>> handlers
        );

        /**
         * 追加初始选择处理器. 处理器依次接收玩家、Window、选项索引和点击时的选项快照.
         *
         * @param handler 新处理器
         * @return 此 Builder
         */
        @NotNull
        Builder addEnchantSelectionHandler(
                @NotNull QuadConsumer<Player, EnchantmentWindow, Integer, EnchantOption> handler
        );

        /**
         * {@inheritDoc}
         */
        @Override
        @NotNull
        Builder clone();
    }
}
