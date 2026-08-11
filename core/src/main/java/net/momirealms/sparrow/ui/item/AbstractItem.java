package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.PlayerKeyedSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public abstract class AbstractItem implements ObservableItem {
    private final ItemProvider itemProvider = this::render;
    private final ObservableDispatcher<Item> observers = new ObservableDispatcher<>();
    private final CopyOnWriteArrayList<Function<? super Player, ? extends Signal<?>>> dependencies = new CopyOnWriteArrayList<>(); // 渲染依赖声明.

    protected AbstractItem() {
    }

    /**
     * 声明渲染读取了哪些数据源, 它们失效时重新渲染这个 Item.
     * <p><strong>只应在子类构造器里调用.</strong>
     *
     * @param signals 渲染依赖的数据源
     */
    protected final void dependsOn(@NotNull Signal<?>... signals) {
        for (int index = 0; index < signals.length; index++) {
            Signal<?> signal = Objects.requireNonNull(signals[index], "signal");
            this.dependencies.add(ignoredViewer -> signal);
        }
    }

    /**
     * 声明渲染读取了哪些按玩家分区的数据源, 它们失效时重新渲染这个 Item.
     * <p><strong>只应在子类构造器里调用.</strong>
     *
     * @param signal 按玩家分区的数据源
     */
    protected final void dependsOn(@NotNull PlayerKeyedSignal<?> signal) {
        Objects.requireNonNull(signal, "signal");
        this.dependencies.add(viewer -> signal.at(viewer.getUniqueId()));
    }

    /**
     * 声明渲染读取了按任意维度分区的数据源, 分区 key 由查看者导出.
     *
     * @param signal 分区数据源
     * @param keyOf 从查看者导出分区 key, 在挂载时执行
     */
    protected final <K> void dependsOn(@NotNull KeyedSignal<K, ?> signal, @NotNull Function<? super Player, ? extends K> keyOf) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(keyOf, "keyOf");
        this.dependencies.add(viewer -> signal.at(keyOf.apply(viewer)));
    }

    /**
     * 根据当前显示上下文同步渲染物品.
     * <p>此方法遵守 {@link ItemProvider#provide(RenderContext)} 的渲染约束.</p>
     *
     * @param context 渲染上下文
     * @return 本次显示的物品
     */
    protected abstract ItemStack render(@NotNull RenderContext context);

    @Override
    public final ItemProvider getItemProvider() {
        return this.itemProvider;
    }

    @Override
    public final ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(observer, "observer");
        ItemAttachment.Tracking attachment = ItemAttachment.tracking(this, observer);
        try {
            attachment.track(this.observers.subscribe(observer));
            attachment.subscribeDependencies(this.dependencies, window.viewer());
            return attachment;
        } catch (RuntimeException | Error throwable) {
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
