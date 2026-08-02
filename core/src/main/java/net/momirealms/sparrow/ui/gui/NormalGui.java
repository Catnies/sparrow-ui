package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class NormalGui extends AbstractGui {

    private NormalGui(Structure structure, SlotElement[] elements, ItemProvider background, boolean frozen) {
        super(structure, elements, background, frozen);
    }

    /**
     * 创建一个所有槽位都为空的 GUI.
     *
     * @param size GUI 尺寸
     * @return 空 GUI
     */
    @NotNull
    public static NormalGui empty(@NotNull GuiSize size) {
        return builder(size).build();
    }

    /**
     * 使用已有布局创建 GUI, 未绑定的槽位保持为空.
     *
     * @param structure GUI 布局
     * @return 空 GUI
     */
    @NotNull
    public static NormalGui from(@NotNull Structure structure) {
        return builder(structure).build();
    }

    /**
     * 为指定尺寸创建 Builder.
     *
     * @param size GUI 尺寸
     * @return 普通 GUI Builder
     */
    @NotNull
    public static Gui.Builder<NormalGui, ?> builder(@NotNull GuiSize size) {
        return new Builder(Structure.of(size));
    }

    /**
     * 为指定尺寸创建 Builder.
     *
     * @param width GUI 宽度
     * @param height GUI 高度
     * @return 普通 GUI Builder
     */
    @NotNull
    public static Gui.Builder<NormalGui, ?> builder(int width, int height) {
        return new Builder(Structure.of(GuiSize.of(width, height)));
    }

    /**
     * 为已有布局创建 Builder.
     *
     * @param structure GUI 布局
     * @return 普通 GUI Builder
     */
    @NotNull
    public static Gui.Builder<NormalGui, ?> builder(@NotNull Structure structure) {
        return new Builder(structure);
    }

    /**
     * GUI Builder 的实现.
     */
    private static final class Builder extends AbstractGuiBuilder<NormalGui, Builder> {
        private Builder(Structure structure) {
            super(structure);
        }

        private Builder(Builder source) {
            super(source);
        }

        @NotNull
        @Override
        protected Builder self() {
            return this;
        }

        @NotNull
        @Override
        protected Builder newCopy() {
            return new Builder(this);
        }

        @NotNull
        @Override
        protected NormalGui create(
                @NotNull Structure structure,
                SlotElement @NotNull [] elements,
                @Nullable ItemProvider background,
                boolean frozen
        ) {
            return new NormalGui(structure, elements, background, frozen);
        }
    }
}
