package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 包一个现成 {@link List} 的集合装饰器, 自己就是那个 {@code List}, 每次有效变更落地之后向订阅者失效.
 * <p>{@link #get()} 返回包装器自己, 是活视图; 跨线程读的安全性由被包装的 {@code List} 决定,
 * 要给渲染线程或异步 Provider 读就包一个写时复制的. 判等按身份, 内容相同的两个包装器不相等, 要比内容就比 {@code List.copyOf(wrapper)}.
 * <p>通知只说 "变了", 不带元素; 无效变更({@code set} 写回同一个对象、空的 {@code addAll})不通知.
 * {@code sort} 只要至少有两个元素就会通知, 顺序没有变化也一样.
 * {@code map} / {@code mapDistinct} 的 mapper <strong>必须返回不可变结果</strong>, 直接返回这个活集合会让判等永远说 "没变".
 * <p><strong>包装器强持被包装的 {@code List}</strong>; 注入到长命结构里时它随该结构活着. 元素禁止放 {@code Player} / {@code Entity} / {@code World}.
 *
 * @param <E> 元素类型
 */
public sealed interface ListSignal<E> extends Signal<List<E>>, List<E> permits ListSignalImpl {

    /**
     * 挂一个元素钩子, 元素存入<strong>之前</strong>调用, 返回值才是真正存进去的, 原样返回就是不换.
     * <p>它是给 "把包装器注入到别人的字段里" 这种用法准备的, 订阅者只知道 "表变了", 钩子才知道谁来了.
     * 钩子在写入线程同步跑, 通知永远在钩子之后; 挂多个按挂的顺序串着跑, 前一个的返回值是后一个的入参.
     * 替换类操作({@code set}、{@code ListIterator.set})先对旧元素跑 {@link #afterRemove} 的钩子, 再对新元素跑本钩子.
     * <p>换值有一条边界: 调用方之后拿原对象来 {@code remove(Object)} / {@code contains}, 按 {@code equals} 找不到换过的那个.
     * <strong>钩子里不得订阅本 signal 再在回调里写它</strong>, 那是重入. 钩子是构造期配置, 要在把包装器交出去之前挂好.
     *
     * @param hook 收到调用方要放的元素, 返回真正存进去的
     * @return 本包装器
     */
    @NotNull
    ListSignal<E> beforeAdd(@NotNull Function<? super E, ? extends E> hook);

    /**
     * 挂一个元素钩子, 元素从集合移除<strong>之后</strong>调用. 按下标、按迭代器移除时收到的是被存着的那个;
     * {@code remove(Object)} 只能给调用方传入的参数, 对身份判等的元素类型两者是同一个.
     * <p>钩子抛出时变更已经落地, 异常原样抛给写入方, 订阅者仍会收到这次变更的通知.
     *
     * @param hook 收到被移除的元素
     * @return 本包装器
     */
    @NotNull
    ListSignal<E> afterRemove(@NotNull Consumer<? super E> hook);

    /**
     * 把 {@code changes} 期间本线程对本集合的变更合并成一次通知, 嵌套时只有最外层通知.
     * <p>只合并本线程的变更, 别的线程这期间的变更照常各自通知. {@code changes} 抛出时已经落地的变更保留并仍通知一次.
     * <p>{@code changes} 与随后通知都抛出时, batch 最终抛出通知异常, {@code changes} 的异常不会保留.
     *
     * @param changes 要合并的一批变更
     */
    void batch(@NotNull Runnable changes);

    /**
     * 包一个现成的 {@code List}. 之后<strong>只能经包装器改它</strong>, 绕过包装器直接改 delegate 不会通知任何人.
     *
     * @param delegate 被包装的 {@code List}
     * @return 包装器
     */
    @NotNull
    static <E> ListSignal<E> wrap(@NotNull List<E> delegate) {
        return new ListSignalImpl<>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * 新建一个包着 {@link CopyOnWriteArrayList} 的装饰器, 任何线程都能安全迭代.
     * <p>写时复制每次写都复制整个数组, 热路径或大集合要按自己的访问模式另选 delegate 用 {@link #wrap}.
     *
     * @return 包装器
     */
    @NotNull
    static <E> ListSignal<E> of() {
        return wrap(new CopyOnWriteArrayList<>());
    }
}
