package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
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
     * <p><strong>取句柄既不推动装载, 也不算订阅</strong>: 分区在这一刻建出来, 到分区的转发要等句柄有了第一个订阅者才挂,
     * 最后一个订阅者走了就摘, 异步来源的首载要等第一次读. 所以给一个已经离线的玩家取句柄, 不会替他去查一次数据库;
     * 只取过句柄而没人订阅的分区, 在分区看来也没有订阅.
     *
     * @param key 分区 key
     * @return 分区句柄
     */
    @NotNull
    Signal<T> at(@NotNull K key);

    void remove(@NotNull K key);

    void clear();

    /**
     * 本 signal 失效侧的弱控制句柄, 每次调用新建一个, 语义见 {@link WeakKeyedControl}.
     * <p>signal 与登记表同寿命时不需要它, 直接调本 signal 即可; 要把本 signal 登记到比它活得久的总线、订阅、定时任务上时, 捕获这个句柄而不是 signal.
     *
     * @return 弱持本 signal 的控制句柄
     */
    @NotNull
    WeakKeyedControl<K> weakControl();

    /**
     * 当前有分区的 key. 建分区(首次 {@code get} / {@code at} 到的 key)与 {@link #remove} 都让它失效, 分区的值变了它不失效.
     * <p>值是一份不可修改的快照, 顺序不保证, 每次拉取按分区数复制一遍, 分区极多时慎用.
     * <p><strong>它是 "有分区的 key", 不是业务上存在的 key</strong>: {@link #get} 一个没见过的 key 也会建分区并出现在这里.
     * 房间、邀请、编辑会话这类由写入建行的用法, 它就是名单; 金币、统计这类由读取装载的缓存用法, 它只是缓存内省, 别当业务名单用.
     * <p>{@link #clear} 逐个删分区, 会让它失效 N 次.
     *
     * @return 有分区的 key 的集合
     */
    @NotNull
    Signal<Set<K>> keys();

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
        return new KeyedSignalImpl<>(initial, sameValue);
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
        return async(placeholder, executor, loader, AbstractSignal.defaultSameValue());
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
        return new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, null);
    }

    /**
     * 创建一个轮询的异步分区数据源, 语义同 {@link Signal#polling(Object, Executor, Supplier, long)}, 按分区各自算有没有订阅.
     * <p>只有句柄被订阅了的那几个分区在轮询, 取句柄不算订阅; 分页场景下就是只有正在显示的那一页在刷.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodTicks 轮询周期, 必须为正
     * @return 轮询的分区 signal
     * @throws IllegalArgumentException {@code periodTicks} 不是正数
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> polling(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader, long periodTicks) {
        return polling(placeholder, executor, loader, periodTicks, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #polling(Object, Executor, Function, long)}, 但指定判等函数, 全部分区共用同一个.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodTicks 轮询周期, 必须为正
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 轮询的分区 signal
     * @throws IllegalArgumentException {@code periodTicks} 不是正数
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> polling(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader, long periodTicks, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, AsyncSignalImpl.Polling.everyTicks(periodTicks));
    }

    /**
     * 同 {@link #polling(Object, Executor, Function, long)}, 但以毫秒计, 时钟挂在 Paper 异步调度器上.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @return 轮询的分区 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader, long periodMillis) {
        return pollingMillis(placeholder, executor, loader, periodMillis, AbstractSignal.defaultSameValue());
    }

    /**
     * 同 {@link #pollingMillis(Object, Executor, Function, long)}, 但指定判等函数, 全部分区共用同一个.
     *
     * @param placeholder 每个分区首载完成前的占位值, 允许为 {@code null}
     * @param executor 执行装载的执行器
     * @param loader 分区装载函数, 在 executor 线程执行.
     * @param periodMillis 轮询周期毫秒数, 不小于 50
     * @param sameValue 判等函数, 语义见 {@link Signal#of(Object, BiPredicate)}
     * @return 轮询的分区 signal
     * @throws IllegalArgumentException {@code periodMillis} 小于 50
     */
    @NotNull
    static <K, T> KeyedSignal<K, T> pollingMillis(T placeholder, @NotNull Executor executor, @NotNull Function<? super K, ? extends T> loader, long periodMillis, @NotNull BiPredicate<? super T, ? super T> sameValue) {
        return new AsyncKeyedSignalImpl<>(placeholder, executor, loader, sameValue, AsyncSignalImpl.Polling.everyMillis(periodMillis));
    }
}
