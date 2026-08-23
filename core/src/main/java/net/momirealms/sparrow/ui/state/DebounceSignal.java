package net.momirealms.sparrow.ui.state;

/**
 * 防抖节点, 上游每失效一次就把发出推后 delay, 连续失效只在最后一次之后发出一次.
 *
 * @param <T> 值类型
 */
final class DebounceSignal<T> extends PacedSignal<T> {

    DebounceSignal(AbstractSignal<T> source, long delay, Delayer delayer) {
        super(source, delay, delayer);
    }

    @Override
    boolean onSourceDirtyLocked() {
        this.scheduleLocked();
        return false;
    }

    @Override
    boolean onFireLocked() {
        // 上游版本没越过快照就不发, 订阅时并进基线的那次写入因此不会再补发
        if (!this.sourceChangedLocked()) return false;
        this.captureLocked();
        return true;
    }
}
