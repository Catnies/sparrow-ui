package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;

/**
 * 三种炉类菜单共用的进度与配方书能力.
 */
@ApiStatus.Internal
public interface FurnaceMenuHandle extends RecipeBookMenuHandle {

    /**
     * 设置客户端箭头的完成进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setCookProgress(double progress);

    /**
     * 设置客户端剩余燃烧火焰的填充进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setFuelProgress(double progress);
}
