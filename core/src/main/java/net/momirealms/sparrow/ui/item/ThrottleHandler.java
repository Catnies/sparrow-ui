package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.ItemClick;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 点击被节流拦截时接收通知的回调.
 *
 * <p>同一玩家对同一 Item 在节流间隔内的重复点击不会执行正常点击处理器,
 * 而是分发给此处理器, 并附带距离解除节流还剩多少毫秒.
 */
@FunctionalInterface
public interface ThrottleHandler {

    /**
     * 处理一次被节流拦截的点击.
     *
     * @param item 被点击的 Item
     * @param click 点击信息
     * @param remainingMillis 距离再次允许点击的剩余毫秒数, 至少为 1 且不超过节流间隔
     */
    void accept(@NotNull Item item, @NotNull ItemClick click, long remainingMillis);

    /**
     * 在当前处理器之后继续执行另一个处理器.
     *
     * @param after 后续处理器
     * @return 组合后的处理器
     */
    @NotNull
    default ThrottleHandler andThen(@NotNull ThrottleHandler after) {
        Objects.requireNonNull(after, "after");
        return (item, click, remainingMillis) -> {
            this.accept(item, click, remainingMillis);
            after.accept(item, click, remainingMillis);
        };
    }
}
