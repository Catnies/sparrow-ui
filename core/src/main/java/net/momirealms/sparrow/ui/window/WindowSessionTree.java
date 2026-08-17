package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/**
 * {@link WindowSession.Kind#TREE} 会话: 步入查重的树, 回退不丢弃任何成员,
 * 步入过的 Window 全部保留到会话结束.
 */
final class WindowSessionTree extends WindowSessionStack {

    WindowSessionTree(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<InventoryCloseEvent.Reason>> endHandlers) {
        super(manager, viewer, endHandlers);
    }

    @NotNull
    @Override
    public Kind kind() {
        return Kind.TREE;
    }
}
