package net.momirealms.sparrow.ui.window;

/**
 * 配方书 Window ghost recipe 发送结果.
 */
public enum GhostRecipeResult {
    SENT,               // 已进入发送路径
    WINDOW_CLOSED,      // 窗口已关闭
    RECIPE_NOT_FOUND,   // 配方不存在
    VIEWER_UNAVAILABLE  // 玩家不可用
}