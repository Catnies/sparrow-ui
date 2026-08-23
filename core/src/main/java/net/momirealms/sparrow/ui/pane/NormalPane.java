package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class NormalPane extends AbstractPane {

    private NormalPane(Structure structure, Element[] elements, ItemProvider background, boolean frozen) {
        super(structure, elements, background, frozen);
    }

    /**
     * 创建一个所有槽位都为空的 Pane.
     *
     * @param size Pane 尺寸
     * @return 空 Pane
     */
    @NotNull
    public static NormalPane empty(@NotNull PaneSize size) {
        return builder(size).build();
    }

    /**
     * 使用已有布局创建 Pane, 未绑定的槽位保持为空.
     *
     * @param structure Pane 布局
     * @return 空 Pane
     */
    @NotNull
    public static NormalPane from(@NotNull Structure structure) {
        return builder(structure).build();
    }

    /**
     * 为指定尺寸创建 Builder.
     *
     * @param size Pane 尺寸
     * @return 普通 Pane Builder
     */
    @NotNull
    public static Pane.Builder<NormalPane, ?> builder(@NotNull PaneSize size) {
        return new Builder(Structure.of(size));
    }

    /**
     * 为指定尺寸创建 Builder.
     *
     * @param width Pane 宽度
     * @param height Pane 高度
     * @return 普通 Pane Builder
     */
    @NotNull
    public static Pane.Builder<NormalPane, ?> builder(int width, int height) {
        return new Builder(Structure.of(PaneSize.of(width, height)));
    }

    /**
     * 为已有布局创建 Builder.
     *
     * @param structure Pane 布局
     * @return 普通 Pane Builder
     */
    @NotNull
    public static Pane.Builder<NormalPane, ?> builder(@NotNull Structure structure) {
        return new Builder(structure);
    }

    private static final class Builder extends AbstractPaneBuilder<NormalPane, Builder> {
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
        protected NormalPane create(
                @NotNull Structure structure,
                Element @NotNull [] elements,
                @Nullable ItemProvider background,
                boolean frozen
        ) {
            return new NormalPane(structure, elements, background, frozen);
        }
    }
}
