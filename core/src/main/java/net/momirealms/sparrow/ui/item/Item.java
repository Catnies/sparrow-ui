package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public interface Item {
    Item EMPTY = new EmptyItem(); // 共享的空 Item 实例

    static Item empty() {
        return EMPTY;
    }

    static Item simple(@NotNull ItemStack itemStack) {
        return new StaticItem(ItemProvider.constant(itemStack));
    }

    static Item simple(@NotNull ItemProvider itemProvider) {
        return new StaticItem(itemProvider);
    }

    static ItemBuilder builder() {
        return new ItemBuilder();
    }

    @NotNull
    ItemProvider getItemProvider();

    /**
     * 获取此 Item 尚无成功渲染结果时使用的占位 Provider.
     * <p>{@link #getItemProvider()} 的 Future 首次成功前, 渲染层显示占位内容;
     * 最近一次成功结果存在时始终优先使用成功结果.
     *
     * @return 占位 Provider
     */
    @NotNull
    default ImmediateItemProvider getPlaceholder() {
        return ItemProvider.EMPTY;
    }

    /**
     * 处理玩家点击物品事件.
     *
     * @param click 点击事件上下文
     */
    default void handleClick(ItemClick click) {
    }

    /**
     * 处理玩家拖拽经过此物品的事件.
     *
     * @param drag 拖拽上下文
     */
    default void handleDrag(ItemDragClick drag) {
    }

    /**
     * 处理玩家在 Bundle 物品中选择槽位的事件.
     *
     * @param select 选择上下文
     */
    default void handleBundleSelect(BundleSelectClick select) {
    }

    /**
     * 将此 Item 挂载到一个最终显示槽位.
     * <p>挂载是按显示路径的, 同一个 Item 显示给多名玩家就有多次挂载.
     *
     * @param window 本次挂载所属的窗口
     * @param observer Item 主动失效时接收通知的观察者
     * @return 本次显示关系
     */
    default ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(observer, "observer");
        return ItemAttachment.PASSIVE;
    }
}
