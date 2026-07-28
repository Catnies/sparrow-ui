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
 *
 * <p>由 {@link ItemBuilder} 构建. 观察者通过 {@link ObservableDispatcher} 管理,
 * 节流状态按玩家隔离且互不影响.
 */
final class ConfiguredItem implements ObservableItem {
    private final ItemBuilder.DisplaySource displaySource; // 显示来源, 决定渲染提供器与自带刷新计划
    private final RefreshPlan refreshPlan; // 显示来源自带计划与显式计划合并后的周期刷新计划
    private final BiConsumer<? super Item, ? super ItemClick> clickHandler;     // 点击处理器链
    private final BiConsumer<? super Item, ? super BundleSelect> bundleHandler; // Bundle 选择处理器链
    private final boolean updateOnClick; // 点击成功后是否主动失效
    @Nullable private final ThrottleConfig throttleConfig; // null 表示未启用节流
    @Nullable private final Map<Player, Long> throttleTimestamps; // 仅启用节流时非空: 玩家 -> 上次接受点击的毫秒时间
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>(); // 挂载观察者注册表, 负责广播失效

    /**
     * 创建组合 Item, 显示工厂会立即用于创建显示来源.
     *
     * @param displayFactory 显示来源工厂, 接收主动失效回调
     * @param explicitRefreshPlan 构建器显式配置的周期刷新计划
     * @param clickHandler 点击处理器链
     * @param bundleHandler Bundle 选择处理器链
     * @param updateOnClick 点击成功后是否主动失效
     * @param throttleConfig 节流配置, 可为 {@code null}
     */
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
        // 合并显示来源自带的刷新计划(如轮播帧周期)与构建器显式配置的计划
        this.refreshPlan = this.displaySource.refreshPlan().or(explicitRefreshPlan);
        this.clickHandler = Objects.requireNonNull(clickHandler, "clickHandler");
        this.bundleHandler = Objects.requireNonNull(bundleHandler, "bundleHandler");
        this.updateOnClick = updateOnClick;
        this.throttleConfig = throttleConfig;
        // WeakHashMap 不会因节流状态阻止 Player 回收; 所有访问都在 handleClick 的短锁内完成
        this.throttleTimestamps = throttleConfig == null ? null : new WeakHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ItemProvider getItemProvider() {
        return this.displaySource.provider();
    }

    /**
     * {@inheritDoc}
     *
     * <p>启用节流时, 间隔内的重复点击交给节流处理器而不执行正常点击逻辑;
     * 配置了 updateOnClick 时, 点击成功后会主动通知窗口重新渲染.
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleBundleSelect(@NotNull BundleSelect select) {
        this.bundleHandler.accept(this, select);
    }

    /**
     * {@inheritDoc}
     *
     * <p>此实现会登记观察者并触发显示来源的首次挂载回调(如启动异步加载);
     * 回调失败时回滚订阅, 避免观察者泄漏.
     */
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

    /**
     * {@inheritDoc}
     */
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
            // null 处理器统一替换为空操作, 使用方无需判空
            if (handler == null) {
                handler = (ignoredItem, ignoredClick, ignoredRemainingMillis) -> { };
            }
        }
    }
}
