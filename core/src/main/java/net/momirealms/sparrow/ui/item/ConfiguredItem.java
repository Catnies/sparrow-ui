package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * 组合显示来源、失效传播、周期计划和交互行为的内部 Item 实现.
 */
final class ConfiguredItem implements ObservableItem {
    private final ItemBuilder.DisplaySource displaySource;
    private final RefreshPlan refreshPlan;
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;
    private final BiConsumer<? super Item, ? super BundleSelect> bundleHandler;
    private final boolean updateOnClick;
    @Nullable private final ThrottleConfig throttleConfig; // null 表示未启用节流
    @Nullable private final Map<Player, Long> throttleTimestamps; // 仅启用节流时非空: 玩家 -> 上次接受点击的毫秒时间
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>();

    ConfiguredItem(
            @NotNull ItemBuilder.DisplayFactory displayFactory,
            @NotNull RefreshPlan explicitRefreshPlan,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @NotNull BiConsumer<? super Item, ? super BundleSelect> bundleHandler,
            boolean updateOnClick,
            @Nullable ThrottleConfig throttleConfig
    ) {
        ItemBuilder.DisplayFactory checkedFactory = Objects.requireNonNull(displayFactory, "displayFactory");
        this.displaySource = Objects.requireNonNull(checkedFactory.create(this::notifyWindows), "displayFactory result");
        this.refreshPlan = this.displaySource.refreshPlan().or(explicitRefreshPlan);
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.bundleHandler = Objects.requireNonNull(bundleHandler, "bundleHandler");
        this.updateOnClick = updateOnClick;
        this.throttleConfig = throttleConfig;
        // WeakHashMap 不会因节流状态阻止 Player 回收; 所有访问都在 handleClick 的短锁内完成
        this.throttleTimestamps = throttleConfig == null ? null : new WeakHashMap<>();
    }

    @Override
    public ItemProvider getItemProvider() {
        return this.displaySource.provider();
    }

    @Override
    public void handleClick(ItemClick click) {
        ThrottleConfig config = this.throttleConfig;
        if (config != null) {
            Map<Player, Long> timestamps = this.throttleTimestamps;
            long now = System.currentTimeMillis();
            long interval = config.intervalMillis();
            boolean accepted;
            long remaining = 0;
            synchronized (timestamps) {
                Long last = timestamps.get(click.player());
                long elapsed = last == null ? Long.MAX_VALUE : now - last;
                if (elapsed >= interval) {
                    timestamps.put(click.player(), now);
                    accepted = true;
                } else {
                    remaining = Math.min(interval, interval - elapsed);
                    accepted = false;
                }
            }

            // 处理器可能回调菜单系统, 必须在锁外执行
            if (!accepted) {
                assert config.handler() != null;
                config.handler().accept(this, click, remaining);
                return;
            }
        }

        this.clickHandler.accept(this, click);
        if (this.updateOnClick) {
            this.notifyWindows();
        }
    }

    @Override
    public void handleBundleSelect(@NotNull BundleSelect select) {
        this.bundleHandler.accept(this, select);
    }

    @Override
    public ItemAttachment attach(@NotNull Observer<? super Item> observer) {
        Subscription subscription = this.observers.subscribe(observer);
        try {
            this.displaySource.onAttached();
            return ItemAttachment.subscribed(this.refreshPlan, subscription);
        } catch (RuntimeException | Error throwable) {
            subscription.close();
            throw throwable;
        }
    }

    @Override
    public void notifyWindows() {
        this.observers.publish(this);
    }

    /**
     * 一份 Item 共用的只读节流配置.
     *
     * @param intervalMillis 两次有效点击之间至少间隔的毫秒数
     * @param handler 被拦截点击的处理器, null 时替换为空操作
     */
    record ThrottleConfig(long intervalMillis, @Nullable ThrottleHandler handler) {
        ThrottleConfig {
            if (handler == null) {
                handler = (ignoredItem, ignoredClick, ignoredRemainingMillis) -> { };
            }
        }
    }
}
