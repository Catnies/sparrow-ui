package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 包一个现成 {@link Set} 的集合装饰器, 自己就是那个 {@code Set}, 每次有效变更落地之后向订阅者失效.
 * <p>契约与 {@link ListSignal} 相同: {@link #get()} 返回包装器自己, 跨线程读的安全性由被包装的 {@code Set} 决定, 判等按身份,
 * 通知不带元素, 无效变更({@code add} 已有元素、{@code remove} 不存在的元素)不通知,
 * {@code map} / {@code mapDistinct} 的 mapper <strong>必须返回不可变结果</strong>.
 * <p><strong>包装器强持被包装的 {@code Set}</strong>. 元素禁止放 {@code Player} / {@code Entity} / {@code World}.
 *
 * @param <E> 元素类型
 */
public sealed interface SetSignal<E> extends Signal<Set<E>>, Set<E> permits SetSignalImpl {

    /**
     * 挂一个元素钩子, 元素存入<strong>之前</strong>调用, 返回值才是真正存进去的, 语义同 {@link ListSignal#onAdd}.
     * <p>{@code add} 先用原元素查重, 已有就不跑钩子; 钩子换出来的元素若与已有元素判等, 这次放入会落空.
     *
     * @param hook 收到调用方要放的元素, 返回真正存进去的
     * @return 本包装器
     */
    @NotNull
    SetSignal<E> onAdd(@NotNull Function<? super E, ? extends E> hook);

    /**
     * 挂一个元素钩子, 元素移除<strong>之后</strong>调用, 语义同 {@link ListSignal#onRemoved}.
     *
     * @param hook 收到被移除的元素
     * @return 本包装器
     */
    @NotNull
    SetSignal<E> onRemoved(@NotNull Consumer<? super E> hook);

    /**
     * 把 {@code changes} 期间本线程对本集合的变更合并成一次通知, 嵌套时只有最外层通知, 语义同 {@link ListSignal#batch}.
     *
     * @param changes 要合并的一批变更
     */
    void batch(@NotNull Runnable changes);

    /**
     * 包一个现成的 {@code Set}. 之后<strong>只能经包装器改它</strong>, 绕过包装器直接改 delegate 不会通知任何人.
     *
     * @param delegate 被包装的 {@code Set}
     * @return 包装器
     */
    @NotNull
    static <E> SetSignal<E> wrap(@NotNull Set<E> delegate) {
        return new SetSignalImpl<>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * 新建一个包着 {@link CopyOnWriteArraySet} 的装饰器, 任何线程都能安全迭代.
     * <p>写时复制每次写都复制整个数组, 且 {@code contains} 是线性的, 热路径或大集合要按自己的访问模式另选 delegate 用 {@link #wrap}.
     *
     * @return 包装器
     */
    @NotNull
    static <E> SetSignal<E> of() {
        return wrap(new CopyOnWriteArraySet<>());
    }
}
