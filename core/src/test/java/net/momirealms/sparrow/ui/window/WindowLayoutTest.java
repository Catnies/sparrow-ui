package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WindowLayoutTest {

    @Test
    void splitMapsEveryProtocolSlotAndExposesBothRoots() {
        Pane upper = Pane.empty(9, 2);
        Pane lower = Pane.empty(9, 4);
        WindowLayout layout = WindowLayout.split(upper, lower);

        assertEquals(18, layout.upperSize());
        assertEquals(54, layout.size());
        assertEquals(54, layout.protocolSize());
        assertSame(upper, layout.paneAt(17).pane());
        assertEquals(17, layout.paneAt(17).slot());
        assertSame(lower, layout.paneAt(18).pane());
        assertEquals(0, layout.paneAt(18).slot());
        assertSame(lower, layout.paneAt(layout.windowSlotAtHotbar(0)).pane());
        assertEquals(27, layout.paneAt(layout.windowSlotAtHotbar(0)).slot());
        assertEquals(List.of(upper, lower), layout.panes());
    }

    @Test
    void mergedMapsEveryProtocolSlotToItsSingleRoot() {
        Pane mergedPane = Pane.empty(9, 6);
        WindowLayout layout = WindowLayout.merged(mergedPane);

        assertEquals(18, layout.upperSize());
        assertEquals(54, layout.size());
        assertEquals(54, layout.protocolSize());
        assertSame(mergedPane, layout.paneAt(53).pane());
        assertEquals(53, layout.paneAt(53).slot());
        assertEquals(List.of(mergedPane), layout.panes());
    }

    @Test
    void layoutOnlyRejectsStructurallyInvalidLowerAndMergedRegions() {
        WindowLayout anvilSized = WindowLayout.split(Pane.empty(3, 1), Pane.empty(9, 4));

        assertEquals(3, anvilSized.upperSize());
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowLayout.split(Pane.empty(9, 1), Pane.empty(9, 3))
        );

        assertThrows(IllegalArgumentException.class, () -> WindowLayout.merged(Pane.empty(9, 4)));
    }

    @Test
    void orderedRegionsPreservePaneBoundariesAndLowerMapping() {
        Pane input = Pane.empty(1, 2);
        Pane result = Pane.empty(1, 1);
        Pane lower = Pane.empty(9, 4);
        WindowLayout layout = WindowLayout.of(
                WindowLayout.Region.upper(input),
                WindowLayout.Region.upper(result),
                WindowLayout.Region.lower(lower)
        );

        assertEquals(3, layout.upperSize());
        assertEquals(39, layout.size());
        assertEquals(1, layout.paneAt(1).slot());
        assertSame(result, layout.paneAt(2).pane());
        assertEquals(0, layout.paneAt(2).slot());
        assertSame(lower, layout.paneAt(3).pane());
        assertEquals(0, layout.paneAt(3).slot());
    }

    @Test
    void crafterPlacesItsResultAfterThePlayerInventory() {
        Pane crafting = Pane.empty(3, 3);
        Pane lower = Pane.empty(9, 4);
        Pane result = Pane.empty(1, 1);
        WindowLayout layout = WindowLayout.of(
                WindowLayout.Region.upper(crafting),
                WindowLayout.Region.lower(lower),
                WindowLayout.Region.upper(result)
        );

        assertEquals(10, layout.upperSize());
        assertEquals(46, layout.size());
        assertEquals(36, layout.windowSlotAtHotbar(0));
        assertEquals(8, layout.paneAt(8).slot());
        assertSame(lower, layout.paneAt(9).pane());
        assertEquals(0, layout.paneAt(9).slot());
        assertEquals(35, layout.paneAt(44).slot());
        assertSame(result, layout.paneAt(45).pane());
        assertEquals(0, layout.paneAt(45).slot());
    }

    @Test
    void orderedCompilerRejectsAmbiguousRegions() {
        Pane upper = Pane.empty(1, 1);
        Pane lower = Pane.empty(9, 4);

        assertThrows(IllegalArgumentException.class, WindowLayout::of);
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowLayout.of(WindowLayout.Region.upper(upper))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> WindowLayout.of(
                        WindowLayout.Region.upper(upper),
                        WindowLayout.Region.lower(lower),
                        WindowLayout.Region.lower(Pane.empty(9, 4))
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> WindowLayout.Region.lower(Pane.empty(6, 6))
        );
    }

    @Test
    void trailingVirtualPaneExtendsLogicalSlotsWithoutExtendingProtocolSlots() {
        Pane upper = Pane.empty(2, 1);
        Pane lower = Pane.empty(9, 4);
        Pane buttons = Pane.empty(4, 2);
        WindowLayout layout = WindowLayout.of(
                WindowLayout.Region.upper(upper),
                WindowLayout.Region.lower(lower),
                WindowLayout.Region.virtual(buttons)
        );

        assertEquals(38, layout.protocolSize());
        assertEquals(46, layout.size());
        assertSame(buttons, layout.paneAt(38).pane());
        assertEquals(0, layout.paneAt(38).slot());
        assertEquals(List.of(upper, lower, buttons), layout.panes());
    }

    @Test
    void zeroSizedVirtualPaneRemainsDiscoverableAndVirtualRegionsMustTrail() {
        Pane upper = Pane.empty(2, 1);
        Pane lower = Pane.empty(9, 4);
        Pane buttons = Pane.empty(4, 0);
        WindowLayout layout = WindowLayout.of(
                WindowLayout.Region.upper(upper),
                WindowLayout.Region.lower(lower),
                WindowLayout.Region.virtual(buttons)
        );

        assertEquals(38, layout.protocolSize());
        assertEquals(38, layout.size());
        assertEquals(List.of(upper, lower, buttons), layout.panes());
        assertThrows(
                IllegalArgumentException.class,
                () -> WindowLayout.of(
                        WindowLayout.Region.upper(upper),
                        WindowLayout.Region.virtual(Pane.empty(4, 1)),
                        WindowLayout.Region.lower(lower)
                )
        );
    }

    @Test
    void hotbarLookupValidatesItsOwnDomain() {
        WindowLayout layout = WindowLayout.split(Pane.empty(9, 1), Pane.empty(9, 4));

        assertThrows(IndexOutOfBoundsException.class, () -> layout.windowSlotAtHotbar(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.windowSlotAtHotbar(9));
    }
}
