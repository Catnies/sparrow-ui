package net.momirealms.sparrow.ui.pane;

import org.junit.jupiter.api.Test;
import java.util.function.IntConsumer;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlotSequenceTest {

    @Test
    void standardSelectorsUsePrimitiveStableOrder() {
        PaneSize size = new PaneSize(4, 3);

        assertArrayEquals(new int[]{4, 5, 6, 7}, SlotSequence.row(size, 1).toArray());
        assertArrayEquals(new int[]{2, 6, 10}, SlotSequence.column(size, 2).toArray());
        assertArrayEquals(new int[]{3, 4, 5, 6}, SlotSequence.range(size, 3, 7).toArray());
        assertArrayEquals(new int[]{5, 6, 9, 10}, SlotSequence.rectangle(size, 1, 1, 2, 2).toArray());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 7, 8, 9, 10, 11}, SlotSequence.borders(size).toArray());
    }

    @Test
    void zeroAreaSelectorsRemainEmpty() {
        PaneSize zeroWidth = new PaneSize(0, 3);
        PaneSize zeroHeight = new PaneSize(4, 0);

        assertArrayEquals(new int[0], SlotSequence.all(zeroWidth).toArray());
        assertArrayEquals(new int[0], SlotSequence.borders(zeroWidth).toArray());
        assertArrayEquals(new int[0], SlotSequence.row(zeroWidth, 1).toArray());
        assertArrayEquals(new int[0], SlotSequence.all(zeroHeight).toArray());
        assertArrayEquals(new int[0], SlotSequence.borders(zeroHeight).toArray());
        assertArrayEquals(new int[0], SlotSequence.column(zeroHeight, 2).toArray());
    }

    @Test
    void builtInPatternsSupportRowsColumnsAndCheckerboards() {
        PaneSize size = new PaneSize(3, 2);
        SlotSequence custom = SlotSequence.of(size, 2, 3, 1, 5);

        assertArrayEquals(new int[]{1, 2, 3, 5}, custom.transform(SlotPatterns.ROW_MAJOR).toArray());
        assertArrayEquals(new int[]{3, 1, 2, 5}, custom.transform(SlotPatterns.COLUMN_MAJOR).toArray());
        assertArrayEquals(new int[]{2}, custom.transform(SlotPatterns.CHECKERBOARD_EVEN).toArray());
        assertArrayEquals(new int[]{3, 1, 5}, custom.transform(SlotPatterns.checkerboard(1)).toArray());
        assertThrows(IllegalArgumentException.class, () -> SlotPatterns.checkerboard(2));
        assertEquals(0, custom.minX());
        assertEquals(0, custom.minY());
    }

    @Test
    void externalPatternsCanChooseAnyCandidateSubsetAndOrder() {
        SlotSequence candidates = SlotSequence.all(new PaneSize(3, 2));
        SlotPattern reverseCorners = (slots, output) -> {
            output.accept(slots.slotAt(5));
            output.accept(slots.slotAt(0));
        };

        assertArrayEquals(new int[]{5, 0}, candidates.transform(reverseCorners).toArray());
        assertSame(candidates, candidates.transform((slots, output) -> slots.forEach(output)));
    }

    @Test
    void patternOutputRejectsInvalidSlotsAndExpiresAfterTransform() {
        SlotSequence candidates = SlotSequence.of(new PaneSize(2, 2), 0, 2);
        IntConsumer[] retained = new IntConsumer[1];

        assertThrows(IllegalArgumentException.class, () -> candidates.transform((ignoredInput, output) -> {
            output.accept(0);
            output.accept(0);
        }));

        assertThrows(
                IllegalArgumentException.class,
                () -> candidates.transform((ignoredInput, output) -> output.accept(1))
        );

        assertThrows(
                IndexOutOfBoundsException.class,
                () -> candidates.transform((ignoredInput, output) -> output.accept(4))
        );
        candidates.transform((ignoredInput, output) -> retained[0] = output);

        assertThrows(IllegalStateException.class, () -> retained[0].accept(0));
    }

    @Test
    void inputAndOutputArraysCannotMutateSequence() {
        PaneSize size = new PaneSize(3, 1);
        int[] input = {0, 2};
        SlotSequence sequence = SlotSequence.of(size, input);
        input[0] = 1;
        int[] output = sequence.toArray();
        output[0] = 1;

        assertArrayEquals(new int[]{0, 2}, sequence.toArray());
        assertNotSame(output, sequence.toArray());
    }

    @Test
    void rejectsOutOfRangeDuplicatesAndIncompatibleConcatenation() {
        PaneSize size = new PaneSize(2, 2);

        assertThrows(IllegalArgumentException.class, () -> SlotSequence.of(size, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> SlotSequence.of(size, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> SlotSequence.concat(
                        SlotSequence.of(size, 0),
                        SlotSequence.of(size, 0)
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> SlotSequence.concat(
                        SlotSequence.of(size, 0),
                        SlotSequence.of(new PaneSize(1, 1), 0)
                )
        );
    }
}
