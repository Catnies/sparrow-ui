package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.util.ConcurrentUUID2ReferenceChainedHashTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
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

    /**
     * UUID key 的特化存储, 由内联 key 的 {@link ConcurrentUUID2ReferenceChainedHashTable} 承载.
     */
    @NotNull
    static <S> KeyStateStore<UUID, S> uuid() {
        return new UuidStore<>();
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

    final class UuidStore<S> implements KeyStateStore<UUID, S> {
        private final ConcurrentUUID2ReferenceChainedHashTable<S> table = new ConcurrentUUID2ReferenceChainedHashTable<>();

        @Override
        @Nullable
        public S get(@NotNull UUID key) {
            return this.table.get(key);
        }

        @Override
        public S compute(@NotNull UUID key, @NotNull BiFunction<? super UUID, ? super S, ? extends S> remapping) {
            return this.table.compute(key, remapping);
        }

        @Override
        public S computeIfPresent(@NotNull UUID key, @NotNull BiFunction<? super UUID, ? super S, ? extends S> remapping) {
            return this.table.computeIfPresent(key, remapping);
        }

        @Override
        public void forEachKey(@NotNull Consumer<? super UUID> action) {
            this.table.forEachKey(action);
        }

        @Override
        public void forEachValue(@NotNull Consumer<? super S> action) {
            this.table.forEachValue(action);
        }
    }
}
