package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 按 key 分区的响应式数据源, 每个分区是一个独立失效, 独立订阅的值.
 * <p><strong>K 禁止使用 {@code Player} 一类与在线会话绑定的重对象</strong>.
 *
 * @param <K> 分区 key 类型
 * @param <T> 值类型, 允许为 {@code null}
 */
public interface KeyedSignal<K, T> {

    /**
     * 读取指定分区的当前值.
     *
     * @param key 分区 key
     * @return 分区当前快照值
     */
    T get(@NotNull K key);

    /**
     * 对指定分区的进行标脏.
     * <p>同步来源打 stale 标记, 重算被下次 {@code get} 拉动, 异步来源调度一次后台重载.
     *
     * @param key 分区 key
     */
    void dirty(@NotNull K key);

    void dirtyAll();

    /**
     * 返回指定分区的 {@link Signal}.
     * <p>分区被 {@link #remove(Object)} 并在下次访问重建后, 取得的句柄会自动跟到新分区, 其订阅与派生保持有效.
     * <p>句柄按弱引用缓存, 只要还有人持有句柄, 或者句柄上面还挂着绑定, 同一个 key 拿到的就是同一个实例.
     * <p><strong>取句柄不会推动装载</strong>: 转发在这一刻就挂好了, 但异步来源的首载要等第一次读.
     * 所以给一个已经离线的玩家取句柄, 不会替他去查一次数据库.
     *
     * @param key 分区 key
     * @return 分区句柄
     */
    @NotNull
    Signal<T> at(@NotNull K key);

    void remove(@NotNull K key);

    void clear();

    /**
     * 创建一个同步分区数据源.
     *
     * @param initial 分区装载与重算函数, 在读取线程执行.
     * @return 可写分区 signal
     */
    @NotNull
    static <K, T> MutableKeyedSignal<K, T> of(@NotNull Function<? super K, ? extends T> initial) {
        return new KeyedSignalImpl<>(initial);
    }

    /**
     * 创建一个同步分区数据源, 并指定判等函数, 全部分区共用同一个.
     *
     * @param initial 分区装载与重算函数, 在读取线程执行.
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 可写分区 signal
     */
    @NotNull
    static <K, T> MutableKeyedSignal<K, T> of(@NotNull Function<? super K, ? extends T> initial, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new KeyedSignalImpl<>(initial, sameValue, KeyStateStore.generic());
    }

    /**
     * 创建一个异步分区数据源.
     * <p>分区装载时即调度一次首载, 完成前 {@code get} 返回占位值.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @return 分区 signal
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> async(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader) {
        return new AsyncKeyedSignalImpl<>(placeholder, executor, loader);
    }

    /**
     * 创建一个异步分区数据源, 并指定判等函数, 全部分区共用同一个.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 分区 signal
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> async(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, KeyStateStore.generic());
    }
}
