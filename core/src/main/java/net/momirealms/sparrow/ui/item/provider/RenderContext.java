package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 单次物品渲染操作的只读上下文.
 *
 * <p>普通上下文指向 Window 的最终槽位. {@link #cursor(Window)} 表示客户端光标,
 * 其 {@link #windowSlot()} 固定为 {@code -1}.</p>
 */
public final class RenderContext {
    private final Player player;  // 查看者, 取自所属 Window
    private final Window window;  // 所属 Window
    private final int windowSlot; // 最终槽位编号; -1 表示客户端光标

    /**
     * 为 Window 的最终槽位创建稳定的渲染上下文.
     *
     * @param window 所属 Window
     * @param windowSlot 最终槽位编号, 必须非负
     * @throws IllegalArgumentException 槽位编号为负数时抛出
     */
    public RenderContext(@NotNull Window window, int windowSlot) {
        this(window, windowSlot, false);
    }

    /**
     * 创建用于渲染 Window 光标可视内容的上下文.
     *
     * @param window 所属 Window
     * @return 光标渲染上下文
     */
    @NotNull
    public static RenderContext cursor(@NotNull Window window) {
        return new RenderContext(window, -1, true);
    }

    /**
     * 创建渲染上下文.
     * 只有光标上下文允许槽位编号为 -1.
     *
     * @param window 所属 Window
     * @param windowSlot 最终槽位编号; 光标上下文固定为 -1
     * @param cursor 是否表示客户端光标
     * @throws IllegalArgumentException 非光标上下文且槽位编号为负时抛出
     */
    private RenderContext(@NotNull Window window, int windowSlot, boolean cursor) {
        // 普通槽位必须非负, 只有光标上下文允许使用 -1
        if (windowSlot < 0 && !cursor)
            throw new IllegalArgumentException("windowSlot must be non-negative");

        this.window = window;
        this.windowSlot = windowSlot;
        this.player = window.viewer();
    }

    /**
     * 获取查看此 Window 的玩家.
     *
     * @return 查看者
     */
    @NotNull
    public Player player() {
        return this.player;
    }

    /**
     * 获取本次渲染所属的 Window.
     *
     * @return 所属 Window
     */
    @NotNull
    public Window window() {
        return this.window;
    }

    /**
     * 获取最终槽位编号. 光标上下文固定返回 -1.
     *
     * @return 槽位编号, 光标上下文为 -1
     */
    public int windowSlot() {
        return this.windowSlot;
    }

    /**
     * 返回此上下文是否表示客户端光标而非最终槽位.
     *
     * @return 表示光标时为 true
     */
    public boolean isCursor() {
        return this.windowSlot == -1;
    }
}
