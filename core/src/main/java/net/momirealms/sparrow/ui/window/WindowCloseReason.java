package net.momirealms.sparrow.ui.window;

/**
 * 描述 Window 结束当前打开状态的来源.
 */
public enum WindowCloseReason {
    /**
     * 无法确定关闭来源.
     */
    UNKNOWN,
    /**
     * 玩家传送时关闭了容器.
     */
    TELEPORT,
    /**
     * 玩家已经无法继续使用容器.
     */
    CANT_USE,
    /**
     * 容器所在区域被卸载.
     */
    UNLOADED,
    /**
     * 新容器替换了当前容器.
     */
    OPEN_NEW,
    /**
     * 玩家主动关闭了容器.
     */
    PLAYER,
    /**
     * 玩家断开连接.
     */
    DISCONNECT,
    /**
     * 玩家死亡时关闭了容器.
     */
    DEATH,
    /**
     * 插件主动关闭了容器.
     */
    PLUGIN
}
