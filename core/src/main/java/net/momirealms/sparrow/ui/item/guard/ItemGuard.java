package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import net.momirealms.sparrow.ui.item.Item;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.BiConsumer;

@FunctionalInterface
public interface ItemGuard<C extends ItemInteraction> {
    ItemGuard<ItemInteraction> ALLOW_ALL = (ignoredItem, ignoredInteraction) -> true;

    /**
     * 判断 Item 是否接受本次交互.
     *
     * @param item 接收交互的 Item
     * @param interaction 交互上下文
     * @return 接受交互时返回 {@code true}
     */
    boolean test(@NotNull Item item, @NotNull C interaction);

    /**
     * 把下一个守卫接到链尾, 整条链在首个 {@code false} 处结束.
     *
     * @param <I> 组合链接受的具体交互类型
     * @param guard 下一个守卫
     * @return 组合后的守卫
     */
    @NotNull
    default <I extends C> ItemGuard<I> and(@NotNull ItemGuard<I> guard) {
        Objects.requireNonNull(guard, "guard");
        return (item, interaction) -> this.test(item, interaction) && guard.test(item, interaction);
    }

    /**
     * 把带拒绝回调的守卫接到链尾, 回调只在新接入的守卫返回 {@code false} 时执行.
     *
     * @param <I> 组合链接受的具体交互类型
     * @param guard 下一个守卫
     * @param onRejected 下一个守卫的拒绝回调
     * @return 组合后的守卫
     */
    @NotNull
    default <I extends C> ItemGuard<I> and(@NotNull ItemGuard<I> guard, @NotNull BiConsumer<Item, I> onRejected) {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(onRejected, "onRejected");
        return (item, interaction) -> {
            if (!this.test(item, interaction)) return false;
            if (guard.test(item, interaction)) return true;
            onRejected.accept(item, interaction);
            return false;
        };
    }
}
