package net.momirealms.sparrow.ui.window;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link WindowSession.Kind#TREE} 会话: 步入查重的树, 树中成员唯一.
 * <p>步入已存在的成员是把当前位置移过去(跨枝亦然, 父链保持原样), 回退是回到父节点;
 * 两者都不丢弃任何成员, 步入过的 Window 全部保留到会话结束.
 */
final class WindowSessionTree extends AbstractWindowSession {
    private @Nullable Node root;   // 第一次步入的成员成为树根
    private @Nullable Node cursor; // 当前位置, 恒指向链顶

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
        Node existing = this.root == null ? null : findNode(this.root, next);
        if (existing != null) {
            this.cursor = existing;
            return;
        }

        Node node = new Node(next, this.cursor);
        if (this.cursor == null) {
            this.root = node;
        } else {
            this.cursor.children.add(node);
        }
        this.cursor = node;
    }

    @Override
    void stepBack() {
        this.cursor = this.cursor == null ? null : this.cursor.parent;
    }

    @Nullable
    @Override
    AbstractWindow<?> currentWindow() {
        return this.cursor == null ? null : this.cursor.window;
    }

    @Nullable
    @Override
    AbstractWindow<?> sourceWindow() {
        return this.cursor == null || this.cursor.parent == null ? null : this.cursor.parent.window;
    }

    @NotNull
    @Override
    List<Window> currentPath() {
        ArrayList<Window> path = new ArrayList<>();
        for (Node node = this.cursor; node != null; node = node.parent) {
            path.add(node.window);
        }
        Collections.reverse(path);
        return path;
    }

    @Override
    void releaseMembers() {
        if (this.root != null) {
            releaseSubtree(this.root);
        }
        this.root = null;
        this.cursor = null;
    }

    // 在子树中查找持有给定 Window 的节点.
    @Nullable
    private static Node findNode(@NotNull Node node, @NotNull AbstractWindow<?> window) {
        if (node.window == window) {
            return node;
        }
        for (int index = 0; index < node.children.size(); index++) {
            Node found = findNode(node.children.get(index), window);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // 解除子树全部成员的会话归属.
    private static void releaseSubtree(@NotNull Node node) {
        node.window.session(null);
        for (int index = 0; index < node.children.size(); index++) {
            releaseSubtree(node.children.get(index));
        }
    }

    // 树上的一次步入, 窗与它的来源关系.
    private static final class Node {
        private final AbstractWindow<?> window;
        private final @Nullable Node parent;
        private final List<Node> children = new ArrayList<>();

        private Node(AbstractWindow<?> window, @Nullable Node parent) {
            this.window = window;
            this.parent = parent;
        }
    }
}
