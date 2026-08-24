package net.momirealms.sparrow.ui.state;

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
