package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Item 与一个最终显示槽位之间的挂载关系.
 * <p><strong>显示路径被替换或关闭时必须调用 {@link #close()}.</strong>
 */
public interface ItemAttachment extends AutoCloseable {
    ItemAttachment PASSIVE = () -> {};

    // 解除显示关系. 重复关闭不产生额外效果.
    @Override
    void close();

    /**
     * 创建一次会持有订阅的挂载.
     *
     * @param item 被挂载的 Item
     * @param observer 本次挂载的失效观察者
     * @return 新的挂载
     */
    @NotNull
    static Tracking tracking(@NotNull Item item, @NotNull Observer<? super Item> observer) {
        return new Tracking(item, observer);
    }

    /**
     * 持有本次挂载取得的全部订阅, 并把依赖失效转成对这一条显示路径的标脏.
     * <p>依赖订阅按挂载建立, 按查看者分区的依赖失效只影响对应玩家的槽位.
     */
    final class Tracking implements ItemAttachment {
        private final Item item;
        private final Observer<? super Item> observer;
        private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Tracking(Item item, Observer<? super Item> observer) {
            this.item = item;
            this.observer = observer;
        }

        void track(@NotNull Subscription subscription) {
            this.subscriptions.add(subscription);
        }

        void subscribeDependencies(
                @NotNull List<Function<Player, Signal<?>>> dependencies,
                @NotNull Player viewer
        ) {
            for (int index = 0; index < dependencies.size(); index++) {
                Signal<?> signal = dependencies.get(index).apply(viewer);
                this.track(signal.onDirty(this::dirty));
            }
        }

        private void dirty() {
            if (this.closed.get()) return;
            this.observer.onUpdate(this.item);
        }

        @Override
        public void close() {
            if (!this.closed.compareAndSet(false, true)) {
                return;
            }
            // 单个订阅关闭失败不应阻断其余清理.
            RuntimeException failure = null;
            for (Subscription subscription : this.subscriptions) {
                try {
                    subscription.close();
                } catch (RuntimeException exception) {
                    failure = ThrowableUtils.combine(failure, exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
