package net.momirealms.sparrow.ui.item.click;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 一次拖拽手势经过某个 Item 时的上下文.
 * <p>只有有效手势才会派发. 光标为空或非创造模式的中键拖拽不会到达 Item.
 * <p>每个经过的 Item 槽位各收到一次事件. 同一个 Item 挂在多个槽位时会收到多次,
 * 每次的 {@link #windowSlot()} 不同, {@link #path()} 相同.
 * <p>{@code path} 固定为客户端提交的槽位序列, 不随处理器对 Pane 的改动而变化.
 *
 * @param clickType 拖拽按键, LEFT 均分, RIGHT 每格一个, MIDDLE 为创造模式整堆
 * @param player 拖拽玩家
 * @param window 所属 Window
 * @param cursor 手势开始时的光标快照, 构造时复制
 * @param windowSlot 本次派发对应的 Window 槽位
 * @param path 手势经过的全部 Window 槽位, 按客户端发包顺序保序去重
 */
public record ItemDrag(
        @NotNull ClickType clickType,
        @NotNull Player player,
        @NotNull Window window,
        @NotNull ItemStack cursor,
        int windowSlot,
        @NotNull List<Stop> path
) implements ItemInteraction {

    public ItemDrag {
        cursor = cursor.clone();
        path = List.copyOf(path);
    }

    /**
     * 返回本次派发对应的站点.
     *
     * @return path 中 windowSlot 与本次派发一致的站点
     */
    @NotNull
    public Stop currentStop() {
        return this.path.get(this.index());
    }

    /**
     * 返回本次槽位在拖拽路径中的下标.
     *
     * @return 从 0 开始的路径下标
     */
    public int index() {
        for (int index = 0; index < this.path.size(); index++) {
            if (this.path.get(index).windowSlot() == this.windowSlot) return index;
        }
        throw new IllegalStateException("drag path does not contain window slot " + this.windowSlot);
    }

    /**
     * 手势经过的一个 Window 槽位.
     *
     * @param windowSlot 该站的 Window 槽位
     */
    public record Stop(int windowSlot) {
    }
}
