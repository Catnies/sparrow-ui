package net.momirealms.sparrow.ui.state;

/**
 * 节流节点, 两次发出之间至少隔 delay.
 * <p>没有打开的窗口时立即发出并开一个窗口; 窗口内的失效只记下待发, 窗口到期时补发一次并续开窗口, 没有待发就关窗.
 *
 * @param <T> 值类型
 */
final class ThrottleSignal<T> extends PacedSignal<T> {
    private boolean trailing;   // 窗口内收到过失效, 到期要补发. 状态锁内读写

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

    // 上游确有变化才发出. 先排窗口到期的任务再拍快照, 排不进去时快照不动, 下次失效重试.
    private boolean emitAndOpenWindowLocked() {
        if (!this.sourceChangedLocked()) return false;
        this.scheduleLocked();
        this.captureLocked();
        return true;
    }
}
