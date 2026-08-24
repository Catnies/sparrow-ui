package net.momirealms.sparrow.ui.internal.menu;

import org.jetbrains.annotations.ApiStatus;

/**
 * 酿造台菜单的酿造进度与燃料状态.
 */
@ApiStatus.Internal
public interface BrewingMenuHandle extends MenuHandle {

    /**
     * 设置客户端箭头的完成进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setBrewProgress(double progress);

    /**
     * 设置客户端燃料条的填充进度.
     *
     * @param progress 范围为 0.0 到 1.0 的进度
     */
    void setFuelProgress(double progress);
}
