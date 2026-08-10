package net.momirealms.sparrow.ui.click.guard;

import net.momirealms.sparrow.ui.click.ItemInteraction;
import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiPredicate;

/**
 * 决定一次交互是否继续执行对应的 Item 点击前置处理器.
 */
@FunctionalInterface
public interface ItemGuard<C extends ItemInteraction> extends BiPredicate<Item, C> {

    @Override
    boolean test(@NotNull Item item, @NotNull C interaction);
}
