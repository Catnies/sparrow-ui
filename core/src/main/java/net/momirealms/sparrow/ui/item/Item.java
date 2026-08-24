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
    Item EMPTY = new EmptyItem();

    /**
     * 返回共享的空 Item.
     *
     * @return 空 Item
     */
    static Item empty() {
        return EMPTY;
    }

    /**
     * 创建显示固定物品的 Item. 传入的物品会在创建 Provider 时复制.
     *
     * @param itemStack 显示模板
     * @return 静态 Item
     */
    static Item simple(@NotNull ItemStack itemStack) {
        return new StaticItem(ItemProvider.constant(itemStack));
    }

    /**
     * 创建由指定 Provider 渲染的 Item.
     *
     * @param itemProvider 显示内容来源
     * @return 静态 Item
     */
    static Item simple(@NotNull ItemProvider itemProvider) {
        return new StaticItem(itemProvider);
    }

    /**
     * 创建 Item Builder.
     *
     * @return 新的 Builder
     */
    static ItemBuilder builder() {
        return new ItemBuilder();
    }

    /**
     * 返回此 Item 的显示内容来源.
     *
     * @return 显示内容来源
     */
    @NotNull
    ItemProvider getItemProvider();

    /**
     * 返回此 Item 首次渲染成功前使用的占位 Provider.
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
     * 处理一次物品点击.
     *
     * @param click 点击事件上下文
     */
    default void handleClick(ItemClick click) {
    }

    /**
     * 处理一次经过此物品的拖拽.
     *
     * @param drag 拖拽上下文
     */
    default void handleDrag(ItemDragClick drag) {
    }

    /**
     * 处理一次 Bundle 槽位选择.
     *
     * @param select 选择上下文
     */
    default void handleBundleSelect(BundleSelectClick select) {
    }

    /**
     * 将此 Item 挂载到一个最终显示槽位.
     * <p>挂载是按显示路径的, 同一个 Item 显示给多名玩家就有多次挂载.
     * <p><strong>返回的附件在显示路径被替换或关闭时必须关闭.</strong>
     *
     * @param window 本次挂载所属的窗口
     * @param observer Item 主动失效时接收通知的观察者
     * @return 本次显示关系的附件
     */
    default ItemAttachment attach(@NotNull Window window, @NotNull Observer<? super Item> observer) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(observer, "observer");
        return ItemAttachment.PASSIVE;
    }
}
