package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.RefreshPlan;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 将一个 {@link SlotElement} 绑定到 Window 的最终槽位.
 *
 * <p>绑定拥有由它创建的 Item 挂载. 替换元素或关闭绑定时会关闭旧挂载,
 * 迟到的旧通知会通过版本检查被忽略.</p>
 */
public final class SlotElementBinding implements AutoCloseable {
    private final Window window;
    private final int windowSlot;

    private volatile SlotElement element;
    private volatile ItemAttachment attachment;
    private volatile long revision;
    private volatile boolean closed;

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
        this.attachment = this.attach(element, this.revision);
        window.dirty(windowSlot);
    }

    /**
     * 获取当前元素需要由 Window tick 执行的刷新计划.
     *
     * @return 当前挂载的不可变刷新计划
     */
    public RefreshPlan refreshPlan() {
        this.requireOpenElement();
        return this.attachment.refreshPlan();
    }

    /**
     * 使用当前元素为最终窗口槽位创建独占 ItemStack 快照.
     *
     * @return 归调用方所有的渲染快照
     * @throws IllegalStateException 如果绑定已经关闭
     */
    public ItemStack render() {
        return switch (this.requireOpenElement()) {
            case SlotElement.Item itemElement -> itemElement.item()
                    .getItemProvider()
                    .provide(new RenderContext(this.window.viewer(), this.window, this.windowSlot)
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
     * 替换槽位内容并原子地切换由此绑定拥有的挂载.
     * 新挂载创建失败时，旧元素和旧挂载保持有效.
     *
     * @param replacement 新元素
     */
    public synchronized void replace(@NotNull SlotElement replacement) {
        if (this.closed)
            throw new IllegalStateException("binding is closed");
        if (this.element.equals(replacement)) return;

        long nextRevision = this.revision + 1;
        ItemAttachment nextAttachment = attach(replacement, nextRevision);
        ItemAttachment previousAttachment = this.attachment;

        this.element = replacement;
        this.attachment = nextAttachment;
        this.revision = nextRevision;
        previousAttachment.close();
        this.window.dirty(this.windowSlot);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;

        this.closed = true;
        this.revision++;
        this.attachment.close();
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

    private ItemAttachment attach(SlotElement element, long expectedRevision) {
        return switch (element) {
            case SlotElement.Item(var item) ->
                    item.attach(ignored -> invalidateIfCurrent(expectedRevision));
        };
    }

    /**
     * 将 Bundle 槽位选择交给当前槽位实际持有的 Item.
     *
     * @param select Bundle 选择上下文
     * @throws IllegalArgumentException 如果选择事件不属于此 Window 的 viewer
     * @throws IllegalStateException 如果绑定已经关闭
     */
    public void handleBundleSelect(@NotNull BundleSelect select) {
        if (!select.player().getUniqueId().equals(this.window.viewer().getUniqueId())) {
            throw new IllegalArgumentException("bundle selection must belong to this window viewer");
        }

        switch (this.requireOpenElement()) {
            case SlotElement.Item itemElement -> itemElement.item().handleBundleSelect(select);
        }
    }

    private void invalidateIfCurrent(long expectedRevision) {
        if (!this.closed && this.revision == expectedRevision) {
            this.window.dirty(this.windowSlot);
        }
    }
}
