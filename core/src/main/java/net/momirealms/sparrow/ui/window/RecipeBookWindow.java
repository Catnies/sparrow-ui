package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 具备原版配方书选择通知和 ghost recipe 显示能力的 Window.
 * <p>配方选择只产生通知, 菜单输入槽和结果始终由应用维护. 所有变更和 ghost 请求都可从
 * 任意线程调用, 实际处理会与 Window 生命周期一起串行进入玩家的实体线程.
 */
public interface RecipeBookWindow extends Window {

    /**
     * 向当前菜单发送指定配方的 ghost recipe.
     *
     * <p>此操作不要求玩家已经解锁配方. Stage 完成表示协议包已经提交给连接,
     * 不表示客户端已经显示或确认.</p>
     *
     * @param recipeId 配方资源标识符
     * @return ghost recipe 提交结果
     */
    @NotNull
    CompletionStage<GhostRecipeResult> sendGhostRecipe(@NotNull Key recipeId);

    /**
     * 替换玩家选择原版配方时调用的处理器.
     *
     * @param handlers 新处理器列表
     */
    void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelect>> handlers);

    /**
     * 返回配方选择处理器快照.
     *
     * @return 不可修改的有序处理器列表
     */
    @NotNull
    @Unmodifiable
    List<Consumer<RecipeBookSelect>> getRecipeSelectHandlers();

    /**
     * 在现有配方选择处理器末尾追加一个处理器.
     *
     * @param handler 要添加的处理器
     */
    void addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler);

    /**
     * 移除一个与给定对象相等的配方选择处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler);

    /**
     * 配方书 Window 共用的类型化 Builder Interface.
     *
     * @param <W> 创建的配方书 Window 类型
     * @param <B> 具体 Builder 类型
     */
    interface Builder<W extends RecipeBookWindow, B extends Builder<W, B>> extends Window.Builder<W, B> {

        /**
         * 替换初始配方选择处理器.
         *
         * @param handlers 初始处理器列表
         * @return 此 Builder
         */
        @NotNull
        B setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelect>> handlers);

        /**
         * 追加一个初始配方选择处理器.
         *
         * @param handler 要添加的处理器
         * @return 此 Builder
         */
        @NotNull
        B addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelect> handler);
    }
}
