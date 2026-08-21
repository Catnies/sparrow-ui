package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.ReferencingInventory;

/**
 * 一笔事务因何而起, 事件处理器凭它区分是谁改了内容.
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
     * 这类变更是既成事实, 只派发 post 事件, 没有可取消的 pre 事件.
     *
     * @see ReferencingInventory#refresh()
     */
    enum External implements UpdateReason {
        INSTANCE
    }
}
