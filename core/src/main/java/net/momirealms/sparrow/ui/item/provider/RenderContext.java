package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 单次物品渲染操作的只读上下文.
 */
public final class RenderContext {
    private final Player player;
    private final Window window;
    private final int windowSlot;

    /**
     * 从 Window 的唯一 viewer 创建最终槽位的稳定渲染上下文.
     */
    public RenderContext(@NotNull Window window, int windowSlot) {
        this.window = window;
        if (windowSlot < 0) {
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
}
