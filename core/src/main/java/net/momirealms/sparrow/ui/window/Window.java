package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;

/**
 * 由一名玩家查看的 GUI 会话, 这是物品渲染所需的最小接口.
 */
public interface Window {

    /**
     * 获取查看此 Window 的玩家.
     *
     * @return 查看此 Window 的玩家
     */
    Player viewer();

    /**
     * 将最终窗口槽位标记为需要重新解析和渲染.
     *
     * <p>此方法可能由异步失效通知调用. 实现只能以线程安全的方式合并槽位,
     * 不得在调用线程中修改 GUI 结构或向客户端发包.</p>
     *
     * @param windowSlot 最终窗口槽位
     */
    void dirty(int windowSlot);
}
