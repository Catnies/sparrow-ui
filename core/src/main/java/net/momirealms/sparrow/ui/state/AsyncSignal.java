package net.momirealms.sparrow.ui.state;

/**
 * 由后台执行器重算的异步 Signal, 读取始终立即返回.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface AsyncSignal<T> extends Signal<T> permits AsyncSignalImpl {

    /**
     * 声明当前值已过期, 调度一次后台重载.
     * <p>重载进行中再次标脏只登记一轮补跑, 连续调用不会堆积任务. 新结果与当前值判等相同时不发送失效,
     * 装载抛出 {@link RuntimeException} 时保留最近一次成功结果.
     * <p>执行器拒绝任务后可以再次调度. 最终一次提交被拒时, 同一窄窗口内登记的并发失效可能需要调用方重新触发.
     *
     * @throws IllegalStateException 从本 signal 的装载函数中直接或间接调用
     */
    void dirty();
}
