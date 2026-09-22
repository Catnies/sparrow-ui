package net.momirealms.sparrow.ui.inventory.event;

/**
 * 本次库存点击预计产生的操作, 包括收纳袋内部的存入和取出.
 */
public enum InventoryClickAction {
    /** 没有物品变化. */
    NOTHING,
    /** 将槽位的整组物品拿到光标. */
    PICKUP_ALL,
    /** 将槽位的部分物品拿到光标. */
    PICKUP_SOME,
    /** 将槽位的一半物品拿到光标. */
    PICKUP_HALF,
    /** 将槽位的一个物品拿到光标. */
    PICKUP_ONE,
    /** 将光标的整组物品放入槽位. */
    PLACE_ALL,
    /** 将光标的部分物品放入槽位. */
    PLACE_SOME,
    /** 将光标的一个物品放入槽位. */
    PLACE_ONE,
    /** 交换光标与槽位的物品. */
    SWAP_WITH_CURSOR,
    /** 丢出光标的整组物品. */
    DROP_ALL_CURSOR,
    /** 丢出光标的一个物品. */
    DROP_ONE_CURSOR,
    /** 丢出槽位的整组物品. */
    DROP_ALL_SLOT,
    /** 丢出槽位的一个物品. */
    DROP_ONE_SLOT,
    /** 将物品移至另一侧库存的可用槽位. */
    MOVE_TO_OTHER_INVENTORY,
    /** 将槽位物品移至快捷栏, 原快捷栏物品重新放回背包. */
    HOTBAR_MOVE_AND_READD,
    /** 交换槽位与快捷栏或副手物品. */
    HOTBAR_SWAP,
    /** 将槽位物品复制为最大堆叠放到光标. */
    CLONE_STACK,
    /** 从库存收集同类物品到光标. */
    COLLECT_TO_CURSOR,
    /** 无法识别本次操作. */
    UNKNOWN,
    /** 从槽位中的收纳袋取出选中的一组物品到光标. */
    PICKUP_FROM_BUNDLE,
    /** 将槽位的全部物品装入光标上的收纳袋. */
    PICKUP_ALL_INTO_BUNDLE,
    /** 将槽位的部分物品装入光标上的收纳袋. */
    PICKUP_SOME_INTO_BUNDLE,
    /** 从光标上的收纳袋取出选中的一组物品到槽位. */
    PLACE_FROM_BUNDLE,
    /** 将光标的全部物品装入槽位中的收纳袋. */
    PLACE_ALL_INTO_BUNDLE,
    /** 将光标的部分物品装入槽位中的收纳袋. */
    PLACE_SOME_INTO_BUNDLE
}
