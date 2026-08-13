package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PaneSlotAttachment implements AutoCloseable {
    private final Element element;       // 订阅创建时的槽位元素
    private final ItemProvider background;   // 订阅创建时的 Pane 背景, 可为 null
    private final boolean frozen;            // 订阅创建时的冻结状态
    private final Subscription subscription; // 槽位更新订阅

    PaneSlotAttachment(Element element, @Nullable ItemProvider background, boolean frozen, Subscription subscription) {
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
    @NotNull
    public Element element() {
        return this.element;
    }

    /**
     * 返回订阅创建时的 Pane 背景.
     *
     * @return 订阅创建时的 Pane 背景, 没有背景时为 null
     */
    @Nullable
    public ItemProvider background() {
        return this.background;
    }

    /**
     * 返回订阅创建时 Pane 是否冻结.
     *
     * @return 订阅创建时 Pane 是否冻结
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
     * 取消订阅.
     * 重复调用不会产生额外效果.
     */
    @Override
    public void close() {
        this.subscription.close();
    }
}
