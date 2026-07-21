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
    private final Player player;
    private final Window window;
    private final int windowSlot;

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
    public static @NotNull RenderContext cursor(@NotNull Window window) {
        return new RenderContext(window, -1, true);
    }

    private RenderContext(@NotNull Window window, int windowSlot, boolean cursor) {
        this.window = window;
        if (windowSlot < 0 && !cursor) {
            throw new IllegalArgumentException("windowSlot must be non-negative");
        }
        this.windowSlot = windowSlot;
        this.player = window.viewer();
    }

    public @NotNull Player player() {
        return this.player;
    }

    public @NotNull Window window() {
        return this.window;
    }

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
