package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.state.internal.collection.ListSignalImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 可订阅变化的列表, {@link #get()} 返回自身的活视图. 写入能力由具体实现决定, 工厂返回 {@link MutableListSignal}.
 * <p>{@link #asReadOnly()} 返回不可修改视图, 源列表变化后仍会更新内容并发送失效; 元素对象本身可以是可变的.
 * <p>判等使用包装器身份, 派生函数应返回不可变结果. <strong>禁止长期存放 {@code Player}、{@code Entity}、{@code World}.</strong>
 *
 * @param <E> 元素类型
 */
@ApiStatus.NonExtendable
public interface ListSignal<E> extends Signal<List<E>>, List<E> {

    /**
     * 返回复用的不可修改活视图, 源列表的修改仍会向该视图发送失效.
     * <p><strong>视图及其 get、迭代器、子列表和反向视图都不允许修改列表</strong>, 写入操作抛出 {@link UnsupportedOperationException}.
     * 元素不复制, 线程安全与源列表一致; 在只读视图上调用本方法返回自身.
     *
     * @return 不可修改的列表 signal
     */
    @NotNull
    ListSignal<E> asReadOnly();

    /**
     * 包一个现成的 {@code List}. 之后<strong>只能经包装器改它</strong>, 绕过包装器直接改 delegate 不会通知任何人.
     *
     * @param <E> 元素类型
     * @param delegate 被包装的 {@code List}
     * @return 包装器
     */
    @NotNull
    static <E> MutableListSignal<E> wrap(@NotNull List<E> delegate) {
        return new ListSignalImpl<>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * 新建一个包着 {@link CopyOnWriteArrayList} 的装饰器, 支持写入期间的并发迭代.
     * <p>写时复制每次写都复制整个数组, 热路径或大集合要按自己的访问模式另选 delegate 用 {@link #wrap}.
     *
     * @param <E> 元素类型
     * @return 包装器
     */
    @NotNull
    static <E> MutableListSignal<E> of() {
        return wrap(new CopyOnWriteArrayList<>());
    }
}
