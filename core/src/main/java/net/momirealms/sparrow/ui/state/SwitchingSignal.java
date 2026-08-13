package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 分区切换节点实现.
 * 值取自 key 当前选中的那个分区, key 换了或选中的分区失效都向下游失效.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型
 */
final class SwitchingSignal<K, T> extends AbstractSignal<T> {
    private final KeyedSignal<K, T> source;
    private final AbstractSignal<K> key;
    private final Object switchLock = new Object();

    @Nullable private volatile Selected<K, T> selected; // 当前 key 选中的分区句柄, 强持有: at() 的缓存是弱的
    private volatile long version;      // 单调递增, 只在换分区或选中分区失效时推进
    private long notifiedVersion;       // 已向下游通知过的版本
    @Nullable private Subscription keyUpstream;         // 有下游订阅时挂着
    @Nullable private Subscription partitionUpstream;   // 有下游订阅时挂着, 与 selected 一起换

    SwitchingSignal(KeyedSignal<K, T> source, AbstractSignal<K> key) {
        this.source = source;
        this.key = key;
    }

    @Override
    public T get() {
        Selected<K, T> current = this.selected;
        if (current == null || !Objects.equals(current.key(), this.key.get())) {
            current = this.refresh();
        }
        return current.partition().get();
    }

    /**
     * <p>版本由本节点自己维护并单调递增: 前后两个分区各有各的计数, 直接透传会来回跳.
     */
    @Override
    long version() {
        Selected<K, T> current = this.selected;
        if (current == null
                || !Objects.equals(current.key(), this.key.get())
                || current.partitionVersion() != current.partition().version()) {
            this.refresh();
        }
        return this.version;
    }

    /**
     * 把选中的分区对齐到当前 key.
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
     * 重新选一次分区, 并在换了分区或分区失效过时推进版本. 已经对齐时无操作.
     *
     * @return 需要在锁外关闭的上一条分区转发凭证, 没有换分区时为 {@code null}
     */
    @Nullable
    private Subscription refreshLocked() {
        K currentKey = this.key.get();
        Selected<K, T> current = this.selected;
        if (current != null && Objects.equals(current.key(), currentKey)) {
            // 还是同一个分区, 只看它自上次记录以来有没有失效过
            long partitionVersion = current.partition().version();
            if (current.partitionVersion() != partitionVersion) {
                this.selected = new Selected<>(currentKey, current.partition(), partitionVersion);
                this.version++;
            }
            return null;
        }

        AbstractSignal<T> partition = AbstractSignal.require(this.source.at(currentKey));
        // 无下游订阅时不挂转发, 版本改由 get 与 version 的拉取路径推进
        Subscription previous = this.partitionUpstream;
        if (previous != null) {
            this.partitionUpstream = partition.onDirty(this::onUpstreamDirty);
        }
        // 取版本快照
        this.selected = new Selected<>(currentKey, partition, partition.version());
        this.version++;
        return previous;
    }

    // key 或选中的分区失效: 重选一次, 真的变了才向下游通知.
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
        synchronized (this.switchLock) {
            this.keyUpstream = this.key.onDirty(this::onUpstreamDirty);
            try {
                this.refreshLocked();
                Selected<K, T> current = this.selected;
                assert current != null; // refreshLocked 一定会留下一个选中结果
                this.partitionUpstream = current.partition().onDirty(this::onUpstreamDirty);
                // 上一句之前发生的分区失效收不到推送, 所以挂完转发再对一次快照, 把它收进版本里
                this.refreshLocked();
            } catch (RuntimeException | Error exception) {
                // key 求值抛出时撤销已挂的订阅, 让 register 的回滚留下干净现场.
                this.keyUpstream.close();
                this.keyUpstream = null;
                throw exception;
            }
            // 没有基线时首次订阅会把无订阅期间攒下的版本推进误判为变化, 手动对齐.
            this.notifiedVersion = this.version;
        }
    }

    @Override
    protected void onInactive() {
        Subscription previousKey;
        Subscription previousPartition;
        synchronized (this.switchLock) {
            previousKey = this.keyUpstream;
            previousPartition = this.partitionUpstream;
            this.keyUpstream = null;
            this.partitionUpstream = null;
        }
        previousKey.close();
        previousPartition.close();
    }

    // 一次选中结果: key, 它对应的分区句柄, 以及记下这一次时该句柄的版本.
    private record Selected<K, T>(K key, AbstractSignal<T> partition, long partitionVersion) {
    }
}
