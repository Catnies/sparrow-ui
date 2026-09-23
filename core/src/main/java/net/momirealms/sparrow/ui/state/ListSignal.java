package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.state.internal.collection.ListSignalImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 可订阅变化的列表. 写入能力由具体实现决定, 工厂返回 {@link MutableListSignal}.
 * <p>{@link #asReadOnly()} 返回不可修改视图, 源列表变化后仍会更新内容并发送失效; 元素对象本身可以是可变的.
 * <p>判等使用包装器身份, 派生函数应返回不可变结果. 自建列表应及时清理 {@code Player}、{@code Entity}、{@code World} 引用.
 *
 * @param <E> 元素类型
 */
@ApiStatus.NonExtendable
public interface ListSignal<E> extends Signal<List<E>>, List<E> {

    /**
     * 读取当前列表. {@link #of()}、{@link #wrap(List)} 及其只读视图返回自身的活视图;
     * {@link Signals#onlinePlayers()} 返回本次读取时的不可修改快照, 名单未变化时复用同一份.
     *
     * @return 当前列表内容
     */
    @NotNull
    @Override
    List<E> get();

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
     * 新建一个底层为 {@link ArrayList} 的空列表 signal.
     * <p><strong>不保证线程安全, 跨线程访问需要调用方同步.</strong> 需要其他列表实现时使用 {@link #wrap}.
     *
     * @param <E> 元素类型
     * @return 包装器
     */
    @NotNull
    static <E> MutableListSignal<E> of() {
        return wrap(new ArrayList<>());
    }
}
