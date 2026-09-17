package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class WindowSessionStack extends AbstractWindowSession {
    private final List<Window> stack = new ArrayList<>(); // 根窗到栈顶的名单, 只在玩家实体线程改

    WindowSessionStack(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<WindowCloseReason>> sessionEndHandlers) {
        super(manager, viewer, sessionEndHandlers);
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
        // 环形栈里同一个实例可能在更深处还压着, 那它还是本会话的成员
        this.discard(popped, this.stack.contains(popped));
    }

    /**
     * 处理刚弹出的窗: 栈里再也找不到它时, 解除它的会话归属.
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
        // 栈顶就是当前窗, 栈空了就没有当前窗
        return this.stack.isEmpty() ? null : (AbstractWindow<?>) this.stack.get(this.stack.size() - 1);
    }

    @Nullable
    @Override
    AbstractWindow<?> previousWindow() {
        return this.stack.size() < 2 ? null : (AbstractWindow<?>) this.stack.get(this.stack.size() - 2);
    }

    @NotNull
    @Override
    List<Window> currentPath() {
        return this.stack;
    }

    @Override
    void releaseMembers() {
        // 会话结束时逐个解除归属, 再清空名单
        for (int index = 0; index < this.stack.size(); index++) {
            ((AbstractWindow<?>) this.stack.get(index)).session(null);
        }
        this.stack.clear();
    }
}
