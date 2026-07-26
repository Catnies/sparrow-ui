package net.momirealms.sparrow.ui.inventory;

/**
 * 批量算法的操作类别, 每个类别可独立配置迭代顺序.
 */
public enum OperationCategory {
    /** 放入类操作: add, putItem 以及点击语义中的快速转移目标选择. */
    ADD,
    /** 收集类操作: collect 以及 double-click 收集. */
    COLLECT,
    /** 其余操作: remove 家族等. */
    OTHER
}
