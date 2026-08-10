package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.click.BundleSelectClick;
import net.momirealms.sparrow.ui.click.ItemClick;
import net.momirealms.sparrow.ui.click.ItemDragClick;
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

final class ConfiguredItem implements ObservableItem {
    private final ItemBuilder.DisplaySource displaySource; // 显示来源, 决定渲染提供器与自带刷新计划
    private final RefreshPlan refreshPlan; // 显示来源自带计划与显式计划合并后的周期刷新计划
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;     // 点击处理器链
    private final BiConsumer<? super Item, ? super ItemDragClick> dragHandler;       // 拖拽处理器链
    private final BiConsumer<? super Item, ? super BundleSelectClick> bundleHandler; // Bundle 选择处理器链
    private final boolean updateOnClick; // 点击成功后是否主动失效
    @Nullable private final ThrottleConfig throttleConfig; // null 表示未启用节流
    @Nullable private final Map<Player, Long> throttleTimestamps; // 仅启用节流时非空: 玩家 -> 上次接受点击的毫秒时间 // todo 这里可能会内存泄露
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>(); // 挂载观察者注册表, 负责广播失效

    ConfiguredItem(
            @NotNull ItemBuilder.SourceSpec source,
            @NotNull RefreshPlan explicitRefreshPlan,
            @NotNull BiConsumer<? super Item, ? super ItemClick> clickHandler,
            @NotNull BiConsumer<? super Item, ? super ItemDragClick> dragHandler,
            @NotNull BiConsumer<? super Item, ? super BundleSelectClick> bundleHandler,
            boolean updateOnClick,
            @Nullable ThrottleConfig throttleConfig
    ) {
        this.displaySource = Objects.requireNonNull(source.create(this::notifyWindows), "source result");
        // 合并显示来源自带的刷新计划(如轮播帧周期)与构建器显式配置的计划
        this.refreshPlan = this.displaySource.refreshPlan().or(explicitRefreshPlan);
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.dragHandler = Objects.requireNonNull(dragHandler, "dragHandler");
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

    // 启用节流时, 间隔内的重复点击交给节流处理器而不执行正常点击逻辑;
    @Override
    public void handleClick(ItemClick click) {
        ThrottleConfig config = this.throttleConfig;
        if (config != null) {
            long now = System.currentTimeMillis();
            long interval = config.intervalMillis();
            boolean accepted;
            long remaining = 0;
            assert this.throttleTimestamps != null;
            // 时间戳的检查与写入必须在同一短锁内完成, 避免并发点击穿透节流
            synchronized (this.throttleTimestamps) {
                Long last = this.throttleTimestamps.get(click.player());
                long elapsed = last == null ? Long.MAX_VALUE : now - last;
                if (elapsed >= interval) {
                    this.throttleTimestamps.put(click.player(), now);
                    accepted = true;
                } else {
                    // 系统时钟回拨时 interval - elapsed 可能超过 interval, 需要钳制到间隔内
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

    // 拖拽不走节流: 一次手势可能同时命中同一个 Item 的多个槽位, 节流会把第二站之后全部拦掉.
    @Override
    public void handleDrag(ItemDragClick drag) {
        this.dragHandler.accept(this, drag);
    }

    @Override
    public void handleBundleSelect(@NotNull BundleSelectClick select) {
        this.bundleHandler.accept(this, select);
    }

    // 登记观察者并触发显示来源的首次挂载回调(如启动异步加载); 回调失败时回滚订阅, 避免观察者泄漏.
    @Override
    public ItemAttachment attach(@NotNull Observer<? super Item> observer) {
        Subscription subscription = this.observers.subscribe(observer);
        try {
            this.displaySource.onAttached();
            return ItemAttachment.subscribed(this.refreshPlan, subscription);
        } catch (RuntimeException | Error throwable) {
            // onAttached 失败时回滚订阅, 避免观察者泄漏.
            subscription.close();
            throw throwable;
        }
    }

    @Override
    public void notifyWindows() {
        this.observers.publish(this);
    }

    // 一份 Item 共用的只读节流配置.
    record ThrottleConfig(long intervalMillis, @Nullable ThrottleHandler handler) {
        ThrottleConfig {
            // null 处理器统一替换为空操作, 使用方无需判空
            if (handler == null) {
                handler = (ignoredItem, ignoredClick, ignoredRemainingMillis) -> { };
            }
        }
    }
}
