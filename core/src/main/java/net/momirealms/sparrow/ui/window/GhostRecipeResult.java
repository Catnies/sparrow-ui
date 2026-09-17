package net.momirealms.sparrow.ui.window;

/**
 * 给配方书 Window 发一个 ghost recipe 之后的结果.
 */
public enum GhostRecipeResult {
    SENT,               // 已经交给发送路径
    WINDOW_CLOSED,      // 窗口已经关了
    RECIPE_NOT_FOUND,   // 配方不存在
    VIEWER_UNAVAILABLE  // 玩家不可用
}
