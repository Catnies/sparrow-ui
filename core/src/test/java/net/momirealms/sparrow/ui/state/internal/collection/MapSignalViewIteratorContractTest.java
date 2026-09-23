package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.state.MapSignal;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapSignalViewIteratorContractTest {

    @Test
    void removeBeforeNextThrowsIllegalStateExceptionForEveryView() {
        MapSignal<String, Integer> signal = MapSignal.wrap(new LinkedHashMap<>(Map.of("a", 1)));
        List<Iterator<?>> iterators = List.of(
                signal.keySet().iterator(),
                signal.values().iterator(),
                signal.entrySet().iterator()
        );
        for (int i = 0; i < iterators.size(); i++) {
            assertThrows(IllegalStateException.class, iterators.get(i)::remove);
        }

        assertEquals(Map.of("a", 1), Map.copyOf(signal));
    }
}
