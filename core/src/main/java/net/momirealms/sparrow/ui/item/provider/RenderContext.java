package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public final class RenderContext {
    private final Player player;  // 查看者, 取自所属 Window
    private final Window window;  // 所属 Window
    private final Kind kind;      // 本次渲染的去向
    private final int windowSlot; // 最终槽位编号, 不落在 Window 槽位上时为 -1
    @Nullable private final Consumer<Object> memo;   // remember 写到哪, 光标与不占槽位的展示位没有

    // 为 Window 的最终槽位创建稳定的渲染上下文.
    public RenderContext(@NotNull Window window, int windowSlot) {
        this(window, Kind.WINDOW_SLOT, windowSlot, null);
    }

    // 为 Window 的最终槽位创建渲染上下文, remember 经 memo 写回这个槽位.
    @ApiStatus.Internal
    public RenderContext(@NotNull Window window, int windowSlot, @NotNull Consumer<Object> memo) {
        this(window, Kind.WINDOW_SLOT, windowSlot, memo);
    }

    // 创建用于渲染 Window 光标可视内容的上下文.
    @NotNull
    public static RenderContext cursor(@NotNull Window window) {
        return new RenderContext(window, Kind.CURSOR, -1, null);
    }

    // 创建用于渲染 Window 里不占槽位的展示位的上下文, 例如商人的交易列表.
    @NotNull
    public static RenderContext offSlot(@NotNull Window window) {
        return new RenderContext(window, Kind.OFF_SLOT, -1, null);
    }

    private RenderContext(@NotNull Window window, @NotNull Kind kind, int windowSlot, @Nullable Consumer<Object> memo) {
        // 槽位上下文必须非负, 只有光标与不占槽位的展示位允许使用 -1
        if (windowSlot < 0 && kind == Kind.WINDOW_SLOT)
            throw new IllegalArgumentException("windowSlot must be non-negative");

        this.window = window;
        this.kind = kind;
        this.windowSlot = windowSlot;
        this.memo = memo;
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

    /**
     * 记下一个自定义数据, 同一个槽位之后的点击可以经 {@code ItemInteraction.remembered()} 取回.
     * <p>记录的是以观察者为单位单次渲染任务的自定义数据, 终点 Item 换了或关闭了 Window 会丢失.
     * <p><strong>在 {@code provide} 返回前调用.</strong> 渲染线程只有一条, 同步 provider 天然串行;
     * 异步 provider 就得看是谁先完成了, 最后一个完成的写入.
     * <p>光标与不占槽位的展示位没有点击会落到它们上面, 在那两种上下文里调用什么也不发生.
     * <p>与 {@code dependsOn} 的闭包一样, <strong>不能捕捉 {@code Player} 等可能会泄露的对象</strong>
     *
     * @param value 要记下的东西, {@code null} 即清除
     */
    public void remember(@Nullable Object value) {
        if (this.memo != null) {
            this.memo.accept(value);
        }
    }

    // 一次渲染的去向.
    public enum Kind {
        WINDOW_SLOT, // Window 的一个槽位, 编号由 windowSlot 给出
        CURSOR,      // 客户端光标
        OFF_SLOT     // Window 里不占槽位的展示位, 例如商人的交易列表
    }
}
