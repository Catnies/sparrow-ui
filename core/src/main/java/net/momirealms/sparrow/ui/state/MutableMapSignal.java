package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.Subscription;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 支持映射表修改、元素钩子和批量通知的 {@link MapSignal}. 线程安全由被包装的映射表决定.
 * <p>三个集合视图和 Map.Entry.setValue 都会写回包装器并发送失效. 没有元素钩子时, 非空 putAll 即使映射相同也会通知.
 * <p>允许 null 值的 delegate 在 key 已映射到 null 且 compute 仍返回 null 时会删除条目, 这次删除无法从返回值辨认, 因而不发送失效.
 *
 * @param <K> key 类型
 * @param <V> 值类型
 */
@ApiStatus.NonExtendable
public interface MutableMapSignal<K, V> extends MapSignal<K, V> {

    /**
     * 挂一个元素钩子, 值存入<strong>之前</strong>调用, 返回值才是真正存进去的, 原样返回就是不换.
     * <p>钩子在写入线程同步执行, 多个钩子按注册顺序串联, 前一个返回值会传给后一个.
     * 替换已有映射时先对旧值执行 {@link #afterRemove} 钩子, 再处理新值,
     * 让按 key 维护的旁表先释放旧记录.
     * <p>带钩子的 {@code put} 会先读后写, 在并发 map 上不具备按 key 的原子性. {@code compute} 一族会在 delegate 的重算函数中执行钩子,
     * 钩子可能重跑, 并且<strong>不得再次操作同一张 map</strong>.
     * <p><strong>钩子属于构造期配置, 应在发布包装器之前注册, 并且不得建立会写回本 signal 的订阅.</strong>
     *
     * <pre>{@code
     * MutableMapSignal<String, Integer> scores = MapSignal.of();
     * Subscription hook = scores.beforePut((name, score) -> Math.max(0, score));
     * }</pre>
     *
     * @param hook 收到 key 与调用方要放的值, 返回真正存进去的
     * <p><strong>只弱持有钩子, 调用方必须保存返回的凭证</strong>, 寿命见 {@link MutableListSignal#beforeAdd}.
     *
     * @return 钩子凭证, 关闭即摘除这个钩子
     */
    @NotNull
    Subscription beforePut(@NotNull BiFunction<? super K, ? super V, ? extends V> hook);

    /**
     * 挂一个元素钩子, 映射从 map 移除<strong>之后</strong>调用, 收到的是被存着的那个值.
     * <p>钩子抛出时变更已经落地, 异常会抛给写入方, 订阅者仍会收到这次失效.
     *
     * @param hook 收到被移除的 key 与值
     * <p>寿命同 {@link #beforePut}: 只弱持有钩子, 调用方必须保存凭证.
     *
     * @return 钩子凭证, 关闭即摘除这个钩子
     */
    @NotNull
    Subscription afterRemove(@NotNull BiConsumer<? super K, ? super V> hook);

    /**
     * 把 {@code changes} 期间本线程对本集合的变更合并成一次通知, 语义见 {@link MutableListSignal#batch}.
     *
     * @param changes 要合并的一批变更
     */
    void batch(@NotNull Runnable changes);

}
