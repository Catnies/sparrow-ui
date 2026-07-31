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

    public HandlerList(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
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
    public void set(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /**
     * 在列表末尾追加一个处理器.
     *
     * @param handler 待追加的处理器
     */
    public void append(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers.size() + 1);
        copy.addAll(this.handlers);
        copy.add(handler);
        this.handlers = List.copyOf(copy);
    }

    /**
     * 移除首个匹配的处理器.
     *
     * @param handler 待移除的处理器
     */
    public void remove(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers);
        copy.remove(handler);
        this.handlers = List.copyOf(copy);
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

    /**
     * 将接受 {@code ? super T} 的消费者窄化为 {@code Consumer<T>}.
     *
     * @param consumer 待窄化的消费者
     * @param <T> 消费者接受的参数类型
     * @return 窄化后的消费者, 与原消费者为同一实例
     */
    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }

    /**
     * 将通配符类型的消费者列表复制为不可变的 {@code Consumer<T>} 列表.
     *
     * @param consumers 原消费者列表
     * @param <T> 消费者接受的参数类型
     * @return 窄化后的不可变消费者列表
     */
    public static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }

    /**
     * 将接受 {@code ? super T} 与 {@code ? super U} 的双参数消费者窄化为 {@code BiConsumer<T, U>}.
     *
     * @param consumer 待窄化的双参数消费者
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 窄化后的双参数消费者, 与原消费者为同一实例
     */
    @SuppressWarnings("unchecked")
    public static <T, U> BiConsumer<T, U> narrowBiConsumer(BiConsumer<? super T, ? super U> consumer) {
        return (BiConsumer<T, U>) consumer;
    }

    /**
     * 将通配符类型的双参数消费者列表复制为不可变的 {@code BiConsumer<T, U>} 列表.
     *
     * @param consumers 原双参数消费者列表
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 窄化后的不可变双参数消费者列表
     */
    public static <T, U> List<BiConsumer<T, U>> copyBiConsumers(List<? extends BiConsumer<? super T, ? super U>> consumers) {
        ArrayList<BiConsumer<T, U>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowBiConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }
}
