package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link WindowSession.Kind#RETAINED_STACK} 会话: 结构同 {@link WindowSessionStack},
 * 唯一差异是被弹出的 Window 收进保留区, 引用保留到会话结束.
 */
final class WindowSessionRetainedStack extends WindowSessionStack {
    private final List<Window> retained = new ArrayList<>(); // 被弹出的窗

    WindowSessionRetainedStack(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<InventoryCloseEvent.Reason>> endHandlers) {
        super(manager, viewer, endHandlers);
    }

    @NotNull
    @Override
    public Kind kind() {
        return Kind.RETAINED_STACK;
    }

    @Override
    void discard(@NotNull AbstractWindow<?> popped, boolean stillPresent) {
        super.discard(popped, stillPresent);
        if (!stillPresent && !this.retained.contains(popped)) {
            this.retained.add(popped);
        }
    }

    @Override
    void releaseMembers() {
        super.releaseMembers();
        // 保留区的窗归属早已解除, 丢引用即释放
        this.retained.clear();
    }
}
