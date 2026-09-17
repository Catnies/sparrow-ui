package net.momirealms.sparrow.ui.inventory.operation;

/**
 * 每个类别可以各配一套迭代顺序.
 */
public enum OperationCategory {
    ADD,        // 放入与快速转移目标选择
    COLLECT,    // 收集与双击收集
    OTHER       // 其余批量操作
}
