package net.momirealms.sparrow.ui.window;

/**
 * 这次 Window 是被什么结束掉的.
 * <p>Bukkit 那边的关闭原因由 {@link WindowCloseReasonAdapter} 翻成这套值, 使用方因此不用直接认平台枚举.
 */
public enum WindowCloseReason {
    /**
     * 平台没给出能认出来的来源.
     */
    UNKNOWN,
    /**
     * 玩家传送, 容器跟着关了.
     */
    TELEPORT,
    /**
     * 玩家已经用不了这个容器了.
     */
    CANT_USE,
    /**
     * 容器所在区域被卸载.
     */
    UNLOADED,
    /**
     * 玩家打开了新容器, 当前这个被换掉.
     */
    OPEN_NEW,
    /**
     * 玩家自己关的.
     */
    PLAYER,
    /**
     * 玩家断开连接.
     */
    DISCONNECT,
    /**
     * 玩家死了, 容器跟着关.
     */
    DEATH,
    /**
     * 插件调 close() 关的.
     */
    PLUGIN
}
