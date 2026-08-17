package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDragClick;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public interface Item {
    Item EMPTY = new EmptyItem(); // 共享的空 Item 实例

    /**
     * 创建空的 Item.
     *
     * @return 使用 {@link EmptyItem} 的空 Item
     */
    static Item empty() {
        return EMPTY;
    }

    /**
     * 创建以固定物品堆显示, 没有主动更新能力的简单 Item.
     *
     * @param itemStack 固定显示的物品堆
     * @return 使用 {@link StaticItem} 快路径的 Item
     */
    static Item simple(@NotNull ItemStack itemStack) {
        return new StaticItem(ItemProvider.constant(itemStack));
    }

    /**
     * 创建没有主动更新能力的简单 Item.
     *
     * @param itemProvider 显示提供器
     * @return 使用 {@link StaticItem} 快路径的 Item
     */
    static Item simple(@NotNull ItemProvider itemProvider) {
        return new StaticItem(itemProvider);
    }

    /**
     * 创建一个声明式 Item 构建器.
     *
     * @return 新构建器
     */
    static ItemBuilder builder() {
        return new ItemBuilder();
    }

    /**
     * 获取 {@link ItemProvider}.
     *
     * @return 此 Item 使用的 Provider
     */
    @NotNull
    ItemProvider getItemProvider();

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
        // 默认实现不保存观察者, 但仍提前拒绝 null 以固定契约
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(observer, "observer");
        return ItemAttachment.PASSIVE;
    }
}
