package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureTest {

    @Test
    void parsesUnicodeAndQuotedIdentifiersByLogicalSlot() {
        Structure structure = Structure.of("😀'`wide id`", "ABC");

        assertEquals(new PaneSize(3, 2), structure.size());
        assertEquals("😀", structure.identifierAt(0));
        assertEquals("'", structure.identifierAt(1));
        assertEquals("wide id", structure.identifierAt(2));
        assertEquals("C", structure.identifierAt(5));
    }

    @Test
    void parsesAdjacentQuotedIdentifiersAndSupportedEscapes() {
        Structure adjacent = Structure.of("`ab``cd`");
        Structure escaped = Structure.of("`a\\`b``a\\\\b`");

        assertEquals(new PaneSize(2, 1), adjacent.size());
        assertEquals("ab", adjacent.identifierAt(0));
        assertEquals("cd", adjacent.identifierAt(1));
        assertEquals("a`b", escaped.identifierAt(0));
        assertEquals("a\\b", escaped.identifierAt(1));
    }

    @Test
    void rejectsMalformedRowsWithSourceLocation() {
        assertThrows(IllegalArgumentException.class, Structure::of);
        assertThrows(IllegalArgumentException.class, () -> Structure.of(""));
        assertThrows(IllegalArgumentException.class, () -> Structure.of("``"));
        assertThrows(IllegalArgumentException.class, () -> Structure.of("`a"));
        assertThrows(IllegalArgumentException.class, () -> Structure.of("`a\\nb`"));
        assertThrows(IllegalArgumentException.class, () -> Structure.of("A\t"));
        assertThrows(IllegalArgumentException.class, () -> Structure.of("A", "AA"));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Structure.of("A", "`broken")
        );

        assertTrue(failure.getMessage().contains("row 2"));
        assertTrue(failure.getMessage().contains("source column 1"));
    }

    @Test
    void pureSizeAndFlatLayoutsSupportRectangles() {
        Structure empty = Structure.of(new PaneSize(5, 2));
        Structure flat = Structure.of(new PaneSize(2, 2), "ABCD");

        assertNull(empty.identifierAt(0));
        assertNull(empty.identifierAt(9));
        assertEquals("D", flat.identifierAt(3));
        assertThrows(
                IllegalArgumentException.class,
                () -> Structure.of(new PaneSize(2, 2), "ABC")
        );
    }

    @Test
    void precompiledGroupsSupportBuiltInAndExternalPatterns() {
        Structure structure = Structure.of("HVH", "VHV");

        assertArrayEquals(new int[]{0, 2, 4}, structure.slots("H").toArray());
        assertSame(structure.slots("H"), structure.slots(SlotPatterns.ROW_MAJOR, "H"));
        assertArrayEquals(
                new int[]{0, 4, 2},
                structure.slots(SlotPatterns.COLUMN_MAJOR, "H").toArray()
        );

        assertArrayEquals(
                new int[]{3, 1, 5},
                structure.slots(SlotPatterns.COLUMN_MAJOR, "V").toArray()
        );

        assertArrayEquals(
                new int[]{0, 3, 1, 4, 2, 5},
                structure.slots(SlotPatterns.COLUMN_MAJOR, "H", "V").toArray()
        );

        assertArrayEquals(
                new int[]{0, 2, 4},
                structure.slots(SlotPatterns.CHECKERBOARD_EVEN, "H", "V").toArray()
        );

        assertArrayEquals(
                new int[]{5, 0},
                structure.slots((slots, output) -> {
                    output.accept(slots.slotAt(5));
                    output.accept(slots.slotAt(0));
                }, "H", "V").toArray()
        );
    }

    @Test
    void structureIsIndependentFromInputAndReusableAcrossBuilders() {
        String[] rows = {"A"};
        Structure structure = Structure.of(rows);
        rows[0] = "B";
        Element first = element();
        Element second = element();
        NormalPane firstPane = Pane.builder(structure).addIngredient("A", first).build();
        NormalPane secondPane = Pane.builder(structure).addIngredient("A", second).build();

        assertSame(first, firstPane.element(0));
        assertSame(second, secondPane.element(0));
    }

    @Test
    void supplierReceivesWholeGroupAndOccurrenceResetsPerBuild() {
        List<String> calls = new ArrayList<>();
        Structure structure = Structure.of("ABA");
        Pane.Builder<NormalPane, ?> builder = Pane.builder(structure)
                .addIngredient("A", (slots, occurrence) -> {
                    calls.add(slots.slotAt(occurrence) + ":" + occurrence + ":" + slots.length());
                    return element();
                });
        NormalPane first = builder.build();
        NormalPane second = builder.build();

        assertEquals(List.of("0:0:2", "2:1:2", "0:0:2", "2:1:2"), calls);
        assertSame(Element.Empty.INSTANCE, first.element(1));
        assertNotSame(first.element(0), first.element(2));
        assertNotSame(first.element(0), second.element(0));
    }

    @Test
    void nestedPaneIngredientPreservesTwoDimensionalOffsets() {
        NormalPane child = Pane.empty(2, 2);
        NormalPane parent = Pane.builder(Structure.of("XX", "XX"))
                .addIngredient("X", child)
                .build();
        for (int slot = 0; slot < 4; slot++) {
            Element.PaneLink link = assertInstanceOf(Element.PaneLink.class, parent.element(slot));

            assertSame(child, link.pane());
            assertEquals(slot, link.slot());
        }
    }

    @Test
    void supplierFailureContainsIdentifierCoordinatesAndCause() {
        IllegalArgumentException failure = new IllegalArgumentException("broken supplier");
        Pane.Builder<NormalPane, ?> throwing = Pane.builder(Structure.of("`wide`A"))
                .addIngredient("A", (ignoredSize, ignoredOccurrence) -> {
                    throw failure;
                });
        IllegalStateException thrown = assertThrows(IllegalStateException.class, throwing::build);

        assertSame(failure, thrown.getCause());
        assertTrue(thrown.getMessage().contains("identifier 'A'"));
        assertTrue(thrown.getMessage().contains("row 1"));
        assertTrue(thrown.getMessage().contains("source column 7"));
        assertTrue(thrown.getMessage().contains("logical column 2"));
        assertTrue(thrown.getMessage().contains("slot 1"));
        Pane.Builder<NormalPane, ?> returningNull = Pane.builder(Structure.of("A"))
                .addIngredient("A", (ignoredSize, ignoredOccurrence) -> null);
        IllegalStateException nullFailure = assertThrows(IllegalStateException.class, returningNull::build);

        assertInstanceOf(NullPointerException.class, nullFailure.getCause());
        AssertionError fatal = new AssertionError("fatal supplier failure");
        Pane.Builder<NormalPane, ?> fatalBuilder = Pane.builder(Structure.of("A"))
                .addIngredient("A", (ignoredSize, ignoredOccurrence) -> {
                    throw fatal;
                });

        assertSame(fatal, assertThrows(AssertionError.class, fatalBuilder::build));
    }

    @Test
    void duplicateBindingUsesLastValueAndUnknownIdentifierIsRejectedAtSeam() {
        Element first = element();
        Element second = element();
        Structure structure = Structure.of("A unused");
        NormalPane pane = Pane.builder(structure)
                .addIngredient("A", first)
                .addIngredient("A", second)
                .build();

        assertSame(second, pane.element(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Pane.builder(structure).addIngredient("missing", first)
        );

        assertThrows(IllegalArgumentException.class, () -> structure.slots(""));
    }

    private static Element element() {
        return new Element.Item(new TestItem());
    }

    private static final class TestItem implements Item {
        @Override
        public @NonNull ItemProvider getItemProvider() {
            return ItemProvider.EMPTY;
        }
    }
}
