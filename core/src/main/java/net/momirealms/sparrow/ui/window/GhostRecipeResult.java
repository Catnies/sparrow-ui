package net.momirealms.sparrow.ui.window;

/**
 * 主动向配方书 Window 发送 ghost recipe 的结果.
 */
public enum GhostRecipeResult {
    SENT,
    WINDOW_CLOSED,
    RECIPE_NOT_FOUND,
    VIEWER_UNAVAILABLE
}