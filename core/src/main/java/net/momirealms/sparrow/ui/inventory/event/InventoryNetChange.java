package net.momirealms.sparrow.ui.inventory.event;

/**
 * 一笔事务对当前订阅 Inventory 内容产生的净变化.
 * <p>本枚举描述的是当前 Inventory 逻辑视图的物品净增减, 不是点击动作或整笔根事务的类型.
 */
public enum InventoryNetChange {
    NONE,     // 槽位可能发生过变更, 但相似物品跨槽抵消后没有净增减
    ADDITION, // 只有物品净增加
    REMOVAL,  // 只有物品净移除
    MIXED     // 同时存在不同物品的净增加和净移除
}