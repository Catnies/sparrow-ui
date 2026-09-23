package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.state.internal.collection.SetSignalImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 可订阅变化的集合, {@link #get()} 返回自身的活视图. 写入能力由具体实现决定, 工厂返回 {@link MutableSetSignal}.
 * <p>{@link #asReadOnly()} 返回不可修改视图, 源集合变化后仍会更新内容并发送失效; 元素中的对象本身可以是可变的.
 * <p>判等使用包装器身份, 派生函数应返回不可变结果. <strong>禁止长期存放 {@code Player}、{@code Entity}、{@code World}.</strong>
 *
 * @param <E> 元素类型
 */
@ApiStatus.NonExtendable
public interface SetSignal<E> extends Signal<Set<E>>, Set<E> {

    /**
     * 返回复用的不可修改活视图, 源集合的修改仍会向该视图发送失效.
     * <p><strong>视图及其 get、迭代器都不允许修改集合</strong>, 写入操作抛出 {@link UnsupportedOperationException}.
     * 元素不复制, 线程安全与源集合一致; 在只读视图上调用本方法返回自身.
     *
     * @return 不可修改的集合 signal
     */
    @NotNull
    SetSignal<E> asReadOnly();

    /**
     * 包一个现成的 {@code Set}. 之后<strong>只能经包装器改它</strong>, 绕过包装器直接改 delegate 不会通知任何人.
     *
     * @param <E> 元素类型
     * @param delegate 被包装的 {@code Set}
     * @return 包装器
     */
    @NotNull
    static <E> MutableSetSignal<E> wrap(@NotNull Set<E> delegate) {
        return new SetSignalImpl<>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * 新建一个底层为 {@link LinkedHashSet} 的空集合 signal, 按插入顺序迭代.
     * <p><strong>不保证线程安全, 跨线程访问需要调用方同步.</strong> 需要其他集合实现时使用 {@link #wrap}.
     *
     * @param <E> 元素类型
     * @return 包装器
     */
    @NotNull
    static <E> MutableSetSignal<E> of() {
        return wrap(new LinkedHashSet<>());
    }
}
