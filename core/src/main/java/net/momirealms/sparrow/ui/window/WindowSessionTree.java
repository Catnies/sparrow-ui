package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link WindowSession.Kind#TREE} 会话: 步入查重的树, 树中成员唯一.
 * <p>步入已存在的成员是把当前位置移过去(跨枝亦然, 父链保持原样), 回退是回到父节点;
 * 两者都不丢弃任何成员, 步入过的 Window 全部保留到会话结束.
 */
final class WindowSessionTree extends AbstractWindowSession {
    private final IdentityHashMap<AbstractWindow<?>, AbstractWindow<?>> parents = new IdentityHashMap<>(8); // 成员到父窗, 根窗为 null
    private @Nullable AbstractWindow<?> cursor; // 当前位置, 恒指向当前窗

    WindowSessionTree(@NotNull WindowManager manager, @NotNull Player viewer, @NotNull List<Consumer<InventoryCloseEvent.Reason>> endHandlers) {
        super(manager, viewer, endHandlers);
    }

    @NotNull
    @Override
    public Kind kind() {
        return Kind.TREE;
    }

    @Override
    void stepInto(@NotNull AbstractWindow<?> next) {
        // 已经在树上的成员只移动位置, 它的父不改写
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
        ArrayList<Window> path = new ArrayList<>();
        for (AbstractWindow<?> window = this.cursor; window != null; window = this.parents.get(window)) {
            path.add(window);
        }
        Collections.reverse(path);
        return path;
    }

    @Override
    void releaseMembers() {
        for (AbstractWindow<?> member : this.parents.keySet()) {
            member.session(null);
        }
        this.parents.clear();
        this.cursor = null;
    }
}
