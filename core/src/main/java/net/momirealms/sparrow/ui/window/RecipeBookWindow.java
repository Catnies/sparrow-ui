package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.key.Key;
import net.momirealms.sparrow.ui.window.click.RecipeBookSelectClick;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface RecipeBookWindow extends Window {

    /**
     * 往当前菜单发一个配方的 ghost recipe.
     * <p>不要求玩家解锁过这个配方. Future 完成只说明协议包进了发送路径, 不代表客户端显示或者确认了.
     *
     * @param recipeId 配方资源标识符
     * @return ghost recipe 的发送结果
     */
    @NotNull
    CompletableFuture<GhostRecipeResult> sendGhostRecipe(@NotNull Key recipeId);

    /**
     * 整批换掉玩家在原版配方书里选配方时的处理器.
     *
     * @param handlers 新处理器列表
     */
    void setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelectClick>> handlers);

    /**
     * 现在的配方选择处理器, 给一份快照.
     *
     * @return 不可修改的有序处理器列表
     */
    @NotNull
    @Unmodifiable
    List<Consumer<RecipeBookSelectClick>> getRecipeSelectHandlers();

    /**
     * 在配方选择处理器末尾追加一个.
     *
     * @param handler 要添加的处理器
     */
    void addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelectClick> handler);

    /**
     * 按 equals 摘掉一个配方选择处理器.
     *
     * @param handler 要移除的处理器
     */
    void removeRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelectClick> handler);

    /**
     * 配方书窗口共用的类型化 Builder.
     *
     * @param <W> 创建的配方书 Window 类型
     * @param <B> 具体 Builder 类型
     */
    interface Builder<W extends RecipeBookWindow, B extends Builder<W, B>> extends Window.Builder<W, B> {

        /**
         * 整批换掉初始的配方选择处理器.
         *
         * @param handlers 初始处理器列表
         * @return 此 Builder
         */
        @NotNull
        B setRecipeSelectHandlers(@NotNull List<? extends Consumer<? super RecipeBookSelectClick>> handlers);

        /**
         * 追加一个初始的配方选择处理器.
         *
         * @param handler 要添加的处理器
         * @return 此 Builder
         */
        @NotNull
        B addRecipeSelectHandler(@NotNull Consumer<? super RecipeBookSelectClick> handler);
    }
}
