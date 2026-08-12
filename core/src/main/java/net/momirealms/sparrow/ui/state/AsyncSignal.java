package net.momirealms.sparrow.ui.state;

/**
 * 异步来源的响应式数据源, 值由后台执行器重算, 读取永不阻塞.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface AsyncSignal<T> extends Signal<T> permits AsyncSignalImpl {

    /**
     * 声明当前值已过期, 调度一次后台重载, 任意线程, 立即返回.
     * <p>重载进行中再次失效会被合并: 当前这轮完成后仅补跑一轮, 不会排队堆积.
     * 重载结果与旧值相等时不产生失效, 若重载抛出的异常, 旧值保持不变.
     * <p>执行器拒绝任务时状态机会回滚到可再次调度的状态, 极窄窗口内并发登记的失效可能丢失, 下一次调用会恢复.
     */
    void dirty();
}
