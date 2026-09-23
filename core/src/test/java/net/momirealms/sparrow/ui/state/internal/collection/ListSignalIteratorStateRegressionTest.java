package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MutableListSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListSignalIteratorStateRegressionTest {

    private final java.util.List<Subscription> hookTokens = new java.util.ArrayList<>();

    @Test
    void setBeforeTraversalRejectsNullWithoutCallingHooks() {
        List<String> removed = new ArrayList<>();
        MutableListSignal<String> signal = ListSignal.wrap(new ArrayList<>(List.of("a")));
        this.hookTokens.add(signal.afterRemove(removed::add));
        ListIterator<String> iterator = signal.listIterator();

        assertThrows(IllegalStateException.class, () -> iterator.set(null));
        assertEquals(List.of(), removed);
        assertEquals(List.of("a"), List.copyOf(signal));
    }

    @Test
    void setAfterRemoveDoesNotCallAfterRemoveAgain() {
        List<String> removed = new ArrayList<>();
        MutableListSignal<String> signal = ListSignal.wrap(new ArrayList<>(List.of("a")));
        this.hookTokens.add(signal.afterRemove(removed::add));
        ListIterator<String> iterator = signal.listIterator();

        assertEquals("a", iterator.next());
        iterator.remove();

        assertThrows(IllegalStateException.class, () -> iterator.set("z"));
        assertEquals(List.of("a"), removed);
        assertEquals(List.of(), List.copyOf(signal));
    }
}
