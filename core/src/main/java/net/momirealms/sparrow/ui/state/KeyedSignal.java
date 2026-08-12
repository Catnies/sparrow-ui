package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 按 key 分区的响应式数据源, 每个分区是一个独立失效, 独立订阅的值.
 * <p><strong>K 禁止使用 {@code Player} 一类与在线会话绑定的重对象</strong>
 * 分区表是长命结构, 强持有此类对象会造成连锁的内存泄漏;
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
     * <p>同步来源打 stale 标记, 重算被下次 {@code get} 拉动; 异步来源调度一次后台重载.
     *
     * @param key 分区 key
     */
    void dirty(@NotNull K key);

    /**
     * 对所有已装载分区标脏.
     */
    void dirtyAll();

    /**
     * 返回指定分区的 {@link Signal} .
     * <p>分区被 {@link #remove(Object)} 并在下次访问重建后, 取得的视图会自动跟到新分区, 其订阅与派生保持有效.
     * <p>视图按弱引用缓存: 只要还有人持有它, 或者它上面还挂着绑定, 同一个 key 拿到的就是同一个实例;
     *
     * @param key 分区 key
     * @return 分区视图
     */
    @NotNull
    Signal<T> at(@NotNull K key);

    /**
     * 删除指定分区, 丢弃分区及其缓存值.
     *
     * @param key 分区 key
     */
    void remove(@NotNull K key);

    /**
     * 驱逐所有分区.
     */
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
     * 创建一个异步分区数据源.
     * <p>分区装载时即调度一次首载, 完成前 {@code get} 返回占位值.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行, 必须线程安全
     * @return 分区 signal
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> async(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader) {
        return new AsyncKeyedSignalImpl<>(placeholder, executor, loader);
    }
}
