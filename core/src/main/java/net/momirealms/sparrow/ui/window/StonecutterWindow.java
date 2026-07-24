package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 使用原版切石机界面的 Window.
 *
 * <p>上部 GUI 的两个真实 raw slot 分别为输入和结果. 左侧配方列表是切石机原生控件,
 * 不属于 Window 槽位, 不参与 {@link #guis()}、{@link #guiAt(int)} 或普通物品点击.
 * Window 只负责提交有序显示选项并报告客户端选择; 真实结果由使用者按业务规则更新.
 * 每次合法客户端选择都会产生事件, 包括再次选择当前索引; 过期索引只会被纠正.</p>
 */
public interface StonecutterWindow extends Window {

    /**
     * 创建使用 2x1 上部 GUI 的 Builder.
     *
     * @return 切石机 Window Builder
     */
    @NotNull
    static Builder builder() {
        return new StonecutterWindowImpl.BuilderImpl();
    }

    /**
     * 替换原生配方列表的全部显示选项.
     *
     * <p>选项按列表顺序取得连续索引, 允许重复但不支持空洞. 替换列表总会把选择
     * 清除为 -1, 但不会调用配方选择处理器.</p>
     *
     * @param options 新的有序显示快照
     */
    void setRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options);

    /**
     * 返回最近一次已提交的配方选项快照.
     *
     * @return 不可修改的有序选项列表
     */
    @Unmodifiable
    @NotNull
    List<StonecutterRecipeOption> getRecipeOptions();

    /**
     * 返回当前选中的配方索引.
     *
     * @return 配方索引, -1 表示未选择
     */
    int getSelectedRecipeIndex();

    /**
     * 设置当前选择的配方.
     *
     * <p>-1 表示清除选择. 此操作不会调用配方选择处理器;
     * 非负索引必须在执行变更时属于当前配方列表.
     *
     * @param index 配方索引或 -1
     */
    void setSelectedRecipeIndex(int index);

    /**
     * 替换玩家选择原生配方时调用的处理器.
     *
     * @param handlers 新处理器列表
     */
    void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super StonecutterRecipeSelect>> handlers);

    /**
     * 返回配方选择处理器快照.
     *
     * @return 不可修改的处理器列表
     */
    @Unmodifiable
    @NotNull
    List<Consumer<StonecutterRecipeSelect>> getRecipeSelectHandlers();

    /**
     * 添加玩家配方选择处理器.
     *
     * @param handler 要添加的处理器
     */
    void addRecipeSelectHandler(@NotNull Consumer<? super StonecutterRecipeSelect> handler);

    /**
     * 移除一个与给定对象相等的玩家配方选择处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeRecipeSelectHandler(@NotNull Consumer<? super StonecutterRecipeSelect> handler);

    /**
     * 切石机 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<StonecutterWindow, Builder> {

        /**
         * 设置必须为 2x1 的上部 GUI.
         *
         * @param upperGui 输入与结果 GUI
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
         * 设置初始原生配方列表.
         *
         * @param options 有序显示快照
         * @return 此 Builder
         */
        @NotNull
        Builder setRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options);

        /**
         * 设置初始选择.
         *
         * @param index 配方索引或 -1
         * @return 此 Builder
         */
        @NotNull
        Builder setSelectedRecipeIndex(int index);

        /**
         * 替换玩家选择原生配方时调用的处理器.
         *
         * @param handlers 新处理器列表
         * @return 此 Builder
         */
        @NotNull
        Builder setRecipeSelectHandlers(
                @NotNull List<? extends Consumer<? super StonecutterRecipeSelect>> handlers
        );

        /**
         * 添加玩家配方选择处理器.
         *
         * @param handler 要添加的处理器
         * @return 此 Builder
         */
        @NotNull
        Builder addRecipeSelectHandler(@NotNull Consumer<? super StonecutterRecipeSelect> handler);

        @Override
        @NotNull
        Builder clone();
    }
}
