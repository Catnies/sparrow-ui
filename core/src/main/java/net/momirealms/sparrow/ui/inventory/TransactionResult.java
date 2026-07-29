package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.InventoryDelta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次 Inventory 操作事务的提交结果.
 * <p>取消、冲突与执行上下文不可用都是正常控制流, 操作结果通过返回值表达;
 * 调用方可以对 {@link Conflicted} 结果自行决定是否重试, 若重试 pre 处理器会再次运行.
 */
public sealed interface TransactionResult {

    /**
     * 事务已提交, 携带全部参与 Inventory 的修改.
     *
     * @param changes 按调用方声明顺序排列的各Inventory变更
     */
    record Committed(@NotNull List<InventoryDelta> changes) implements TransactionResult {

        public Committed {
            changes = List.copyOf(changes);
        }
    }

    /**
     * PreEvent 事件操作取消
     * <p>所有参与操作的 Inventory 不会变更.
     */
    enum Cancelled implements TransactionResult {
        INSTANCE
    }

    // TODO 默认行为可配置, 默认重试1次.
    /**
     * 乐观校验失败
     * <p>某个参与操作的 Inventory 在 Plan 之后已被其他事务操作修改,
     * 所有参与操作的 Inventory 不会变更.
     */
    enum Conflicted implements TransactionResult {
        INSTANCE
    }

    /**
     * 跨线程访问
     * <p>通常发生在 Foila 上当操作者访问一个其他线程的 {@link ReferencingInventory} 时触发的失败,
     * 所有参与操作的 Inventory 不会变更.
     * <p>跨线程访问具有真实载体的 Bukkit Inventory 是不被允许的.
     * Inventory 会退化成只读视图, 目标 Inventory 回归到操作者所在的线程时可再次尝试.
     */
    enum Unavailable implements TransactionResult {
        INSTANCE
    }
}
