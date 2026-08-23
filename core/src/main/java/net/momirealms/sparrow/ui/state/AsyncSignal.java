package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

/**
 * 异步来源的响应式数据源, 值由后台执行器重算, 读取永不阻塞.
 *
 * @param <T> 值类型, 允许为 {@code null}
 */
public sealed interface AsyncSignal<T> extends Signal<T> permits AsyncSignalImpl {

    /**
     * 声明当前值已过期, 调度一次后台重载.
     * <p>在本 signal 的装载函数里调用它会抛出 {@link IllegalStateException}, 那是一个自己喂自己的死循环.
     * <p>重载进行中再次标脏会在当前这轮完成后补跑一轮, 不会排队堆积.
     * 判等函数认为重载结果与旧值相同时不产生失效信号, 若重载抛出的异常, 旧值保持不变.
     * <p>执行器拒绝任务时, 会回滚到可再次调度的状态, 但并发登记的失效信号可能丢失, 需要重新调用.
     */
    void dirty();

    /**
     * 本 signal 失效侧的弱控制句柄, 每次调用新建一个, 语义见 {@link WeakAsyncControl}.
     * <p>signal 与登记表同寿命时不需要它, 直接调 {@link #dirty} 即可; 要把本 signal 登记到比它活得久的总线、订阅、定时任务上时, 捕获这个句柄而不是 signal.
     *
     * @return 弱持本 signal 的控制句柄
     */
    @NotNull
    WeakAsyncControl weakControl();
}
