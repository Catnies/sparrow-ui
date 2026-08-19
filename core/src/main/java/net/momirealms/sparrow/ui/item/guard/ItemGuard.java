package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiPredicate;

@FunctionalInterface
public interface ItemGuard<C extends ItemInteraction> extends BiPredicate<Item, C> {

    @Override
    boolean test(@NotNull Item item, @NotNull C interaction);
}
