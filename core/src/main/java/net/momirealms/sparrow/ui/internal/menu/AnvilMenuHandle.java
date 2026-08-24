package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public interface AnvilMenuHandle extends MenuHandle {

    /**
     * 接收客户端提交的重命名文本, 并重新核对结果槽和等级消耗.
     *
     * @param text 新文本
     */
    void handleRename(@NotNull String text);

    /**
     * 设置客户端铁砧界面显示的等级消耗.
     *
     * @param enchantmentCost 等级消耗
     */
    void setEnchantmentCost(int enchantmentCost);

    /**
     * 设置空输入槽是否使用携带最近重命名文本的不可见占位物.
     *
     * @param textFieldAlwaysEnabled 是否始终启用文本框
     */
    void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled);

    /**
     * 设置空结果槽是否使用不可见占位物保持按钮有效.
     *
     * @param resultAlwaysValid 是否始终保持结果有效
     */
    void setResultAlwaysValid(boolean resultAlwaysValid);
}
