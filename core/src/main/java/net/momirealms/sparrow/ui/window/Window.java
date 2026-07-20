package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 由一名玩家查看的 GUI 会话, 这是物品渲染所需的最小接口.
 */
public interface Window {

    /**
     * 获取查看此 Window 的玩家.
     *
     * @return 查看此 Window 的玩家
     */
    @NotNull Player viewer();

    /**
     * 将最终窗口槽位标记为需要刷新.
     *
     * <p>此方法可能由异步失效通知调用. 实现只能以线程安全的方式合并槽位,
     * 不得在调用线程中解析 GUI 或向客户端发包.
     * 是否需要重新解析路径由显示路径自己判断.</p>
     *
     * @param windowSlot 最终窗口槽位
     */
    void dirty(int windowSlot);
}
