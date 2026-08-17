package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link WindowSession.Kind#STACK} 会话: 线性栈, 步入不查重, 同一实例可压入多次;
 * 回退弹出栈顶, 栈中不再出现该实例时丢弃对它的引用.
 */
class WindowSessionStack extends AbstractWindowSession {
    private final List<Window> stack = new ArrayList<>(); // 链根到栈顶, 只在玩家实体线程修改

    WindowSessionStack(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<InventoryCloseEvent.Reason>> endHandlers) {
        super(manager, viewer, endHandlers);
    }

    @NotNull
    @Override
    public Kind kind() {
        return Kind.STACK;
    }

    @Override
    void stepInto(@NotNull AbstractWindow<?> next) {
        this.stack.add(next);
    }

    @Override
    void stepBack() {
        AbstractWindow<?> popped = (AbstractWindow<?>) this.stack.remove(this.stack.size() - 1);
        // 环形栈里同一实例可能在更深处还压着, 那时它仍是本会话的成员
        this.discard(popped, this.stack.contains(popped));
    }

    /**
     * 处置被弹出的窗, 不再出现在栈中时解除会话归属并丢弃引用.
     *
     * @param popped 刚弹出的窗
     * @param stillPresent 同一实例是否还在栈的更深处
     */
    void discard(@NotNull AbstractWindow<?> popped, boolean stillPresent) {
        if (!stillPresent) {
            popped.session(null);
        }
    }

    @Nullable
    @Override
    AbstractWindow<?> currentWindow() {
        return this.stack.isEmpty() ? null : (AbstractWindow<?>) this.stack.get(this.stack.size() - 1);
    }

    @Nullable
    @Override
    AbstractWindow<?> sourceWindow() {
        return this.stack.size() < 2 ? null : (AbstractWindow<?>) this.stack.get(this.stack.size() - 2);
    }

    @NotNull
    @Override
    List<Window> currentPath() {
        return this.stack;
    }

    @Override
    void releaseMembers() {
        for (int index = 0; index < this.stack.size(); index++) {
            ((AbstractWindow<?>) this.stack.get(index)).session(null);
        }
        this.stack.clear();
    }
}
