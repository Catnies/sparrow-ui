package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;

/**
 * 由一名玩家查看的 GUI 会话, 这是物品渲染所需的最小接口.
 */
public interface Window {

    Player viewer();
}
