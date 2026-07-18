package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observable;

/**
 * 能够主动宣布显示结果已失效的 Item.
 * 不会主动变化的 Item 不应实现此接口, 从而避免无意义的订阅存储.
 * 订阅者收到的更新值是实际发生变化的 Item.
 */
public interface ObservableItem extends Item, Observable<Item> {
}
