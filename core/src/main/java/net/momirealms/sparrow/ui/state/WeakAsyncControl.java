package net.momirealms.sparrow.ui.state;

/**
 * {@link AsyncSignal} 的失效侧, <strong>弱持有</strong>目标, 是 {@link WeakKeyedControl} 在非分区 signal 上的对称版.
 * <p>用法与取舍同 {@link WeakKeyedControl}: signal 与登记表同寿命时不需要它; 要把一个菜单级的 signal 登记到全局总线上时,
 * 捕获 {@code signal.weakControl()} 而不是 signal 本身, signal 死后登记只剩一个空壳.
 */
public sealed interface WeakAsyncControl permits AsyncSignalImpl.Control {

    /**
     * 同 {@link AsyncSignal#dirty}, 目标已被回收时什么也不做.
     */
    void dirty();

    /**
     * 目标 signal 是否已被回收. 为真之后 {@link #dirty} 是空操作, 登记表可以据此把自己的条目摘掉.
     *
     * @return 目标已被回收
     */
    boolean isStale();
}
