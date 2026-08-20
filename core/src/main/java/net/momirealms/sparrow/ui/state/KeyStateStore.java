package net.momirealms.sparrow.ui.state;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * KeyedSignal 的 KeyState 存储抽象.
 *
 * @param <K> key 类型
 * @param <S> KeyState 类型
 */
interface KeyStateStore<K, S> {

    @Nullable
    S get(@NotNull K key);

    S compute(@NotNull K key, @NotNull BiFunction<? super K, ? super S, ? extends S> remapping);

    S computeIfPresent(@NotNull K key, @NotNull BiFunction<? super K, ? super S, ? extends S> remapping);

    // 弱一致遍历, 遍历中允许对本表执行删除.
    void forEachKey(@NotNull Consumer<? super K> action);

    void forEachValue(@NotNull Consumer<? super S> action);

    /**
     * 任意 key 类型的通用存储, 由 {@link ConcurrentHashMap} 承载.
     */
    @NotNull
    static <K, S> KeyStateStore<K, S> generic() {
        return new GenericStore<>();
    }

    final class GenericStore<K, S> implements KeyStateStore<K, S> {
        private final ConcurrentHashMap<K, S> map = new ConcurrentHashMap<>();

        @Override
        @Nullable
        public S get(@NotNull K key) {
            return this.map.get(key);
        }

        @Override
        public S compute(@NotNull K key, @NotNull BiFunction<? super K, ? super S, ? extends S> remapping) {
            return this.map.compute(key, remapping);
        }

        @Override
        public S computeIfPresent(@NotNull K key, @NotNull BiFunction<? super K, ? super S, ? extends S> remapping) {
            return this.map.computeIfPresent(key, remapping);
        }

        @Override
        public void forEachKey(@NotNull Consumer<? super K> action) {
            for (K key : this.map.keySet()) {
                action.accept(key);
            }
        }

        @Override
        public void forEachValue(@NotNull Consumer<? super S> action) {
            for (S state : this.map.values()) {
                action.accept(state);
            }
        }
    }
}
