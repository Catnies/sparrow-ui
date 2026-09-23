package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.MutableSetSignal;
import net.momirealms.sparrow.ui.state.SetSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetSignalDuplicateAddAllTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();

    @Test
    void addAllCallsBeforeAddOnceForDuplicateInput() {
        List<String> sideTable = new ArrayList<>();
        MutableSetSignal<String> signal = SetSignal.wrap(new LinkedHashSet<>());
        this.hookTokens.add(signal.beforeAdd(element -> {
            sideTable.add(element);
            return "<" + element + ">";
        }));

        assertTrue(signal.addAll(List.of("d", "d")));
        assertEquals(Set.of("<d>"), Set.copyOf(signal));
        assertEquals(List.of("d"), sideTable);
    }
}
