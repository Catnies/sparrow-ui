package net.momirealms.sparrow.ui.inventory.event;

public enum InventoryNetChange {
    NONE,     // 槽位可能发生过变更, 但相似物品跨槽抵消后没有净增减
    ADDITION, // 只有物品净增加
    REMOVAL,  // 只有物品净移除
    MIXED     // 同时存在不同物品的净增加和净移除
}