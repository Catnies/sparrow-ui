package net.momirealms.sparrow.ui.pane;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaneSizeTest {

    @Test
    void rectangularCoordinatesAndIndicesRoundTrip() {
        PaneSize size = new PaneSize(7, 4);

        assertEquals(28, size.area());
        for (int slot = 0; slot < size.area(); slot++) {
            PanePosition position = size.positionOf(slot);

            assertEquals(slot, size.indexOf(position));
        }

        assertEquals(27, size.indexOf(6, 3));
    }

    @Test
    void acceptsZeroDimensionsButRejectsNegativeDimensionsAndOverflow() {
        assertEquals(0, new PaneSize(0, 1).area());
        assertEquals(0, new PaneSize(1, 0).area());
        assertEquals(0, new PaneSize(0, 0).area());
        assertThrows(IllegalArgumentException.class, () -> new PaneSize(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PaneSize(1, -1));
        assertThrows(ArithmeticException.class, () -> new PaneSize(Integer.MAX_VALUE, 2));
    }

    @Test
    void rejectsPositionsAndSlotsOutsideSize() {
        PaneSize size = new PaneSize(3, 2);

        assertThrows(IndexOutOfBoundsException.class, () -> size.indexOf(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> size.indexOf(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> size.indexOf(0, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> size.positionOf(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> size.positionOf(6));
        assertThrows(IndexOutOfBoundsException.class, () -> size.checkSlot(6));
    }
}
