package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.ReferencingInventory;

/**
 * 一笔事务的触发来源.
 */
public interface UpdateReason {

    /**
     * 程序化操作的原因, 例如初始化, 重置或管理命令触发的写入.
     */
    enum Program implements UpdateReason {
        INSTANCE
    }

    /**
     * 外部世界绕过 Sparrow 直接改了被引用的容器, 由每 tick 的比对发现.
     * 这类变更已经生效, 只派发 post 事件.
     *
     * @see ReferencingInventory#refresh()
     */
    enum External implements UpdateReason {
        INSTANCE
    }
}
