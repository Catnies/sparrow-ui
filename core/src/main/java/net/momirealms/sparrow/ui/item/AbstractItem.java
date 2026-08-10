package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 为自行维护状态的 Item 提供显示与主动失效的基础实现.
 * <p>子类按需直接覆写 {@link Item} 的交互方法, 并在显示状态改变后调用 {@link #notifyWindows()}.</p>
 * <p>同一实例可以同时显示在多个 Window 与槽位中, 子类必须自行保证可变字段的线程安全.</p>
 */
public abstract class AbstractItem implements ObservableItem {
    private final ItemProvider itemProvider = this::render;
    private final RefreshPlan refreshPlan;
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>();

    /**
     * 创建没有周期刷新的 Item.
     */
    protected AbstractItem() {
        this(RefreshPlan.none());
    }

    /**
     * 创建使用指定周期刷新计划的 Item.
     *
     * @param refreshPlan 周期刷新计划
     */
    protected AbstractItem(@NotNull RefreshPlan refreshPlan) {
        this.refreshPlan = Objects.requireNonNull(refreshPlan, "refreshPlan");
    }

    /**
     * 根据当前显示上下文同步渲染物品.
     * <p>此方法遵守 {@link ItemProvider#provide(RenderContext)} 的渲染约束.</p>
     *
     * @param context 渲染上下文
     * @return 本次显示的物品
     */
    protected abstract ItemStack render(@NotNull RenderContext context);

    @Override
    public final ItemProvider getItemProvider() {
        return this.itemProvider;
    }

    @Override
    public final ItemAttachment attach(@NotNull Observer<? super Item> observer) {
        Subscription subscription = this.observers.subscribe(observer);
        return ItemAttachment.subscribed(this.refreshPlan, subscription);
    }

    @Override
    public final void notifyWindows() {
        this.observers.publish(this);
    }
}
