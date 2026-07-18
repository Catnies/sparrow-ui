package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.BundleSelect;
import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public interface Item {
    Item EMPTY = new EmptyItem();

    /**
     * 创建空的 Item.
     *
     * @return 使用 {@link EmptyItem} 的空 Item
     */
    static Item empty() {
        return EMPTY;
    }

    /**
     * 创建没有主动更新能力的简单 Item.
     *
     * @param itemProvider ItemProvider
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
    ItemProvider getItemProvider();

    /**
     * 处理玩家点击物品事件.
     *
     * @param click 点击事件上下文
     */
    default void handleClick(ItemClick click) {
    }

    /**
     * 处理玩家在 Bundle 物品中选择槽位的事件.
     *
     * @param select 选择上下文
     */
    default void handleBundleSelect(BundleSelect select) {
    }

    /**
     * 将此 Item 挂载到一个最终显示槽位.
     *
     * <p>返回值拥有本次显示关系. Window 在替换路径或关闭时必须关闭它.
     * 不会主动变化的 Item 返回不保存观察者的共享挂载.</p>
     *
     * @param observer Item 主动失效时接收通知的观察者
     * @return 本次显示关系
     */
    default ItemAttachment attach(@NotNull Observer<? super Item> observer) {
        Objects.requireNonNull(observer, "observer");
        return ItemAttachment.passive();
    }
}
