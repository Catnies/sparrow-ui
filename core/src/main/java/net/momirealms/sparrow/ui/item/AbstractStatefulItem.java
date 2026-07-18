package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 为有状态 Item 提供类型化失效传播的基础实现.
 *
 * <p>子类在影响显示结果的状态发生变化后调用 {@link #notifyWindows()}.
 * 失效通知本身不执行渲染, 订阅者只应记录需要刷新的窗口槽位.</p>
 */
public abstract class AbstractStatefulItem implements ObservableItem {
    private final ObservableDispatcher<Item> invalidations = new ObservableDispatcher<>();

    protected AbstractStatefulItem() {
    }

    @Override
    public Subscription subscribe(@NotNull Observer<? super Item> observer) {
        return invalidations.subscribe(observer);
    }

    protected final int subscriptionCount() {
        return invalidations.subscriptionCount();
    }

    /**
     * 通知所有显示此 {@link Item} 的 {@link Window} 更新其对应的 {@link ItemStack ItemStack}.
     * <p> 可以异步调用.
     */
    protected final void notifyWindows() {
        invalidations.publish(this);
    }
}
