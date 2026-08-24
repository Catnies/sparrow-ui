package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

/**
 * 按 key 切换来源的节点实现.
 * 值取自 key 当前选中的那个来源, key 换了或选中的来源失效都向下游失效.
 * <p>只订阅当前选中的那一个来源, 没被选中的来源不参与失效传播, 也不会被求值.
 *
 * @param <K> 选择用的 key 类型
 * @param <T> 值类型
 */
final class SwitchingSignal<K, T> extends AbstractSignal<T> {
    private final Function<? super K, ? extends Signal<T>> sourceOf;
    private final AbstractSignal<K> key;
    private final Object switchLock = new Object();

    @Nullable private volatile Selected<K, T> selected; // 当前 key 选中的来源, 强持有: KeyedSignal 那边 at() 的缓存是弱的
    private volatile long version;      // 单调递增, 只在换来源或选中来源失效时推进
    private long notifiedVersion;       // 已向下游通知过的版本
    @Nullable private Subscription keyUpstream;      // 有下游订阅时挂着
    @Nullable private Subscription sourceUpstream;   // 有下游订阅时挂着, 与 selected 一起换

    SwitchingSignal(Function<? super K, ? extends Signal<T>> sourceOf, AbstractSignal<K> key) {
        this.sourceOf = sourceOf;
        this.key = key;
    }

    @Override
    public T get() {
        Selected<K, T> current = this.selected;
        if (current == null || !Objects.equals(current.key(), this.key.get())) {
            current = this.refresh();
        }
        return current.source().get();
    }

    /**
     * 版本由本节点自己维护并单调递增: 前后两个来源各有各的计数, 直接透传会来回跳.
     */
    @Override
    long version() {
        Selected<K, T> current = this.selected;
        if (current == null
                || !Objects.equals(current.key(), this.key.get())
                || current.sourceVersion() != current.source().version()) {
            this.refresh();
        }
        return this.version;
    }

    /**
     * 把选中的来源对齐到当前 key.
     *
     * @return 对齐后的选中结果
     */
    private Selected<K, T> refresh() {
        Subscription previous;
        Selected<K, T> current;
        synchronized (this.switchLock) {
            previous = this.refreshLocked();
            current = this.selected;
        }
        if (previous != null) {
            previous.close();
        }
        assert current != null;
        return current;
    }

    /**
     * 重新选一次来源, 并在换了来源或来源失效过时推进版本. 已经对齐时无操作.
     * <p>版本一律先推进再发布快照. {@link #version()} 按 selected 到 version 的顺序读, 这里写成相反的顺序,
     * 读者才不会看见新快照却配上旧版本 —— 那会让没有下游订阅的拉取路径把这次变化整个漏掉.
     * 反过来看见旧快照配新版本是安全的: 那只会让下游多算一遍.
     *
     * @return 需要在锁外关闭的上一条来源转发凭证, 没有换来源时为 {@code null}
     */
    @Nullable
    private Subscription refreshLocked() {
        K currentKey = this.key.get();
        Selected<K, T> current = this.selected;
        if (current != null && Objects.equals(current.key(), currentKey)) {
            // 还是同一个来源, 只看它自上次记录以来有没有失效过
            long sourceVersion = current.source().version();
            if (current.sourceVersion() != sourceVersion) {
                this.version++;
                this.selected = new Selected<>(currentKey, current.source(), sourceVersion);
            }
            return null;
        }

        AbstractSignal<T> source = AbstractSignal.require(this.sourceOf.apply(currentKey));
        // 无下游订阅时不挂转发, 版本改由 get 与 version 的拉取路径推进
        Subscription previous = this.sourceUpstream;
        Subscription attached = null;
        if (previous != null) {
            attached = this.linkTo(source, this::onUpstreamDirty);
        }
        // 取版本快照. 抛出时整笔换源作废: 新转发当场撤掉, 选中结果与旧转发都维持原状,
        // 否则逻辑上还选着旧来源, 转发却已经改听新来源, 而换回旧 key 走的是快路径, 不会再重挂.
        long sourceVersion;
        try {
            sourceVersion = source.version();
        } catch (RuntimeException | Error exception) {
            if (attached != null) {
                attached.close();
            }
            throw exception;
        }
        if (attached != null) {
            this.sourceUpstream = attached;
        }
        this.version++;
        this.selected = new Selected<>(currentKey, source, sourceVersion);
        return previous;
    }

    // key 或选中的来源失效时重选一次, 真的变了才向下游通知.
    private void onUpstreamDirty() {
        // 死条目本来靠派发时投递失败剔除, 而本节点会把没换成员的失效全部截断.
        // 在这里补一次清理, 避免下游走光之后还会一直挂在集合与成员上重新对齐.
        this.reapDeadEntries();
        Subscription previous;
        boolean shouldNotify = false;
        synchronized (this.switchLock) {
            previous = this.refreshLocked();
            if (this.version > this.notifiedVersion) {
                this.notifiedVersion = this.version;
                shouldNotify = true;
            }
        }
        if (previous != null) {
            previous.close();
        }
        if (shouldNotify) {
            this.notifyDirty();
        }
    }

    @Override
    protected void onActive() {
        Subscription discarded = null;
        synchronized (this.switchLock) {
            this.keyUpstream = this.linkTo(this.key, this::onUpstreamDirty);
            try {
                this.refreshLocked();
                Selected<K, T> current = this.selected;
                assert current != null; // refreshLocked 一定会留下一个选中结果
                this.sourceUpstream = this.linkTo(current.source(), this::onUpstreamDirty);
                // 上一句之前发生的来源失效收不到推送, 所以挂完转发再对一次快照, 把它收进版本里.
                discarded = this.refreshLocked();
            } catch (RuntimeException | Error exception) {
                // key 求值抛出时撤销已挂的订阅, 让 register 的回滚留下干净现场.
                if (this.sourceUpstream != null) {
                    this.sourceUpstream.close();
                    this.sourceUpstream = null;
                }
                this.keyUpstream.close();
                this.keyUpstream = null;
                throw exception;
            }
            // 没有基线时首次订阅会把无订阅期间攒下的版本推进误判为变化, 手动对齐.
            this.notifiedVersion = this.version;
        }
        if (discarded != null) {
            discarded.close();
        }
    }

    @Override
    protected void onInactive() {
        Subscription previousKey;
        Subscription previousSource;
        synchronized (this.switchLock) {
            previousKey = this.keyUpstream;
            previousSource = this.sourceUpstream;
            this.keyUpstream = null;
            this.sourceUpstream = null;
        }
        previousKey.close();
        previousSource.close();
    }

    // 一次选中结果, 以及记下这一次时 source 的版本.
    private record Selected<K, T>(K key, AbstractSignal<T> source, long sourceVersion) {
    }
}
