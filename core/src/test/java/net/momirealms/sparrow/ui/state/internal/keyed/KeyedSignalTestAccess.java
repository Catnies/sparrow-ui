package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;

public final class KeyedSignalTestAccess {
    private KeyedSignalTestAccess() {
    }

    public static int handleCount(KeyedSignal<?, ?> signal) {
        return ((AbstractKeyedSignal<?, ?, ?>) signal).handleCount();
    }

    public static <K, T> AbstractSignal<T> partition(KeyedSignal<K, T> signal, K key) {
        return ((AbstractKeyedSignal<K, T, ?>) signal).partition(key);
    }
}
