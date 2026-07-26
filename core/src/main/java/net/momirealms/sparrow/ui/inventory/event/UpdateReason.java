package net.momirealms.sparrow.ui.inventory.event;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;

/**
 * 一次库存事务的变更原因.
 * <p>这是开放的标记接口: 插件可以实现自己的原因类型, 事务处理器用 {@code instanceof}
 * 判断来源并决定放行或取消. 事务的 reason 一律非空; 程序化操作缺省使用
 * {@link Program#INSTANCE}, 不存在 "null 表示无原因" 的写法.
 */
public interface UpdateReason {

    /**
     * 程序化操作的缺省原因, 例如初始化, 重置或管理命令触发的写入.
     */
    enum Program implements UpdateReason {
        INSTANCE
    }

    /**
     * 外部世界对被引用容器的直接修改, 由对账发现.
     * 这类变更是既成事实, 只派发 post 事件, 不派发可取消的 pre 事件.
     *
     * @see net.momirealms.sparrow.ui.inventory.ReferencingInventory#refresh()
     */
    enum External implements UpdateReason {
        INSTANCE
    }

    /**
     * 玩家在 Window 中的一次点击触发的库存事务.
     *
     * @param viewer 点击的玩家
     * @param clickType 点击类型
     */
    record PlayerClick(@NotNull Player viewer, @NotNull ClickType clickType) implements UpdateReason {
    }

    /**
     * 玩家在 Window 中的一次拖拽分配触发的库存事务.
     *
     * @param viewer 拖拽的玩家
     * @param clickType 拖拽按键(LEFT 均分, RIGHT 每槽一个, MIDDLE 创造整堆)
     */
    record PlayerDrag(@NotNull Player viewer, @NotNull ClickType clickType) implements UpdateReason {
    }
}
