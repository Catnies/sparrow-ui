package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletionException;

public final class ThrowableUtils {
    private ThrowableUtils() {}

    /**
     * 合并两个异常, 后续异常以 suppressed 形式挂到已有主异常上.
     *
     * @param first 当前主异常, 或 {@code null}
     * @param next 后续异常
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
     * 执行清理动作, 将 RuntimeException 或 Error 聚合到已有异常.
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
     * 解开一层 {@link CompletionException}, 其他异常原样返回.
     *
     * @param throwable 异步阶段给出的异常
     * @return 包装原因, 或原异常
     */
    @NotNull
    public static Throwable unwrapCompletion(@NotNull Throwable throwable) {
        return throwable instanceof CompletionException completionException
                && completionException.getCause() != null
                ? completionException.getCause()
                : throwable;
    }

    /**
     * 原样抛出 RuntimeException 或 Error, 受检异常和 {@code null} 保持静默.
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
     * 执行可抛出受检异常的 Supplier, 异常原样重抛且不增加包装.
     *
     * @param supplier 可抛出异常的计算
     * @param <T> 返回值类型
     * @return 计算结果
     */
    public static <T> T sneakyThrow(ThrowableSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * 利用泛型擦除原样抛出异常, 不增加包装.
     *
     * @param t 要抛出的异常
     * @param <E> 泛型异常类型
     * @return 此方法不会正常返回
     * @throws E 原异常
     */
    @SuppressWarnings("unchecked")
    public static <E extends Throwable> E sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * 可以抛出任意异常的 Supplier.
     *
     * @param <T> 返回的结果类型
     */
    @FunctionalInterface
    public interface ThrowableSupplier<T> {

        /**
         * 执行计算并返回结果.
         *
         * @return 执行结果
         * @throws Throwable 执行过程中抛出的任意异常
         */
        T get() throws Throwable;
    }
}
