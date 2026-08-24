package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class HandlerList<T> {
    private volatile List<T> handlers;

    /**
     * 使用给定处理器创建初始快照.
     *
     * @param handlers 初始处理器
     */
    public HandlerList(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /**
     * 返回当前不可变快照.
     *
     * @return 当前处理器快照
     */
    @NotNull
    @Unmodifiable
    public List<T> snapshot() {
        return this.handlers;
    }

    /**
     * 使用给定列表替换当前快照.
     *
     * @param handlers 新的处理器列表
     */
    public void set(@NotNull List<? extends T> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    /**
     * 在当前快照末尾添加一个处理器.
     *
     * @param handler 新处理器
     */
    public void append(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers.size() + 1);
        copy.addAll(this.handlers);
        copy.add(handler);
        this.handlers = List.copyOf(copy);
    }

    /**
     * 从当前快照中移除第一个相等的处理器.
     *
     * @param handler 要移除的处理器
     */
    public void remove(@NotNull T handler) {
        ArrayList<T> copy = new ArrayList<>(this.handlers);
        copy.remove(handler);
        this.handlers = List.copyOf(copy);
    }

    /**
     * 对当前快照中的每个处理器执行操作, 并分别上报处理器抛出的异常.
     * <p>reporter 自身抛出异常时立即停止遍历.
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
        // 本轮始终遍历同一份快照, 后续增删留给下一轮.
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
     * 将 {@code Consumer<? super T>} 视为 {@code Consumer<T>}.
     *
     * @param consumer 可接收 T 或其父类型的 Consumer
     * @param <T> 元素类型
     * @return 同一个 Consumer
     */
    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> narrowConsumer(Consumer<? super T> consumer) {
        return (Consumer<T>) consumer;
    }

    /**
     * 将 Consumer 列表复制为元素类型精确的不可变列表.
     *
     * @param consumers 要复制的 Consumer
     * @param <T> 元素类型
     * @return 元素类型精确的不可变副本
     */
    public static <T> List<Consumer<T>> copyConsumers(List<? extends Consumer<? super T>> consumers) {
        ArrayList<Consumer<T>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }

    /**
     * 将 {@code BiConsumer<? super T, ? super U>} 视为 {@code BiConsumer<T, U>}.
     *
     * @param consumer 可接收 T, U 或其父类型的 BiConsumer
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 同一个 BiConsumer
     */
    @SuppressWarnings("unchecked")
    public static <T, U> BiConsumer<T, U> narrowBiConsumer(BiConsumer<? super T, ? super U> consumer) {
        return (BiConsumer<T, U>) consumer;
    }

    /**
     * 将 BiConsumer 列表复制为元素类型精确的不可变列表.
     *
     * @param consumers 要复制的 BiConsumer
     * @param <T> 第一个参数类型
     * @param <U> 第二个参数类型
     * @return 元素类型精确的不可变副本
     */
    public static <T, U> List<BiConsumer<T, U>> copyBiConsumers(List<? extends BiConsumer<? super T, ? super U>> consumers) {
        ArrayList<BiConsumer<T, U>> copy = new ArrayList<>(consumers.size());
        for (int index = 0; index < consumers.size(); index++) {
            copy.add(narrowBiConsumer(consumers.get(index)));
        }
        return List.copyOf(copy);
    }
}
