package net.momirealms.sparrow.ui.state;

final class ThrottleSignal<T> extends PacedSignal<T> {
    private boolean trailing;   // 当前窗口内收到过失效, 状态锁内读写

    ThrottleSignal(AbstractSignal<T> source, long delay, Delayer delayer) {
        super(source, delay, delayer);
    }

    @Override
    boolean onSourceDirtyLocked() {
        if (this.waitingLocked()) {
            this.trailing = true;
            return false;
        }
        return this.emitAndOpenWindowLocked();
    }

    @Override
    boolean onFireLocked() {
        if (!this.trailing) return false;
        this.trailing = false;
        return this.emitAndOpenWindowLocked();
    }

    // 停表时丢弃当前窗口内的待发记录
    @Override
    void onInactiveLocked() {
        this.trailing = false;
    }

    // 上游确有变化才发出. 先排窗口到期的任务再拍快照, 排不进去时快照不动, 下次失效重试.
    private boolean emitAndOpenWindowLocked() {
        if (!this.sourceChangedLocked()) return false;
        this.scheduleLocked();
        this.captureLocked();
        return true;
    }
}
