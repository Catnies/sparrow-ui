package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 一组注册处理器的写时复制容器.
 * <p>读取只发布 volatile 快照, 增删整体替换为新列表. 因此迭代中的快照不会观察到同轮增删,
 * 单个处理器的异常也不会中断后续处理器.
 */
public final class HandlerList<T> {
    private volatile List<T> handlers; // 当前生效的处理器列表, 增删时整体替换

    public HandlerList(@NotNull List<T> handlers) {
        this.handlers = handlers;
    }

    /**
     * 返回当前处理器列表快照.
     *
     * @return 处理器列表快照
     */
    @NotNull
    @Unmodifiable
    public List<T> snapshot() {
        return this.handlers;
    }

    /**
     * 整体替换处理器列表.
     *
     * @param handlers 新的处理器列表
     */
    public void set(@NotNull List<T> handlers) {
        this.handlers = handlers;
    }

    /**
     * 在列表末尾追加一个处理器.
     *
     * @param handler 待追加的处理器
     */
    public void append(@NotNull T handler) {
//        ArrayList<T> copy = new ArrayList<>(this.handlers.size() + 1);
//        copy.addAll(this.handlers);
//        copy.add(handler);
//        this.handlers = List.copyOf(copy);
        this.handlers = MiscUtils.append(this.handlers, handler);
    }

    /**
     * 移除首个匹配的处理器.
     *
     * @param handler 待移除的处理器
     */
    public void remove(@NotNull T handler) {
        this.handlers = MiscUtils.remove(this.handlers, handler);
    }

    /**
     * 按注册顺序在调用时的列表快照上运行每个处理器.
     * 单个处理器失败会上报并继续执行后续处理器.
     *
     * @param action 对每个处理器执行的动作
     * @param failureMessage 失败报告文本
     * @param reporter 失败上报目标
     */
    public void forEachIsolated(
            @NotNull Consumer<? super T> action,
            @NotNull String failureMessage,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    ) {
        List<T> snapshot = this.handlers;
        for (int index = 0; index < snapshot.size(); index++) {
            try {
                action.accept(snapshot.get(index));
            } catch (Throwable throwable) {
                reporter.accept(failureMessage, throwable);
            }
        }
    }
}
