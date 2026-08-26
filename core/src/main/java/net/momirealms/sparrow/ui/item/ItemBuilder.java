package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ItemBuilder {
    // 显示与刷新
    private ItemProvider provider = ItemProvider.EMPTY;                // 显示来源, 只能配置一次
    private ImmediateItemProvider placeholder = ItemProvider.EMPTY;    // 首次成功结果前使用的占位提供器
    private boolean sourceConfigured; // 显示来源是否已完成配置
    private final List<Function<Player, Signal<?>>> dependencies = new ArrayList<>(); // 渲染依赖声明的 signal
    private boolean updateOnClick; // 点击成功后是否主动失效
    // 交互守卫
    @Nullable private ItemGuard<ItemClick> clickGuard;                  // 点击前置处理器链
    @Nullable private ItemGuard<ItemDrag> dragGuard;               // 拖拽前置处理器链
    @Nullable private ItemGuard<BundleSelectClick> bundleSelectGuard;   // Bundle 前置处理器链
    // 交互处理器
    @Nullable private BiConsumer<Item, ItemClick> clickHandler;             // 点击处理器
    @Nullable private BiConsumer<Item, ItemDrag> dragHandler;          // 拖拽处理器
    @Nullable private BiConsumer<Item, BundleSelectClick> bundleHandler;    // Bundle 选择处理器
    // 构建收尾
    private Consumer<ObservableItem> modifier = ignoredItem -> { }; // 构建完成后执行的修改器链

    /**
     * 配置在渲染调用线程立即返回 ItemStack 的显示来源.
     *
     * @param renderer 同步渲染函数, 不得返回 {@code null}
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProvider(@NotNull Function<RenderContext, ItemStack> renderer) {
        return this.setItemProviderAsync(ItemProvider.sync(renderer));
    }

    /**
     * 配置固定显示的物品. 传入的模板会被复制.
     *
     * @param itemStack 固定物品模板
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderConstant(@NotNull ItemStack itemStack) {
        return this.setItemProviderAsync(ItemProvider.constant(itemStack));
    }

    /**
     * 配置异步显示来源. Future 未完成时显示最近一次成功结果, 首次完成前显示空物品.
     *
     * @param itemProvider 显示提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderAsync(@NotNull ItemProvider itemProvider) {
        return this.setItemProviderAsync(itemProvider, ItemProvider.EMPTY);
    }

    /**
     * 配置异步显示来源及首次成功前显示的占位物品.
     *
     * @param itemProvider 显示提供器
     * @param placeholder 首次成功结果前显示的占位物品
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderAsync(@NotNull ItemProvider itemProvider, @NotNull ItemStack placeholder) {
        return this.setItemProviderAsync(itemProvider, ItemProvider.constant(placeholder));
    }

    /**
     * 配置异步显示来源及首次成功前使用的占位 Provider.
     *
     * @param itemProvider 显示提供器
     * @param placeholder 首次成功结果前使用的占位提供器
     * @return 此构建器
     * @throws IllegalStateException 当显示来源已经配置过时
     */
    public ItemBuilder setItemProviderAsync(@NotNull ItemProvider itemProvider, @NotNull ImmediateItemProvider placeholder) {
        this.setSource(itemProvider, placeholder);
        return this;
    }

    /**
     * 让 Item 在被显示期间每隔固定 tick 重新渲染一次.
     *
     * @param periodTicks 正数 tick 周期
     * @return 此构建器
     * @throws IllegalArgumentException 当周期不是正数时
     */
    public ItemBuilder updatePeriodically(int periodTicks) {
        return this.dependsOn(Signals.everyTicks(periodTicks));
    }

    /**
     * 声明渲染读取的 Signal. 任一 Signal 失效时重新渲染这个 Item.
     *
     * @param signals 渲染依赖的数据源
     * @return 此构建器
     */
    public ItemBuilder dependsOn(@NotNull Signal<?>... signals) {
        for (int index = 0; index < signals.length; index++) {
            Signal<?> signal = signals[index];
            this.dependencies.add(ignoredViewer -> signal);
        }
        return this;
    }

    /**
     * 声明按查看者 UUID 取值的渲染依赖.
     *
     * @param signal 按玩家分区的数据源
     * @return 此构建器
     */
    public ItemBuilder dependsOn(@NotNull PlayerKeyedSignal<?> signal) {
        this.dependencies.add(viewer -> signal.at(viewer.getUniqueId()));
        return this;
    }

    /**
     * 声明通过查看者计算分区键的渲染依赖.
     *
     * @param <K> 分区键类型
     * @param signal 分区数据源
     * @param keyOf 从查看者导出分区 key, 在挂载时执行
     * @return 此构建器
     */
    public <K> ItemBuilder dependsOn(@NotNull KeyedSignal<K, ?> signal, @NotNull Function<Player, K> keyOf) {
        this.dependencies.add(viewer -> signal.at(keyOf.apply(viewer)));
        return this;
    }

    /**
     * 让 Item 在点击守卫全部通过且处理器正常返回后主动失效.
     *
     * @return 此构建器
     */
    public ItemBuilder updateOnClick() {
        this.updateOnClick = true;
        return this;
    }

    /**
     * 添加点击守卫. 守卫按添加顺序执行, 第一个 {@code false} 会终止本次点击.
     *
     * @param guard 点击守卫
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<ItemClick> guard) {
        ItemGuard<ItemClick> current = this.clickGuard;
        this.clickGuard = current == null ? guard : current.and(guard);
        return this;
    }

    /**
     * 添加点击守卫与拒绝回调.
     *
     * @param guard 点击守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<ItemClick> guard, @NotNull Consumer<ItemClick> onRejected) {
        return this.addClickGuard(guard, (ignoredItem, click) -> onRejected.accept(click));
    }

    /**
     * 添加可访问 Item 自身的点击守卫与拒绝回调.
     *
     * @param guard 点击守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addClickGuard(@NotNull ItemGuard<ItemClick> guard, @NotNull BiConsumer<Item, ItemClick> onRejected) {
        ItemGuard<ItemClick> current = this.clickGuard;
        this.clickGuard = current == null
                ? guard.onRejected(onRejected)
                : current.and(guard, onRejected);
        return this;
    }

    /**
     * 添加点击处理器. 处理器按添加顺序执行.
     *
     * @param clickHandler 点击处理器
     * @return 此构建器
     */
    public ItemBuilder addClickHandler(@NotNull Consumer<ItemClick> clickHandler) {
        return this.addClickHandler((ignoredItem, click) -> clickHandler.accept(click));
    }

    /**
     * 添加可访问 Item 自身的点击处理器.
     *
     * @param clickHandler 同时接收物品和点击事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addClickHandler(@NotNull BiConsumer<Item, ItemClick> clickHandler) {
        BiConsumer<Item, ItemClick> current = this.clickHandler;
        this.clickHandler = current == null ? clickHandler : current.andThen(clickHandler);
        return this;
    }

    /**
     * 添加拖拽守卫.
     *
     * @param guard 拖拽守卫
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<ItemDrag> guard) {
        ItemGuard<ItemDrag> current = this.dragGuard;
        this.dragGuard = current == null ? guard : current.and(guard);
        return this;
    }

    /**
     * 添加拖拽守卫与拒绝回调.
     *
     * @param guard 拖拽守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<ItemDrag> guard, @NotNull Consumer<ItemDrag> onRejected) {
        return this.addDragGuard(guard, (ignoredItem, drag) -> onRejected.accept(drag));
    }

    /**
     * 添加可访问 Item 自身的拖拽守卫与拒绝回调.
     *
     * @param guard 拖拽守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addDragGuard(@NotNull ItemGuard<ItemDrag> guard, @NotNull BiConsumer<Item, ItemDrag> onRejected) {
        ItemGuard<ItemDrag> current = this.dragGuard;
        this.dragGuard = current == null
                ? guard.onRejected(onRejected)
                : current.and(guard, onRejected);
        return this;
    }

    /**
     * 添加拖拽处理器.
     *
     * @param dragHandler 拖拽处理器
     * @return 此构建器
     */
    public ItemBuilder addDragHandler(@NotNull Consumer<ItemDrag> dragHandler) {
        return this.addDragHandler((ignoredItem, drag) -> dragHandler.accept(drag));
    }

    /**
     * 添加可访问 Item 自身的拖拽处理器.
     *
     * @param dragHandler 同时接收物品和拖拽事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addDragHandler(@NotNull BiConsumer<Item, ItemDrag> dragHandler) {
        BiConsumer<Item, ItemDrag> current = this.dragHandler;
        this.dragHandler = current == null ? dragHandler : current.andThen(dragHandler);
        return this;
    }

    /**
     * 添加 Bundle 选择守卫.
     *
     * @param guard Bundle 选择守卫
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<BundleSelectClick> guard) {
        ItemGuard<BundleSelectClick> current = this.bundleSelectGuard;
        this.bundleSelectGuard = current == null ? guard : current.and(guard);
        return this;
    }

    /**
     * 添加 Bundle 选择守卫与拒绝回调.
     *
     * @param guard Bundle 选择守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<BundleSelectClick> guard, @NotNull Consumer<BundleSelectClick> onRejected) {
        return this.addBundleSelectGuard(guard, (ignoredItem, select) -> onRejected.accept(select));
    }

    /**
     * 添加可访问 Item 自身的 Bundle 选择守卫与拒绝回调.
     *
     * @param guard Bundle 选择守卫
     * @param onRejected 此守卫返回 {@code false} 时执行的回调
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectGuard(@NotNull ItemGuard<BundleSelectClick> guard, @NotNull BiConsumer<Item, BundleSelectClick> onRejected) {
        ItemGuard<BundleSelectClick> current = this.bundleSelectGuard;
        this.bundleSelectGuard = current == null
                ? guard.onRejected(onRejected)
                : current.and(guard, onRejected);
        return this;
    }

    /**
     * 添加 Bundle 选择处理器. 处理器按添加顺序执行.
     *
     * @param selectHandler 选择处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectHandler(@NotNull Consumer<BundleSelectClick> selectHandler) {
        return this.addBundleSelectHandler((ignoredItem, select) -> selectHandler.accept(select));
    }

    /**
     * 添加可访问 Item 自身的 Bundle 选择处理器.
     *
     * @param selectHandler 同时接收物品和选择事件的处理器
     * @return 此构建器
     */
    public ItemBuilder addBundleSelectHandler(@NotNull BiConsumer<Item, BundleSelectClick> selectHandler) {
        BiConsumer<Item, BundleSelectClick> current = this.bundleHandler;
        this.bundleHandler = current == null ? selectHandler : current.andThen(selectHandler);
        return this;
    }

    /**
     * 添加构建后修改器. 修改器按添加顺序执行.
     * <p>修改器抛出的异常由 {@link #build()} 直接抛出, 后续修改器不再执行.
     *
     * @param modifier 构建完成后的修改器
     * @return 此构建器
     */
    public ItemBuilder addModifier(@NotNull Consumer<? super ObservableItem> modifier) {
        this.modifier = this.modifier.andThen(modifier);
        return this;
    }

    /**
     * 构建具备主动通知能力的 Item.
     *
     * @return 构建完成的 ObservableItem
     */
    public ObservableItem build() {
        ObservableItem item = new ConfiguredItem(
                this.provider,
                this.placeholder,
                this.dependencies,
                this.clickGuard,
                this.dragGuard,
                this.bundleSelectGuard,
                this.clickHandler,
                this.dragHandler,
                this.bundleHandler,
                this.updateOnClick
        );
        this.modifier.accept(item);
        return item;
    }

    // 默认空来源不占用配置次数, 显式来源只能设置一次.
    private void setSource(ItemProvider provider, ImmediateItemProvider placeholder) {
        if (this.sourceConfigured)
            throw new IllegalStateException("display source has already been configured");
        this.provider = provider;
        this.placeholder = placeholder;
        this.sourceConfigured = true;
    }
}
