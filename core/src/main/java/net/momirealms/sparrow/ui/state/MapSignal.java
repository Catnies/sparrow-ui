package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.state.internal.collection.MapSignalImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 可订阅变化的映射表, {@link #get()} 返回自身的活视图. 写入能力由具体实现决定, 工厂返回 {@link MutableMapSignal}.
 * <p>{@link #asReadOnly()} 返回不可修改视图, 源映射表变化后仍会更新内容并发送失效; 映射中的对象本身可以是可变的.
 * <p>判等使用包装器身份, 派生函数应返回不可变结果. <strong>禁止长期存放 {@code Player}、{@code Entity}、{@code World}.</strong>
 *
 * @param <K> key 类型
 * @param <V> 值类型
 */
@ApiStatus.NonExtendable
public interface MapSignal<K, V> extends Signal<Map<K, V>>, Map<K, V> {

    /**
     * 返回复用的不可修改活视图, 源映射表的修改仍会向该视图发送失效.
     * <p><strong>视图及其 get、keySet、values、entrySet 及 Map.Entry.setValue都不允许修改映射表</strong>, 写入操作抛出 {@link UnsupportedOperationException}.
     * 映射不复制, 线程安全与源映射表一致; 在只读视图上调用本方法返回自身.
     *
     * @return 不可修改的映射表 signal
     */
    @NotNull
    MapSignal<K, V> asReadOnly();

    /**
     * 包一个现成的 {@code Map}. 之后<strong>只能经包装器改它</strong>, 绕过包装器直接改 delegate 不会通知任何人.
     *
     * @param <K> key 类型
     * @param <V> 值类型
     * @param delegate 被包装的 {@code Map}
     * @return 包装器
     */
    @NotNull
    static <K, V> MutableMapSignal<K, V> wrap(@NotNull Map<K, V> delegate) {
        return new MapSignalImpl<>(Objects.requireNonNull(delegate, "delegate"));
    }

    /**
     * 新建一个底层为 {@link LinkedHashMap} 的空映射表 signal, 按插入顺序迭代, 允许 {@code null} key 和值.
     * <p><strong>不保证线程安全, 跨线程访问需要调用方同步.</strong> 需要其他映射表实现时使用 {@link #wrap}.
     *
     * @param <K> key 类型
     * @param <V> 值类型
     * @return 包装器
     */
    @NotNull
    static <K, V> MutableMapSignal<K, V> of() {
        return wrap(new LinkedHashMap<>());
    }
}
