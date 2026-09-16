package net.momirealms.sparrow.ui.inventory.operation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlotOrderTest {

    @Test
    void naturalOrderVisitsSlotsSequentially() {
        SlotOrder order = SlotOrder.natural(4);

        assertEquals(4, order.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i, order.slotAt(i));
        }
    }

    @Test
    void customOrderPreservesGivenSequence() {
        SlotOrder order = SlotOrder.of(2, 0, 3, 1);

        assertEquals(4, order.size());
        assertEquals(2, order.slotAt(0));
        assertEquals(0, order.slotAt(1));
        assertEquals(3, order.slotAt(2));
        assertEquals(1, order.slotAt(3));
    }

    @Test
    void rejectsNonPermutations() {
        assertThrows(IllegalArgumentException.class, () -> SlotOrder.of(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SlotOrder.of(0, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> SlotOrder.of(-1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SlotOrder.natural(-1));
    }

    @Test
    void constructionCopiesTheInputArray() {
        int[] source = {1, 0};
        SlotOrder order = SlotOrder.of(source);
        source[0] = 0;

        assertEquals(1, order.slotAt(0));
    }

    @Test
    void reversedFlipsVisitOrderWithoutMutatingOriginal() {
        SlotOrder order = SlotOrder.of(2, 0, 3, 1);
        SlotOrder reversed = order.reversed();

        assertEquals(1, reversed.slotAt(0));
        assertEquals(3, reversed.slotAt(1));
        assertEquals(0, reversed.slotAt(2));
        assertEquals(2, reversed.slotAt(3));
        assertEquals(2, order.slotAt(0));
    }

}
