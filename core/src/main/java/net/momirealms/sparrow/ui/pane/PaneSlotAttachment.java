package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;

public final class PaneSlotAttachment implements AutoCloseable {
    private final Element element;              // 挂载时槽位上的元素
    private final boolean frozen;               // 挂载时这个槽位冻没冻
    private final Subscription subscription;    // 这次挂载的订阅

    PaneSlotAttachment(Element element, boolean frozen, Subscription subscription) {
        this.element = element;
        this.frozen = frozen;
        this.subscription = subscription;
    }

    @NotNull
    public Element element() {
        return this.element;
    }

    public boolean frozen() {
        return this.frozen;
    }

    public boolean isClosed() {
        return this.subscription.isClosed();
    }

    @Override
    public void close() {
        this.subscription.close();
    }
}
