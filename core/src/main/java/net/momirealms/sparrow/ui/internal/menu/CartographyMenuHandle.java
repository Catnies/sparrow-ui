package net.momirealms.sparrow.ui.internal.menu;

import net.momirealms.sparrow.ui.window.CartographyWindow;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

@ApiStatus.Internal
public interface CartographyMenuHandle extends MenuHandle {

    /**
     * 把地图色补丁写入虚拟画布.
     *
     * @param patch 地图补丁
     */
    void applyPatch(@NotNull CartographyWindow.MapPatch patch);

    /**
     * 替换地图图标.
     *
     * @param icons 新图标集合
     */
    void setIcons(@NotNull Set<CartographyWindow.MapIcon> icons);

    /**
     * 分配新地图编号并清空画布和图标.
     */
    void resetMap();

    /**
     * 设置原版制图台预览模式.
     *
     * @param view 新预览模式
     */
    void setView(@NotNull CartographyWindow.View view);
}
