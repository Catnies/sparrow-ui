package net.momirealms.sparrow.ui.item.provider;

import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * 单次物品渲染操作的只读上下文.
 *
 * @param player 接收渲染物品的玩家
 * @param window 正在渲染的窗口
 * @param windowSlot 该窗口中的最终槽位
 */
public record RenderContext(Player player, Window window, int windowSlot) {

    /**
     * 校验玩家、窗口及最终槽位构成有效的渲染目标.
     */
    public RenderContext {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(window, "window");
        if (windowSlot < 0) {
            throw new IllegalArgumentException("windowSlot must be non-negative");
        }

        Player viewer = Objects.requireNonNull(window.viewer(), "window.viewer()");
        if (!player.getUniqueId().equals(viewer.getUniqueId())) {
            throw new IllegalArgumentException("player must be the window viewer");
        }
    }
}
