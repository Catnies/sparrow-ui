package net.momirealms.sparrow.ui.inventory.event;

import net.momirealms.sparrow.ui.inventory.ReferencingInventory;

public interface UpdateReason {

    /**
     * 程序化操作的原因, 例如初始化, 重置或管理命令触发的写入.
     */
    enum Program implements UpdateReason {
        INSTANCE
    }

    /**
     * 外部世界对被引用容器的直接修改, 由 Tick 任务主动发现.
     * 这类变更是既成事实, 只派发 post 事件, 不派发可取消的 pre 事件.
     *
     * @see ReferencingInventory#refresh()
     */
    enum External implements UpdateReason {
        INSTANCE
    }
}
