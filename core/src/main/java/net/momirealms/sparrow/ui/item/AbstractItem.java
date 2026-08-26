package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public abstract class AbstractItem implements ObservableItem {
    private final ItemProvider itemProvider;
    private final ImmediateItemProvider placeholder;
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>(); // 失效广播派发器, notifyWindows 经它送达所有观察者
    private final CopyOnWriteArrayList<Function<Player, ? extends Signal<?>>> dependencies = new CopyOnWriteArrayList<>(); // 渲染依赖声明.

    protected AbstractItem() {
        this.itemProvider = this::render;
        this.placeholder = ItemProvider.sync(this::placeholder);
    }

    /**
     * 声明渲染读取了哪些数据源, 它们失效时重新渲染这个 Item.
     * <p><strong>只应在子类构造器里调用.</strong>
     *
     * @param signals 渲染依赖的数据源
     */
    protected final void dependsOn(@NotNull Signal<?>... signals) {
        for (int index = 0; index < signals.length; index++) {
            Signal<?> signal = signals[index];
            this.dependencies.add(ignoredViewer -> signal);
        }
    }

    /**
     * 声明按查看者 UUID 取值的渲染依赖.
     * <p><strong>只应在子类构造器里调用.</strong>
     *
     * @param signal 按玩家分区的数据源
     */
    protected final void dependsOn(@NotNull PlayerKeyedSignal<?> signal) {
        this.dependencies.add(viewer -> signal.at(viewer.getUniqueId()));
    }

    /**
     * 声明通过查看者计算分区键的渲染依赖.
     * <p><strong>只应在子类构造器里调用.</strong>
     *
     * @param <K> 分区键类型
     * @param signal 分区数据源
     * @param keyOf 从查看者取得分区键的函数, 每次挂载时调用
     */
    protected final <K> void dependsOn(@NotNull KeyedSignal<K, ?> signal, @NotNull Function<? super Player, ? extends K> keyOf) {
        this.dependencies.add(viewer -> signal.at(keyOf.apply(viewer)));
    }

    /**
     * 为当前显示位置计算物品内容.
     * <p>实现必须遵守 {@link ItemProvider#provide(RenderContext)} 的渲染约束.
     *
     * @param context 渲染上下文
     * @return 本次显示结果的 Future
     */
    @NotNull
    protected abstract CompletableFuture<ItemStack> render(RenderContext context);

    /**
     * 返回此显示位置首次渲染完成前使用的占位物品.
     * <p>后续刷新尚未完成时继续显示最近一次成功结果, 不会重新退回占位物品.
     *
     * @param context 渲染上下文
     * @return 首次完成前显示的占位物品
     */
    @NotNull
    protected ItemStack placeholder(@NotNull RenderContext context) {
        return ItemUtils.EMPTY;
    }

    @NotNull
    @Override
    public final ItemProvider getItemProvider() {
        return this.itemProvider;
    }

    @NotNull
    @Override
    public final ImmediateItemProvider getPlaceholder() {
        return this.placeholder;
    }

    @Override
    public ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
        ItemAttachment.Tracking attachment = ItemAttachment.tracking(this, observer);
        // 观察者和依赖必须一同生效, 任一订阅失败都撤销本次挂载.
        try {
            attachment.track(this.observers.subscribe(observer));
            attachment.subscribeDependencies(this.dependencies, window.viewer());
            return attachment;
        } catch (RuntimeException | Error throwable) {
            // 保留原始挂载异常, 清理异常作为补充信息.
            try {
                attachment.close();
            } catch (RuntimeException | Error closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    @Override
    public final void notifyWindows() {
        this.observers.publish(this);
    }
}
