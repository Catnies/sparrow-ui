package net.momirealms.sparrow.ui.gui;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 记录对一个 GUI 槽位的订阅, 以及订阅创建时读到的状态.
 * <p>{@link #element()}, {@link #background()} 和 {@link #frozen()} 来自同一时刻.
 * 调用 {@link #close()} 后不再接收该槽位的更新.
 */
public final class GuiSlotAttachment implements AutoCloseable {
    private final SlotElement element;
    private final ItemProvider background;
    private final boolean frozen;
    private final Subscription subscription;

    GuiSlotAttachment(SlotElement element, ItemProvider background, boolean frozen, Subscription subscription) {
        this.element = element;
        this.background = background;
        this.frozen = frozen;
        this.subscription = subscription;
    }

    /**
     * 返回订阅创建时的槽位元素.
     *
     * @return 订阅创建时的槽位元素
     */
    public @NotNull SlotElement element() {
        return this.element;
    }

    /**
     * 返回订阅创建时的 GUI 背景.
     *
     * @return 订阅创建时的 GUI 背景, 没有背景时为 null
     */
    public @Nullable ItemProvider background() {
        return this.background;
    }

    /**
     * 返回订阅创建时 GUI 是否冻结.
     *
     * @return 订阅创建时 GUI 是否冻结
     */
    public boolean frozen() {
        return this.frozen;
    }

    /**
     * 返回订阅是否已取消.
     *
     * @return 订阅是否已取消
     */
    public boolean isClosed() {
        return this.subscription.isClosed();
    }

    /**
     * 取消订阅. 重复调用不会产生额外效果.
     */
    @Override
    public void close() {
        this.subscription.close();
    }
}
