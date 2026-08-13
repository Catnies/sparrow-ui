package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.pane.Pane;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapPalette;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

public interface CartographyWindow extends Window {
    int MAP_SIZE = 128;

    /**
     * 创建使用 1x2 输入 Pane 与 1x1 结果 Pane 的 Builder.
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
     * 返回最近一次已应用的不可修改图标快照.
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
     * 返回最近一次已应用的地图预览模式.
     *
     * @return 预览模式
     */
    @NotNull
    View getView();

    /**
     * 地图上的一个图标.
     *
     * @param type 图标类型
     * @param x 横坐标, 发送时以 {@code (byte) (x - 128)} 编码
     * @param y 纵坐标, 发送时以 {@code (byte) (y - 128)} 编码
     * @param rot 0 到 15 的旋转步
     * @param component 可选的图标文本
     */
    record MapIcon(@NotNull MapCursor.Type type, int x, int y, int rot, @Nullable Component component) {
        public MapIcon {
            Objects.requireNonNull(type, "type");
            if (rot < 0 || rot > 15) {
                throw new IllegalArgumentException("map icon rotation must be between 0 and 15: " + rot);
            }
        }

        /**
         * 使用注册表键创建地图图标.
         *
         * @param type 图标类型的注册表键
         * @param x 横坐标, 发送时以 {@code (byte) (x - 128)} 编码
         * @param y 纵坐标, 发送时以 {@code (byte) (y - 128)} 编码
         * @param rot 0 到 15 的旋转步
         * @param component 可选的图标文本
         * @return 地图图标
         * @throws java.util.NoSuchElementException 如果注册表中不存在该类型
         */
        @NotNull
        public static MapIcon fromKey(@NotNull NamespacedKey type, int x, int y, int rot, @Nullable Component component) {
            return new MapIcon(Registry.MAP_DECORATION_TYPE.getOrThrow(type), x, y, rot, component);
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
        NORMAL,     // 普通大小
        SMALL,      // 使用纸张触发的缩小预览
        DUPLICATE,  // 使用空地图触发的复制预览
        LOCK        // 使用玻璃板触发的锁定预览
    }

    /**
     * 制图台 Window 的可重复 Builder.
     */
    interface Builder extends Window.Builder<CartographyWindow, Builder> {

        /**
         * 设置映射协议槽位(raw slot)0 和 1 的 1x2 输入 Pane.
         *
         * @param inputPane 输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

        /**
         * 设置映射协议槽位(raw slot)2 的 1x1 结果 Pane.
         *
         * @param resultPane 结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        /**
         * 设置控制玩家物品栏区域的 9x4 Pane; null 表示连接玩家 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

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
