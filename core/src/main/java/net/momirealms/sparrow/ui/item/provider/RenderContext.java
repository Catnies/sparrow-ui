package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class RenderContext {
    private final Player player;  // 查看者, 取自所属 Window
    private final Window window;  // 所属 Window
    private final Kind kind;      // 本次渲染的去向
    private final int windowSlot; // 最终槽位编号, 不落在 Window 槽位上时为 -1

    // 为 Window 的最终槽位创建稳定的渲染上下文.
    public RenderContext(@NotNull Window window, int windowSlot) {
        this(window, Kind.WINDOW_SLOT, windowSlot);
    }

    // 创建用于渲染 Window 光标可视内容的上下文.
    @NotNull
    public static RenderContext cursor(@NotNull Window window) {
        return new RenderContext(window, Kind.CURSOR, -1);
    }

    // 创建用于渲染 Window 里不占槽位的展示位的上下文, 例如商人的交易列表.
    @NotNull
    public static RenderContext offSlot(@NotNull Window window) {
        return new RenderContext(window, Kind.OFF_SLOT, -1);
    }

    private RenderContext(@NotNull Window window, @NotNull Kind kind, int windowSlot) {
        // 槽位上下文必须非负, 只有光标与不占槽位的展示位允许使用 -1
        if (windowSlot < 0 && kind == Kind.WINDOW_SLOT)
            throw new IllegalArgumentException("windowSlot must be non-negative");

        this.window = window;
        this.kind = kind;
        this.windowSlot = windowSlot;
        this.player = window.viewer();
    }

    @NotNull
    public Player player() {
        return this.player;
    }

    @NotNull
    public Window window() {
        return this.window;
    }

    // 本次渲染的去向.
    @NotNull
    public Kind kind() {
        return this.kind;
    }

    // 获取最终槽位编号, 不落在 Window 槽位上时固定返回 -1.
    public int windowSlot() {
        return this.windowSlot;
    }

    public boolean isCursor() {
        return this.kind == Kind.CURSOR;
    }

    // 一次渲染的去向.
    public enum Kind {
        WINDOW_SLOT, // Window 的一个槽位, 编号由 windowSlot 给出
        CURSOR,      // 客户端光标
        OFF_SLOT     // Window 里不占槽位的展示位, 例如商人的交易列表
    }
}
