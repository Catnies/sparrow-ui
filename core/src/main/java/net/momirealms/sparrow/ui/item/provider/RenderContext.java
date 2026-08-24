package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 一次 Item 渲染的只读上下文.
 */
public final class RenderContext {
    private final Player player;
    private final Window window;
    private final Kind kind;
    private final int windowSlot;
    @Nullable private final Consumer<Object> memo;

    /**
     * 为 Window 的最终槽位创建渲染上下文.
     *
     * @param window 所属 Window
     * @param windowSlot Window 槽位
     * @throws IllegalArgumentException 当 windowSlot 为负数时
     */
    public RenderContext(@NotNull Window window, int windowSlot) {
        this(window, Kind.WINDOW_SLOT, windowSlot, null);
    }

    /**
     * 为 Window 的最终槽位创建可记录交互数据的渲染上下文.
     *
     * @param window 所属 Window
     * @param windowSlot Window 槽位
     * @param memo 接收 {@link #remember(Object)} 数据的回调
     * @throws IllegalArgumentException 当 windowSlot 为负数时
     */
    @ApiStatus.Internal
    public RenderContext(@NotNull Window window, int windowSlot, @NotNull Consumer<Object> memo) {
        this(window, Kind.WINDOW_SLOT, windowSlot, memo);
    }

    /**
     * 创建用于渲染 Window 光标内容的上下文.
     *
     * @param window 所属 Window
     * @return 光标渲染上下文
     */
    @NotNull
    public static RenderContext cursor(@NotNull Window window) {
        return new RenderContext(window, Kind.CURSOR, -1, null);
    }

    /**
     * 创建用于渲染非槽位内容的上下文, 例如商人交易列表.
     *
     * @param window 所属 Window
     * @return 非槽位渲染上下文
     */
    @NotNull
    public static RenderContext offSlot(@NotNull Window window) {
        return new RenderContext(window, Kind.OFF_SLOT, -1, null);
    }

    private RenderContext(@NotNull Window window, @NotNull Kind kind, int windowSlot, @Nullable Consumer<Object> memo) {
        if (windowSlot < 0 && kind == Kind.WINDOW_SLOT)
            throw new IllegalArgumentException("windowSlot must be non-negative");

        this.window = window;
        this.kind = kind;
        this.windowSlot = windowSlot;
        this.memo = memo;
        this.player = window.viewer();
    }

    /**
     * 返回查看当前内容的玩家.
     *
     * @return 查看者
     */
    @NotNull
    public Player player() {
        return this.player;
    }

    /**
     * 返回本次渲染所属的 Window.
     *
     * @return Window
     */
    @NotNull
    public Window window() {
        return this.window;
    }

    /**
     * 返回本次渲染的去向.
     *
     * @return 渲染去向
     */
    @NotNull
    public Kind kind() {
        return this.kind;
    }

    /**
     * 返回最终 Window 槽位. 光标与非槽位内容固定返回 {@code -1}.
     *
     * @return Window 槽位, 或 {@code -1}
     */
    public int windowSlot() {
        return this.windowSlot;
    }

    /**
     * 返回本次是否在渲染光标内容.
     *
     * @return 渲染光标内容时为 {@code true}
     */
    public boolean isCursor() {
        return this.kind == Kind.CURSOR;
    }

    /**
     * 为当前 Window 槽位记录一份交互数据, 后续可通过 {@link ItemInteraction#remembered()} 取回.
     * <p>每次调用覆盖前值, {@code null} 表示清除. 记录会在显示路径终点改变或 Window 关闭时清除.
     * <p><strong>必须在本次渲染回调返回前调用.</strong>
     * 光标与非槽位上下文没有可接收交互的槽位, 调用不会产生效果.
     * <p>显示路径会强引用记录值直到它被覆盖或清除, 不要存放需要更早释放的对象.
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
