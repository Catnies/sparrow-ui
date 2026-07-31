package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;

/**
 * Item 与一个最终显示槽位之间的挂载关系.
 * Window 在替换显示路径或关闭时必须调用 {@link #close()}.
 */
public interface ItemAttachment extends AutoCloseable {

    // 不携带订阅(不主动失效)与周期刷新需求的共享挂载实例.
    ItemAttachment PASSIVE = new ItemAttachment() {

        @Override
        public RefreshPlan refreshPlan() {
            return RefreshPlan.none();
        }

        @Override
        public void close() {
        }
    };

    // 携带主动失效订阅与周期刷新计划的挂载, 关闭时会同时关闭订阅.
    static ItemAttachment subscribed(@NotNull RefreshPlan refreshPlan, @NotNull Subscription subscription) {
        return new ItemAttachment() {

            @Override
            public RefreshPlan refreshPlan() {
                return refreshPlan;
            }

            @Override
            public void close() {
                subscription.close();
            }
        };
    }

    // 获取此显示关系需要的周期刷新计划.
    RefreshPlan refreshPlan();

    // 解除此显示关系, 重复关闭不产生额外效果.
    @Override
    void close();
}
