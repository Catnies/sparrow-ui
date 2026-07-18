package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.item.ObservableItem;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 将一个 {@link SlotElement} 绑定到 Window 的最终槽位.
 *
 * <p>绑定拥有由它创建的 Item 订阅. 替换元素或关闭绑定时会取消旧订阅,
 * 迟到的旧通知会通过版本检查被忽略.</p>
 */
public final class SlotElementBinding implements AutoCloseable {
    private final Window window;
    private final int windowSlot;

    private volatile SlotElement element;
    private volatile long revision;
    private volatile boolean closed;
    private Subscription subscription;

    /**
     * 创建并立即订阅一个最终窗口槽位绑定.
     * 初始槽位会被标记为脏.
     *
     * @param window 所属 Window
     * @param windowSlot 最终窗口槽位
     * @param element 初始元素
     */
    public SlotElementBinding(@NotNull Window window, int windowSlot, @NotNull SlotElement element) {
        if (windowSlot < 0)
            throw new IllegalArgumentException("windowSlot must be non-negative");
        this.window = window;
        this.windowSlot = windowSlot;
        this.element = element;
        this.subscription = this.subscribe(element, this.revision);
        window.invalidateSlot(windowSlot);
    }

    /**
     * 获取当前元素需要由 Window tick 触发的重新渲染周期.
     *
     * @return 正数 tick 周期；不需要周期更新时返回负数
     */
    public int updatePeriodTicks() {
        return this.requireOpenElement().updatePeriodTicks();
    }

    /**
     * 使用当前元素为最终窗口槽位创建独占 ItemStack 快照.
     *
     * @return 归调用方所有的渲染快照
     * @throws IllegalStateException 如果绑定已经关闭
     */
    public ItemStack render() {
        return switch (this.requireOpenElement()) {
            case SlotElement.Item itemElement -> itemElement.item().getItemProvider().provide(
                    new RenderContext(this.window.viewer(), this.window, this.windowSlot)
            );
        };
    }

    /**
     * 将点击交给当前槽位实际持有的 Item.
     *
     * @param click 点击上下文
     * @throws IllegalArgumentException 如果点击不属于此绑定
     * @throws IllegalStateException 如果绑定已经关闭
     */
    public void handleClick(@NotNull ItemClick click) {
        if (click.window() != this.window || click.windowSlot() != this.windowSlot) {
            throw new IllegalArgumentException("click must target this window slot binding");
        }

        switch (this.requireOpenElement()) {
            case SlotElement.Item itemElement -> itemElement.item().handleClick(click);
        }
    }

    /**
     * 替换槽位内容并原子地切换由此绑定拥有的订阅.
     *
     * @param replacement 新元素
     */
    public synchronized void replace(@NotNull SlotElement replacement) {
        if (this.closed)
            throw new IllegalStateException("binding is closed");
        if (this.element.equals(replacement)) return;

        this.revision++;
        this.closeSubscription();
        this.element = replacement;
        this.subscription = subscribe(replacement, this.revision);
        this.window.invalidateSlot(this.windowSlot);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;

        this.closed = true;
        this.revision++;
        this.closeSubscription();
    }

    public Window window() {
        return this.window;
    }

    public int windowSlot() {
        return this.windowSlot;
    }

    public SlotElement element() {
        return this.element;
    }

    private SlotElement requireOpenElement() {
        if (this.closed) {
            throw new IllegalStateException("binding is closed");
        }
        return this.element;
    }

    private Subscription subscribe(SlotElement element, long expectedRevision) {
        return switch (element) {
            case SlotElement.Item(var item) when item instanceof ObservableItem observable ->
                    observable.subscribe(ignored -> invalidateIfCurrent(expectedRevision));
            case SlotElement.Item ignored -> null;
        };
    }

    private void invalidateIfCurrent(long expectedRevision) {
        if (!this.closed && this.revision == expectedRevision) {
            this.window.invalidateSlot(this.windowSlot);
        }
    }

    private void closeSubscription() {
        if (this.subscription != null) {
            this.subscription.close();
            this.subscription = null;
        }
    }
}
