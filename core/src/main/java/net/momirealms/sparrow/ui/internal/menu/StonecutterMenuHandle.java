package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.window.StonecutterRecipeOption;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@ApiStatus.Internal
public interface StonecutterMenuHandle extends MenuHandle {

    /**
     * 替换客户端原生配方列表并清除当前选择.
     *
     * @param options 按客户端索引顺序排列的显示选项
     */
    void setRecipeOptions(@NotNull List<? extends StonecutterRecipeOption> options);

    /**
     * 设置当前选中的原生配方索引.
     *
     * @param index 配方索引, -1 表示未选择
     */
    void setSelectedRecipeIndex(int index);

    /**
     * 收到客户端选择操作后, 以权威值强制复核选择 property 和结果槽.
     *
     * @param index 需要恢复给客户端的权威配方索引
     */
    void reconcileClientSelection(int index);
}
