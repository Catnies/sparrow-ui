package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

final class SwitchingSignal<K, T> extends AbstractSignal<T> {
    private final Function<? super K, ? extends Signal<T>> sourceOf;
    private final AbstractSignal<K> key;
    private final Object switchLock = new Object();

    @Nullable private volatile Selected<K, T> selected; // 强持当前来源, KeyedSignal 的句柄缓存为弱引用
    private volatile long version;      // 换来源或当前来源失效时递增
    private long notifiedVersion;       // 最近一次已派发的版本
    @Nullable private Subscription keyUpstream;
    @Nullable private Subscription sourceUpstream;   // 有下游订阅时存在, 与 selected 同步替换

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

    // 来源各自维护版本, 本节点另建单调版本供切换前后的下游缓存使用
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

    // 将选中来源对齐到当前 key, 上一条转发在锁外关闭
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
     * <p>先推进 {@code version}, 再发布 {@code selected}. {@link #version()} 按相反顺序读取,
     * 因此不会观察到新来源配旧版本而漏掉变化. 旧来源配新版本只会多计算一次.
     *
     * @return 需要在锁外关闭的上一条来源转发凭证, 没有换来源时为 {@code null}
     */
    @Nullable
    private Subscription refreshLocked() {
        K currentKey = this.key.get();
        Selected<K, T> current = this.selected;
        if (current != null && Objects.equals(current.key(), currentKey)) {
            // key 未变时只比较当前来源版本
            long sourceVersion = current.source().version();
            if (current.sourceVersion() != sourceVersion) {
                this.version++;
                this.selected = new Selected<>(currentKey, current.source(), sourceVersion);
            }
            return null;
        }

        AbstractSignal<T> source = AbstractSignal.require(this.sourceOf.apply(currentKey));
        // 无下游订阅时不建立转发, 由 get 与 version 的拉取路径推进版本
        Subscription previous = this.sourceUpstream;
        Subscription attached = null;
        if (previous != null) {
            attached = this.linkTo(source, this::onUpstreamDirty);
        }
        // 读取来源版本失败时撤销新转发, 让选中结果和上一条订阅继续对应
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

    // key 或当前来源失效后重新对齐, 版本确实前进时才派发
    private void onUpstreamDirty() {
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
                // 建完转发后再次对齐, 收进挂载窗口内发生的来源失效
                discarded = this.refreshLocked();
            } catch (RuntimeException | Error exception) {
                // 激活求值失败时撤销本轮订阅, 配合 register 回滚
                if (this.sourceUpstream != null) {
                    this.sourceUpstream.close();
                    this.sourceUpstream = null;
                }
                this.keyUpstream.close();
                this.keyUpstream = null;
                throw exception;
            }
            // 首次订阅以当前版本建立通知基线
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

    // 当前 key、来源及其对齐时的版本
    private record Selected<K, T>(K key, AbstractSignal<T> source, long sourceVersion) {
    }
}
