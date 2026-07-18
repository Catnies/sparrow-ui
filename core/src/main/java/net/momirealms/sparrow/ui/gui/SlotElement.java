package net.momirealms.sparrow.ui.gui;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public sealed interface SlotElement permits SlotElement.Item {

    /**
     * 直接持有一个可渲染、可点击的 Item.
     */
    record Item(@NotNull net.momirealms.sparrow.ui.item.Item item) implements SlotElement {

        public Item {
            Objects.requireNonNull(item, "item");
        }
    }
}
