package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletionException;

public final class ThrowableUtils {
    private ThrowableUtils() {}

    /**
     * 聚合两个异常. 如果尚无主异常, 直接返回新异常; 否则把新异常附加为 suppressed 异常.
     *
     * @param first 当前主异常, 或 {@code null}
     * @param next 要合并的新异常
     * @param <T> 异常类型
     * @return 非空主异常
     */
    @NotNull
    public static <T extends Throwable> T combine(@Nullable T first, @NotNull T next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * 执行一个只会抛出非受检异常的清理动作, 并把失败聚合到已有异常.
     *
     * @param failure 当前主异常, 或 {@code null}
     * @param action 清理动作
     * @return 聚合后的主异常, 没有失败时为 {@code null}
     */
    @Nullable
    public static Throwable captureUnchecked(@Nullable Throwable failure, @NotNull Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error throwable) {
            return combine(failure, throwable);
        }
        return failure;
    }

    /**
     * 解开 {@link CompletionException} 的包装, 取出真实原因.
     * 不是这种包装或没有原因时返回原异常.
     *
     * @param throwable 异步阶段给出的异常
     * @return 真实原因
     */
    @NotNull
    public static Throwable unwrapCompletion(@NotNull Throwable throwable) {
        return throwable instanceof CompletionException completionException
                && completionException.getCause() != null
                ? completionException.getCause()
                : throwable;
    }

    /**
     * 当异常属于 {@link RuntimeException} 或 {@link Error} 时原样重新抛出.
     * {@code null} 和受检异常不会产生任何操作.
     *
     * @param throwable 待检查的异常, 或 {@code null}
     */
    public static void throwIfUnchecked(@Nullable Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
    }

    /**
     * 执行一个可能抛出异常的提供者, 任何捕获的异常都会作为非受检异常重新抛出.
     *
     * @param supplier 可能抛出异常的逻辑代码块
     * @param <T> 返回值类型
     * @return 提供者执行后的返回值
     * @throws RuntimeException 当提供者抛出异常时, 实际抛出的是原始异常
     */
    public static <T> T sneakyThrow(ThrowableSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * 利用泛型类型擦除机制, 欺骗编译器把受检异常当作非受检异常抛出.
     * 调用此方法后代码执行会中断.
     *
     * @param t 需要抛出的异常实例
     * @param <E> 泛型异常类型
     * @return 实际上不会返回任何值, 因为总是会抛出异常
     * @throws E 被强制转换并抛出的异常
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> E sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * 可能抛出受检异常的供应者, 供 sneakyThrow 包装执行.
     *
     * @param <T> 返回的结果类型
     */
    @FunctionalInterface
    public interface ThrowableSupplier<T> {

        /**
         * 执行并返回结果, 允许抛出任意异常.
         *
         * @return 执行结果
         * @throws Throwable 执行过程中抛出的任意异常
         */
        T get() throws Throwable;
    }
}
