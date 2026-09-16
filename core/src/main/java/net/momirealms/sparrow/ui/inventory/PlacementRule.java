package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface PlacementRule {

    /**
     * 判断本次放入是否成立.
     *
     * @param context 放入上下文
     * @return 允许放入时返回 {@code true}
     */
    boolean test(@NotNull PlacementContext context);
}
