package net.momirealms.sparrow.ui.window.handle;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface CrafterMenuHandle extends MenuHandle {

    /**
     * 设置一个 3x3 输入槽的禁用状态.
     *
     * @param slot 输入槽编号
     * @param disabled {@code true} 表示禁用
     */
    void setSlotDisabled(int slot, boolean disabled);
}
