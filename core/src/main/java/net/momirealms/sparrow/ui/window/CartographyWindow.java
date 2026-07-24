package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.gui.Gui;
import org.bukkit.map.MapPalette;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * 使用原版制图台界面的三槽 Window.
 * <p>窗口使用独立的虚拟地图编号和 128x128 画布, 不会修改服务器中的真实地图数据.
 */
public interface CartographyWindow extends Window {
    int MAP_SIZE = 128;

    /**
     * 创建使用 1x2 输入 GUI 与 1x1 结果 GUI 的 Builder.
     *
     * @return 制图台窗口 Builder
     */
    @NotNull
    static Builder builder() {
        return new CartographyWindowImpl.BuilderImpl();
    }

    /**
     * 把图片转换为地图色并绘制到指定起点.
     *
     * @param x 左上角 x 坐标
     * @param y 左上角 y 坐标
     * @param image 不越过 128x128 画布的图片
     */
    @SuppressWarnings("removal")
    default void applyPatch(int x, int y, @NotNull BufferedImage image) {
        Objects.requireNonNull(image, "image");
        this.applyPatch(new MapPatch(
                x,
                y,
                image.getWidth(),
                image.getHeight(),
                MapPalette.imageToBytes(image)
        ));
    }

    /**
     * 把一块地图色补丁绘制到虚拟画布.
     *
     * @param patch 地图补丁
     */
    void applyPatch(@NotNull MapPatch patch);

    /**
     * 替换地图图标.
     *
     * @param icons 新图标集合
     */
    void setIcons(@NotNull Set<? extends MapIcon> icons);

    /**
     * 返回最近一次已提交的不可修改图标快照.
     *
     * @return 图标快照
     */
    @Unmodifiable
    @NotNull
    Set<MapIcon> getIcons();

    /**
     * 分配新的虚拟地图编号并清除画布与图标.
     */
    void resetMap();

    /**
     * 设置制图台预览模式.
     *
     * @param view 新预览模式
     */
    void setView(@NotNull View view);

    /**
     * 返回最近一次已提交的预览模式.
     *
     * @return 预览模式
     */
    @NotNull
    View getView();

    /**
     * 地图上的一个图标.
     *
     * @param type 图标类型
     * @param x 0 到 256 的横坐标
     * @param y 0 到 256 的纵坐标
     * @param rot 0 到 15 的旋转步
     * @param component 可选的图标文本
     */
    record MapIcon(@NotNull Type type, int x, int y, int rot, @Nullable Component component) {
        public MapIcon {
            Objects.requireNonNull(type, "type");
            if (x < 0 || x > 256) {
                throw new IllegalArgumentException("map icon x must be between 0 and 256: " + x);
            }
            if (y < 0 || y > 256) {
                throw new IllegalArgumentException("map icon y must be between 0 and 256: " + y);
            }
            if (rot < 0 || rot > 15) {
                throw new IllegalArgumentException("map icon rotation must be between 0 and 15: " + rot);
            }
        }

        /**
         * 客户端原版地图支持的图标类型.
         */
        public enum Type {
            WHITE_ARROW,
            GREEN_ARROW,
            RED_ARROW,
            BLUE_ARROW,
            WHITE_CROSS,
            RED_POINTER,
            WHITE_CIRCLE,
            SMALL_WHITE_CIRCLE,
            MANSION,
            TEMPLE,
            WHITE_BANNER,
            ORANGE_BANNER,
            MAGENTA_BANNER,
            LIGHT_BLUE_BANNER,
            YELLOW_BANNER,
            LIME_BANNER,
            PINK_BANNER,
            GRAY_BANNER,
            LIGHT_GRAY_BANNER,
            CYAN_BANNER,
            PURPLE_BANNER,
            BLUE_BANNER,
            BROWN_BANNER,
            GREEN_BANNER,
            RED_BANNER,
            BLACK_BANNER,
            RED_CROSS
        }
    }

    /**
     * 画布上的矩形地图色补丁.
     *
     * @param startX 左上角 x 坐标
     * @param startY 左上角 y 坐标
     * @param width 宽度
     * @param height 高度
     * @param colors 按行排列的地图色
     */
    record MapPatch(int startX, int startY, int width, int height, byte @NotNull [] colors) {
        public MapPatch {
            Objects.requireNonNull(colors, "colors");
            if (startX < 0 || startY < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException("map patch coordinates must be non-negative and dimensions must be positive");
            }
            if (startX >= MAP_SIZE || startY >= MAP_SIZE || width > MAP_SIZE - startX || height > MAP_SIZE - startY) {
                throw new IllegalArgumentException("map patch exceeds the 128x128 canvas");
            }
            try {
                int expectedLength = Math.multiplyExact(width, height);
                if (colors.length != expectedLength) {
                    throw new IllegalArgumentException("map patch requires " + expectedLength + " colors, got " + colors.length);
                }
                colors = colors.clone();
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("map patch dimensions overflow", exception);
            }
        }

        @Override
        public byte @NotNull [] colors() {
            return this.colors.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MapPatch patch
                    && this.startX == patch.startX
                    && this.startY == patch.startY
                    && this.width == patch.width
                    && this.height == patch.height
                    && Arrays.equals(this.colors, patch.colors);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(this.startX, this.startY, this.width, this.height);
            return 31 * result + Arrays.hashCode(this.colors);
        }
    }

    /**
     * 制图台客户端预览模式.
     */
    enum View {
        /** 普通大小. */
        NORMAL,
        /** 使用纸张触发的缩小预览. */
        SMALL,
        /** 使用空地图触发的复制预览. */
        DUPLICATE,
        /** 使用玻璃板触发的锁定预览. */
        LOCK
    }

    /**
     * 制图台 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<CartographyWindow, Builder> {

        /**
         * 设置映射原始槽位 0 和 1 的 1x2 输入 GUI.
         *
         * @param inputGui 输入 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setInputGui(@NotNull Gui inputGui);

        /**
         * 设置映射原始槽位 2 的 1x1 结果 GUI.
         *
         * @param resultGui 结果 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setResultGui(@NotNull Gui resultGui);

        /**
         * 设置控制玩家物品栏区域的 9x4 GUI; null 表示映射玩家真实物品栏.
         *
         * @param lowerGui 下部 GUI
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerGui(@Nullable Gui lowerGui);

        /**
         * 设置初始图标.
         *
         * @param icons 初始图标集合
         * @return 此 Builder
         */
        @NotNull
        Builder setIcons(@NotNull Set<? extends MapIcon> icons);

        /**
         * 设置完整的 128x128 初始地图色.
         *
         * @param colors 16384 个地图色
         * @return 此 Builder
         */
        @NotNull
        Builder setMap(byte @NotNull [] colors);

        /**
         * 设置完整的 128x128 初始图片.
         *
         * @param image 初始图片
         * @return 此 Builder
         */
        @NotNull
        Builder setMap(@NotNull BufferedImage image);

        /**
         * 设置初始预览模式.
         *
         * @param view 初始预览模式
         * @return 此 Builder
         */
        @NotNull
        Builder setView(@NotNull View view);

        @Override
        @NotNull
        Builder clone();
    }
}
