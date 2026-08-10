package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 为自行维护状态的 Item 提供显示、交互与主动失效的基础实现.
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
    public final void handleClick(ItemClick click) {
        Throwable failure = null;
        try {
            if (this.evaluateClick(click)) {
                this.onClick(click);
            } else {
                this.onClickRejected(click);
            }
        } catch (Throwable throwable) {
            failure = throwable;
        }

        try {
            this.afterClick(click);
        } catch (Throwable throwable) {
            failure = ThrowableUtils.combine(failure, throwable);
        }

        if (failure != null) {
            throw ThrowableUtils.sneakyThrow(failure);
        }
    }

    /**
     * 判断一次点击是否进入正常点击处理.
     *
     * @param click 点击上下文
     * @return 允许继续时返回 true
     */
    protected boolean evaluateClick(ItemClick click) {
        return true;
    }

    /**
     * 处理被 {@link #evaluateClick(ItemClick)} 拒绝的点击.
     *
     * @param click 点击上下文
     */
    protected void onClickRejected(ItemClick click) {
    }

    /**
     * 处理通过检查的点击.
     *
     * @param click 点击上下文
     */
    protected void onClick(ItemClick click) {
    }

    /**
     * 在一次点击尝试结束后执行, 即使检查或处理过程抛出异常也会调用.
     *
     * @param click 点击上下文
     */
    protected void afterClick(ItemClick click) {
    }

    @Override
    public final void handleDrag(ItemDragClick drag) {
        if (this.evaluateDrag(drag)) {
            this.onDrag(drag);
        } else {
            this.onDragRejected(drag);
        }
    }

    /**
     * 判断当前槽位的拖拽回调是否进入正常处理.
     * <p>返回 false 不取消已经处理的拖拽事务.</p>
     *
     * @param drag 拖拽上下文
     * @return 允许继续时返回 true
     */
    protected boolean evaluateDrag(ItemDragClick drag) {
        return true;
    }

    /**
     * 处理被 {@link #evaluateDrag(ItemDragClick)} 拒绝的拖拽回调.
     *
     * @param drag 拖拽上下文
     */
    protected void onDragRejected(ItemDragClick drag) {
    }

    /**
     * 处理拖拽经过此 Item 的事件.
     *
     * @param drag 拖拽上下文
     */
    protected void onDrag(ItemDragClick drag) {
    }

    @Override
    public final void handleBundleSelect(@NotNull BundleSelectClick select) {
        if (this.evaluateBundleSelect(select)) {
            this.onBundleSelect(select);
        } else {
            this.onBundleSelectRejected(select);
        }
    }

    /**
     * 判断 Bundle 选择回调是否进入正常处理.
     * <p>返回 false 不恢复已经更新的选择状态.</p>
     *
     * @param select 选择上下文
     * @return 允许继续时返回 true
     */
    protected boolean evaluateBundleSelect(BundleSelectClick select) {
        return true;
    }

    /**
     * 处理被 {@link #evaluateBundleSelect(BundleSelectClick)} 拒绝的 Bundle 选择回调.
     *
     * @param select 选择上下文
     */
    protected void onBundleSelectRejected(BundleSelectClick select) {
    }

    /**
     * 处理 Bundle 选择事件.
     *
     * @param select 选择上下文
     */
    protected void onBundleSelect(BundleSelectClick select) {
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
