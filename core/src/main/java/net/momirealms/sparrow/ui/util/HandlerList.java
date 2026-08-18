package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class HandlerList<T> {
    private volatile List<T> handlers; // 当前处理器快照, 每次修改都替换为新的不可变列表

    public HandlerList(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    @NotNull
    @Unmodifiable
    public List<T> snapshot() {
        return this.handlers;
    }


    public void set(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public void append(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers.size() + 1);
        copy.addAll(this.handlers);
        copy.add(handler);
        this.handlers = List.copyOf(copy);
    }

    public void remove(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers);
        copy.remove(handler);
        this.handlers = List.copyOf(copy);
    }

    /**
     * 依次对每个处理器执行 action, 单个处理器抛出的异常不会中断其余处理器,
     * 而是附带 failureMessage 交给 reporter 上报.
     *
     * @param action 对每个处理器执行的动作
     * @param failureMessage 上报失败时附带的消息
     * @param reporter 异常上报器, 接收失败消息与被处理器抛出的异常
     */
    public void forEachIsolated(
            @NotNull Consumer<? super T> action,
            @NotNull String failureMessage,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    ) {
        // 快照后遍历, 执行期间不受并发修改影响; 异常被逐个隔离上报
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
     * 把 Consumer<? super T> 收窄为 Consumer<T>, 便于存入元素类型精确的列表.
     *
     * @param consumer 原始消费者
     * @param <T> 元素类型
     * @return 收窄后的消费者
     */
    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }

    /**
     * 把处理器列表拷贝为元素类型精确的不可变列表.
     *
     * @param consumers 原始处理器列表
     * @param <T> 元素类型
     * @return 收窄并拷贝后的不可变列表
     */
    public static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }

    /**
     * 把 BiConsumer<? super T, ? super U> 收窄为 BiConsumer<T, U>, 便于存入元素类型精确的列表.
     *
     * @param consumer 原始消费者
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 收窄后的消费者
     */
    @SuppressWarnings("unchecked")
    public static <T, U> BiConsumer<T, U> narrowBiConsumer(BiConsumer<? super T, ? super U> consumer) {
        return (BiConsumer<T, U>) consumer;
    }

    /**
     * 把 BiConsumer 处理器列表拷贝为元素类型精确的不可变列表.
     *
     * @param consumers 原始处理器列表
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 收窄并拷贝后的不可变列表
     */
    public static <T, U> List<BiConsumer<T, U>> copyBiConsumers(List<? extends BiConsumer<? super T, ? super U>> consumers) {
        ArrayList<BiConsumer<T, U>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowBiConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }
}
