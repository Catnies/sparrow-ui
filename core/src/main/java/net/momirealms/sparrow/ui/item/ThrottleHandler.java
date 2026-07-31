package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.click.ItemClick;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

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

    @NotNull
    default ThrottleHandler andThen(@NotNull ThrottleHandler after) {
        Objects.requireNonNull(after, "after");
        return (item, click, remainingMillis) -> {
            this.accept(item, click, remainingMillis);
            after.accept(item, click, remainingMillis);
        };
    }
}
