package net.momirealms.sparrow.ui.inventory.operation;

/**
 * 批量算法的操作类别, 每个类别可独立配置迭代顺序.
 */
public enum OperationCategory {
    ADD,        // 放入类操作, 包括 add, putItem 与点击语义里的快速转移选目标.
    COLLECT,    // 收集类操作, 包括 collect 与 double-click 收集.
    OTHER       // 其余操作, 例如 remove.
}
