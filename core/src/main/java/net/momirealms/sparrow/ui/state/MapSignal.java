package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 包一个现成 {@link Map} 的集合装饰器, 自己就是那个 {@code Map}, 每次有效变更落地之后向订阅者失效.
 * <p>契约与 {@link ListSignal} 相同: {@link #get()} 返回包装器自己, 跨线程读的安全性由被包装的 {@code Map} 决定, 判等按身份,
 * 通知不带元素, 无效变更({@code put} 相同值、{@code remove} 不存在的 key)不通知,
 * {@code map} / {@code mapDistinct} 的 mapper <strong>必须返回不可变结果</strong>.
 * {@code keySet()} / {@code values()} / {@code entrySet()} 与 {@code Map.Entry.setValue} 都写穿并通知.
 * <p>映射到 {@code null} 值的条目移除时判断不了有没有删掉东西, 可能漏通知; 要用 {@code null} 值就别指望那一次通知.
 * <p><strong>包装器强持被包装的 {@code Map}</strong>. key 与值禁止放 {@code Player} / {@code Entity} / {@code World}.
 *
 * @param <K> key 类型
 * @param <V> 值类型
 */
public sealed interface MapSignal<K, V> extends Signal<Map<K, V>>, Map<K, V> permits MapSignalImpl {

    /**
     * 挂一个元素钩子, 值存入<strong>之前</strong>调用, 返回值才是真正存进去的, 原样返回就是不换.
     * <p>它是给 "把包装器注入到别人的字段里" 这种用法准备的, 例如换掉一个区块的方块实体表, 每个方块实体放进来时换成代理.
     * 钩子在写入线程同步跑, 通知永远在钩子之后; 挂多个按挂的顺序串着跑, 前一个的返回值是后一个的入参.
     * 替换已有映射时先对旧值跑 {@link #onRemoved} 的钩子, 再对新值跑本钩子, 旁表按 key 登记才不会把刚放进去的新条目误删.
     * <p>带钩子的 {@code put} 先读再写, 在并发 map 上不再原子; 钩子面向注入 NMS 那类单线程结构,
     * 并发 map 上要原子又要钩子就用 {@code compute} 一族, 它们的钩子跑在被包装 map 的重算函数里,
     * <strong>钩子里不得碰同一张 map</strong>(并发 map 会抛 {@code Recursive update}), 重算函数可能被重跑, 钩子要能重跑.
     * <strong>钩子里不得订阅本 signal 再在回调里写它</strong>, 那是重入. 钩子是构造期配置, 要在把包装器交出去之前挂好.
     *
     * @param hook 收到 key 与调用方要放的值, 返回真正存进去的
     * @return 本包装器
     */
    @NotNull
    MapSignal<K, V> onPut(@NotNull BiFunction<? super K, ? super V, ? extends V> hook);

    /**
     * 挂一个元素钩子, 映射从 map 移除<strong>之后</strong>调用, 收到的是被存着的那个值.
     * <p>钩子抛出时变更已经落地, 异常原样抛给写入方, 订阅者仍会收到这次变更的通知.
     *
     * @param hook 收到被移除的 key 与值
     * @return 本包装器
     */
    @NotNull
    MapSignal<K, V> onRemoved(@NotNull BiConsumer<? super K, ? super V> hook);

    /**
     * 把 {@code changes} 期间本线程对本集合的变更合并成一次通知, 嵌套时只有最外层通知, 语义同 {@link ListSignal#batch}.
     *
     * @param changes 要合并的一批变更
     */
    void batch(@NotNull Runnable changes);

    /**
     * 包一个现成的 {@code Map}. 之后<strong>只能经包装器改它</strong>, 绕过包装器直接改 delegate 不会通知任何人.
     *
     * @param delegate 被包装的 {@code Map}
     * @return 包装器
     */
    @NotNull
    static <K, V> MapSignal<K, V> wrap(@NotNull Map<K, V> delegate) {
        return new MapSignalImpl<>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * 新建一个包着 {@link ConcurrentHashMap} 的装饰器, 任何线程都能安全读写.
     * <p><strong>它不保迭代顺序</strong>, 也不接受 {@code null} key 或值; 要顺序就 {@code wrap(new LinkedHashMap<>())} 并自己管线程.
     *
     * @return 包装器
     */
    @NotNull
    static <K, V> MapSignal<K, V> of() {
        return wrap(new ConcurrentHashMap<>());
    }
}
