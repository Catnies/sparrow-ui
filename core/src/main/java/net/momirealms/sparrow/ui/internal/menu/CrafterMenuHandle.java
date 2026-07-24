package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;

/**
 * 合成器 Window 使用的类型化菜单句柄.
 */
@ApiStatus.Internal
public interface CrafterMenuHandle extends MenuHandle {

    /**
     * 设置一个 3x3 输入槽的禁用状态.
     *
     * @param slot 输入槽编号
     * @param disabled true 表示禁用
     */
    void setSlotDisabled(int slot, boolean disabled);
}
