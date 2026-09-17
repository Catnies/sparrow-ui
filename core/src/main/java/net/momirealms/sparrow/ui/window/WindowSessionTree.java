package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

final class WindowSessionTree extends AbstractWindowSession {
    private final IdentityHashMap<AbstractWindow<?>, AbstractWindow<?>> parents = new IdentityHashMap<>(8); // 成员 -> 父窗; 根窗的父是 null
    private @Nullable AbstractWindow<?> cursor; // 现在站在哪个成员上, 也就是当前窗

    WindowSessionTree(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<WindowCloseReason>> sessionEndHandlers) {
        super(manager, viewer, sessionEndHandlers);
    }

    @NotNull
    @Override
    public Kind kind() {
        return Kind.TREE;
    }

    @Override
    void stepInto(@NotNull AbstractWindow<?> next) {
        // 已经在树里的人只是把位置挪过去, 它的父节点照样是原来那个
        if (!this.parents.containsKey(next)) {
            this.parents.put(next, this.cursor);
        }
        this.cursor = next;
    }

    @Override
    void stepBack() {
        this.cursor = this.previousWindow();
    }

    @Nullable
    @Override
    AbstractWindow<?> currentWindow() {
        return this.cursor;
    }

    @Nullable
    @Override
    AbstractWindow<?> previousWindow() {
        return this.cursor == null ? null : this.parents.get(this.cursor);
    }

    @NotNull
    @Override
    List<Window> currentPath() {
        // 顺着父指针一路往上收, 再倒过来就是根到当前位置那一条
        ArrayList<Window> path = new ArrayList<>();
        for (AbstractWindow<?> window = this.cursor; window != null; window = this.parents.get(window)) {
            path.add(window);
        }
        Collections.reverse(path);
        return path;
    }

    @Override
    void releaseMembers() {
        // 会话结束时逐个解除归属, 然后清掉树和位置
        for (AbstractWindow<?> member : this.parents.keySet()) {
            member.session(null);
        }
        this.parents.clear();
        this.cursor = null;
    }
}
