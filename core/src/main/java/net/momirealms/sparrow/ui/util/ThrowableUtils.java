package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 提供异常聚合、非受检异常传播与受检异常穿透功能.
 */
public final class ThrowableUtils {
    private ThrowableUtils() {}

    /**
     * 聚合两个异常. 如果尚无主异常, 直接返回新异常; 否则把新异常附加为 suppressed 异常.
     *
     * @param first 当前主异常, 或 {@code null}
     * @param next 要合并的新异常
     * @return 非空主异常
     */
    @NotNull
    public static Throwable combine(@Nullable Throwable first, @NotNull Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
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
     * 执行一个可能抛出异常的提供者, 并通过 sneakyThrow 将任何捕获的异常作为非受检异常抛出.
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
     * 利用泛型类型擦除机制, 欺骗编译器将受检异常作为非受检异常抛出.
     * 使用时需要注意, 调用此方法后代码执行会中断.
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
     * 执行一个任务, 如果发送了异常则接续到传入的异常里.
     *
     * @param task 执行的任务
     * @param throwable 被接续的异常
     */
    public static void runCatchAddSuppressed(@NotNull Runnable task, @NotNull Throwable throwable) {
        try {
            task.run();
        } catch (Throwable t) {
            throwable.addSuppressed(t);
        }
    }

    /**
     * 函数式接口, 表示一个不接受参数且返回结果的提供者, 在执行过程中可能抛出异常.
     * 
     * @param <T> 提供者返回的结果类型
     */
    @FunctionalInterface
    public interface ThrowableSupplier<T> {

        /**
         * 获取一个结果.
         * 
         * @return 计算得到的结果
         * @throws Throwable 如果在计算过程中发生错误
         */
        T get() throws Throwable;
    }
}
