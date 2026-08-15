package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Window 引擎控制铁砧专属协议状态的类型化菜单边界.
 */
@ApiStatus.Internal
public interface AnvilMenuHandle extends MenuHandle {

    /**
     * 通知菜单客户端已提交新的重命名文本.
     * 实现应保存该文本供空输入槽占位物同步使用, 并重新发送可能被客户端预测覆盖的结果槽和等级消耗状态.
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
     * 设置输入槽为空时是否以不可见占位物保持文本框可编辑.
     * 占位物会携带最近一次由客户端提交的重命名文本, 使空输入槽的预测纠正不会把文本重置为空.
     *
     * @param textFieldAlwaysEnabled 是否始终启用文本框
     */
    void setTextFieldAlwaysEnabled(boolean textFieldAlwaysEnabled);

    /**
     * 设置结果槽为空时是否以不可见占位物保持结果按钮有效.
     *
     * @param resultAlwaysValid 是否始终保持结果有效
     */
    void setResultAlwaysValid(boolean resultAlwaysValid);
}
