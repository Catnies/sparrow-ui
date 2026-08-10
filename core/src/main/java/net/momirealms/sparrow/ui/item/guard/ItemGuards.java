package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import org.bukkit.GameMode;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class ItemGuards {

    private ItemGuards() {
    }

    @NotNull
    public static ItemGuard<ItemInteraction> permission(@NotNull String permission) {
        Objects.requireNonNull(permission, "permission");
        return (ignoredItem, interaction) -> interaction.player().hasPermission(permission);
    }

    @NotNull
    public static ItemGuard<ItemInteraction> gameMode(@NotNull GameMode gameMode) {
        Objects.requireNonNull(gameMode, "gameMode");
        return (ignoredItem, interaction) -> interaction.player().getGameMode() == gameMode;
    }

    /**
     * 创建按 Item 与玩家分别计时的节流规则.
     * <p>首次点击立即通过, 限制期内的拒绝不会延长间隔.</p>
     *
     * @param intervalMillis 两次有效点击之间至少间隔的毫秒数
     * @return 节流守卫
     * @throws IllegalArgumentException 间隔不是正数时抛出
     */
    @NotNull
    public static ItemGuard<ItemClick> throttle(long intervalMillis) {
        return new ThrottleGuard(intervalMillis, System::currentTimeMillis);
    }
}
