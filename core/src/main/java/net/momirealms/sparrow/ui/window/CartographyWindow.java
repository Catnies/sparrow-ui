package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.window.map.MapColorPalette;
import net.momirealms.sparrow.ui.pane.Pane;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.map.MapCursor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

public interface CartographyWindow extends Window {
    int MAP_SIZE = 128; // 画布宽高, 128x128

    /**
     * 把一张图片转成地图色, 画到画布的指定起点.
     *
     * @param x 左上角 x 坐标
     * @param y 左上角 y 坐标
     * @param image 不越过 128x128 画布的图片
     * @throws IllegalArgumentException 图片超出画布时
     * @throws IllegalStateException SparrowUI 尚未完成初始化
     */
    default void applyPatch(int x, int y, @NotNull BufferedImage image) {
        Objects.requireNonNull(image, "image");
        this.applyPatch(new MapPatch(
                x,
                y,
                image.getWidth(),
                image.getHeight(),
                MapColorPalette.imageToBytes(image)
        ));
    }

    /**
     * 把一块已经调好色的补丁画到虚拟画布上.
     *
     * @param patch 地图补丁
     */
    void applyPatch(@NotNull MapPatch patch);

    /**
     * 换掉整组地图图标.
     *
     * @param icons 新图标集合
     */
    void setIcons(@NotNull Set<? extends MapIcon> icons);

    /**
     * 最近一次应用上去的图标, 给一份不可修改的快照.
     *
     * @return 图标快照
     */
    @Unmodifiable
    @NotNull
    Set<MapIcon> getIcons();

    /**
     * 换一张新的虚拟地图编号, 画布和图标一起清空.
     */
    void resetMap();

    /**
     * 设置客户端的预览模式.
     *
     * @param view 新预览模式
     */
    void setView(@NotNull View view);

    /**
     * 最近一次应用上去的预览模式.
     *
     * @return 预览模式
     */
    @NotNull
    View getView();

    /**
     * 地图上的一个图标.
     *
     * @param type 图标类型
     * @param x 横坐标; 发给客户端时会先减 128 再转成 byte
     * @param y 纵坐标; 发给客户端时会先减 128 再转成 byte
     * @param rot 旋转步, 0 到 15
     * @param component 图标上的文字, 可以不给
     */
    record MapIcon(@NotNull MapCursor.Type type, int x, int y, int rot, @Nullable Component component) {
        public MapIcon {
            Objects.requireNonNull(type, "type");
            if (rot < 0 || rot > 15) {
                throw new IllegalArgumentException("map icon rotation must be between 0 and 15: " + rot);
            }
        }

        /**
         * 拿注册表键建一个图标.
         *
         * @param type 图标类型的注册表键
         * @param x 横坐标; 发给客户端时会先减 128 再转成 byte
         * @param y 纵坐标; 发给客户端时会先减 128 再转成 byte
         * @param rot 旋转步, 0 到 15
         * @param component 图标上的文字, 可以不给
         * @return 地图图标
         * @throws java.util.NoSuchElementException 注册表里没有这个类型时
         */
        @NotNull
        public static MapIcon fromKey(@NotNull NamespacedKey type, int x, int y, int rot, @Nullable Component component) {
            return new MapIcon(Registry.MAP_DECORATION_TYPE.getOrThrow(type), x, y, rot, component);
        }
    }

    /**
     * 画布上的一块矩形补丁, 颜色已经是地图色.
     *
     * @param startX 左上角在第几列
     * @param startY 左上角在第几行
     * @param width 宽度
     * @param height 高度
     * @param colors 按行排的地图色, 长度必须等于 {@code width * height}, <strong>构造时复制</strong>
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

        /**
         * 把地图色复制一份出去.
         *
         * @return 按行排的地图色副本
         */
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
     * 客户端上的预览模式.
     */
    enum View {
        NORMAL,     // 普通大小
        SMALL,      // 使用纸张触发的缩小预览
        DUPLICATE,  // 使用空地图触发的复制预览
        LOCK        // 使用玻璃板触发的锁定预览
    }

    @NotNull
    static Builder builder() {
        return new CartographyWindowImpl.BuilderImpl();
    }

    interface Builder extends Window.Builder<CartographyWindow, Builder> {

        /**
         * 设置输入 Pane, 映射协议槽位(raw slot)0 和 1, 尺寸 1x2.
         *
         * @param inputPane 输入 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setInputPane(@NotNull Pane inputPane);

        /**
         * 设置结果 Pane, 映射协议槽位(raw slot)2, 尺寸 1x1.
         *
         * @param resultPane 结果 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setResultPane(@NotNull Pane resultPane);

        /**
         * 设置下部那个 9x4 的 Pane, 管玩家物品栏那一片; 给 null 就接玩家的 Bukkit Inventory.
         *
         * @param lowerPane 下部 Pane
         * @return 此 Builder
         */
        @NotNull
        Builder setLowerPane(@Nullable Pane lowerPane);

        /**
         * 设置一开始的图标.
         *
         * @param icons 初始图标集合
         * @return 此 Builder
         */
        @NotNull
        Builder setIcons(@NotNull Set<? extends MapIcon> icons);

        /**
         * 设置一开始那张完整的 128x128 地图色.
         *
         * @param colors 16384 个地图色
         * @return 此 Builder
         * @throws IllegalArgumentException 地图色数量不是 16384 时
         */
        @NotNull
        Builder setMap(byte @NotNull [] colors);

        /**
         * 设置一开始那张完整的 128x128 图片.
         *
         * @param image 初始图片
         * @return 此 Builder
         * @throws IllegalArgumentException 图片尺寸不是 128x128 时
         * @throws IllegalStateException SparrowUI 尚未完成初始化
         */
        @NotNull
        Builder setMap(@NotNull BufferedImage image);

        /**
         * 设置一开始的预览模式.
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
