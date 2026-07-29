package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.NotNull;

/**
 * Item 与一个最终显示槽位之间的挂载关系.
 * <p>挂载同时携带主动失效订阅和被动周期刷新计划. Window 在替换显示路径或关闭时必须调用 {@link #close()}.
 */
public interface ItemAttachment extends AutoCloseable {
    /** 不携带订阅(不主动失效)与周期刷新需求的共享挂载实例. */
    ItemAttachment PASSIVE = new ItemAttachment() {
        /**
         * {@inheritDoc}
         */
        @Override
        public RefreshPlan refreshPlan() {
            return RefreshPlan.none();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
        }
    };

    /**
     * 创建携带主动失效订阅与周期刷新计划的挂载, 关闭时会同时关闭订阅.
     *
     * @param refreshPlan 周期刷新计划
     * @param subscription 主动失效订阅
     * @return 挂载实例
     */
    static ItemAttachment subscribed(@NotNull RefreshPlan refreshPlan, @NotNull Subscription subscription) {
        return new ItemAttachment() {
            /**
             * {@inheritDoc}
             */
            @Override
            public RefreshPlan refreshPlan() {
                return refreshPlan;
            }

            /**
             * {@inheritDoc}
             *
             * <p>同时关闭携带的主动失效订阅.</p>
             */
            @Override
            public void close() {
                subscription.close();
            }
        };
    }

    /**
     * 获取此显示关系需要的周期刷新计划.
     *
     * @return 周期刷新计划, 不需要周期刷新时返回空计划
     */
    RefreshPlan refreshPlan();

    /**
     * 解除此显示关系, 重复关闭不产生额外效果.
     */
    @Override
    void close();
}
