package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.window.handle.MenuInput;
import net.momirealms.sparrow.ui.pane.Pane;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ClickInterpreterTest {

    private final WindowLayout layout = WindowLayout.split(Pane.empty(9, 1), Pane.empty(9, 4));
    private final ClickInterpreter interpreter = new ClickInterpreter();

    @Test
    void mapsWindowSlotsAndOutsideWithoutMutatingContainerState() {
        ClickInterpreter.Result.SingleClick pane = this.singleClick(this.click(0, ClickType.LEFT));
        ClickInterpreter.Result.SingleClick lower = this.singleClick(this.click(9, ClickType.SHIFT_LEFT));
        ClickInterpreter.Result.SingleClick outside = this.singleClick(
                this.click(-999, ClickType.WINDOW_BORDER_RIGHT)
        );

        assertEquals(ClickType.LEFT, pane.clickType());
        assertEquals(0, pane.rawSlot());
        assertEquals(ClickType.SHIFT_LEFT, lower.clickType());
        assertEquals(9, lower.rawSlot());
        assertEquals(ClickType.WINDOW_BORDER_RIGHT, outside.clickType());
        assertEquals(InventoryView.OUTSIDE, outside.rawSlot());
    }

    @Test
    void mapsSwapKeysAndExplainsRejectedSingleClicks() {
        ClickInterpreter.Result.SingleClick number = this.singleClick(this.click(2, ClickType.NUMBER_KEY, 7));
        ClickInterpreter.Result.SingleClick offHand = this.singleClick(this.click(2, ClickType.SWAP_OFFHAND));
        ClickInterpreter.Result invalidButton = this.interpret(this.click(2, ClickType.CREATIVE));
        ClickInterpreter.Result invalidSlot = this.interpret(this.click(99, ClickType.LEFT));
        ClickInterpreter.Result invalidNegativeSlot = this.interpret(this.click(-2, ClickType.LEFT));

        assertEquals(ClickType.NUMBER_KEY, number.clickType());
        assertEquals(7, number.hotbarButton());
        assertEquals(ClickType.SWAP_OFFHAND, offHand.clickType());
        assertEquals(-1, offHand.hotbarButton());
        assertEquals(
                ClickInterpreter.Rejection.INVALID_BUTTON,

                assertInstanceOf(ClickInterpreter.Result.Rejected.class, invalidButton).reason()
        );

        assertEquals(
                ClickInterpreter.Rejection.INVALID_SLOT,

                assertInstanceOf(ClickInterpreter.Result.Rejected.class, invalidSlot).reason()
        );

        assertEquals(
                ClickInterpreter.Rejection.INVALID_SLOT,

                assertInstanceOf(ClickInterpreter.Result.Rejected.class, invalidNegativeSlot).reason()
        );
    }

    @Test
    void rejectsNumberKeysOutsideHotbarRange() {
        assertRejected(
                ClickInterpreter.Rejection.INVALID_BUTTON,
                this.interpret(this.click(2, ClickType.NUMBER_KEY, -1))
        );

        assertRejected(
                ClickInterpreter.Rejection.INVALID_BUTTON,
                this.interpret(this.click(2, ClickType.NUMBER_KEY, 9))
        );
    }

    @Test
    void emitsOneOrderedIntentAfterAValidMultiPacketDrag() {
        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(-999, ClickType.LEFT, MenuInput.Common.DragPhase.START), 4)
        );

        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(0, ClickType.LEFT, MenuInput.Common.DragPhase.ADD), 4)
        );

        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(3, ClickType.LEFT, MenuInput.Common.DragPhase.ADD), 4)
        );

        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(0, ClickType.LEFT, MenuInput.Common.DragPhase.ADD), 4)
        );
        ClickInterpreter.Result.Drag drag = assertInstanceOf(
                ClickInterpreter.Result.Drag.class,
                this.interpret(this.drag(-999, ClickType.LEFT, MenuInput.Common.DragPhase.END), 4)
        );

        assertEquals(ClickType.LEFT, drag.clickType());
        assertEquals(List.of(0, 3), drag.slots());
    }

    @Test
    void singleSlotRightDragFallsBackToSingleClick() {
        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(-999, ClickType.RIGHT, MenuInput.Common.DragPhase.START), 4)
        );

        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(2, ClickType.RIGHT, MenuInput.Common.DragPhase.ADD), 4)
        );
        ClickInterpreter.Result.SingleClick click = assertInstanceOf(
                ClickInterpreter.Result.SingleClick.class,
                this.interpret(this.drag(-999, ClickType.RIGHT, MenuInput.Common.DragPhase.END), 4)
        );

        assertEquals(ClickType.RIGHT, click.clickType());
        assertEquals(-1, click.hotbarButton());
        assertEquals(2, click.rawSlot());
    }

    @Test
    void rejectsIllegalOrCrossGenerationDragSequencesAndResetsThem() {
        assertRejected(
                ClickInterpreter.Rejection.INVALID_DRAG_SEQUENCE,
                this.interpret(this.drag(0, ClickType.LEFT, MenuInput.Common.DragPhase.ADD), 2)
        );

        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(-999, ClickType.RIGHT, MenuInput.Common.DragPhase.START), 2)
        );

        assertRejected(
                ClickInterpreter.Rejection.INVALID_DRAG_SEQUENCE,
                this.interpret(this.drag(0, ClickType.RIGHT, MenuInput.Common.DragPhase.ADD), 3)
        );

        assertRejected(
                ClickInterpreter.Rejection.INVALID_DRAG_SEQUENCE,
                this.interpret(this.drag(-999, ClickType.RIGHT, MenuInput.Common.DragPhase.END), 3)
        );
    }

    @Test
    void ordinaryClickTerminatesAnUnfinishedDrag() {
        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                this.interpret(this.drag(-999, ClickType.LEFT, MenuInput.Common.DragPhase.START), 7)
        );

        assertInstanceOf(
                ClickInterpreter.Result.SingleClick.class,
                this.interpret(this.click(0, ClickType.LEFT), 7)
        );

        assertRejected(
                ClickInterpreter.Rejection.INVALID_DRAG_SEQUENCE,
                this.interpret(this.drag(-999, ClickType.LEFT, MenuInput.Common.DragPhase.END), 7)
        );
    }

    @Test
    void rejectsUnsupportedDragClickTypeWithoutStartingAGesture() {
        assertRejected(
                ClickInterpreter.Rejection.INVALID_BUTTON,
                this.interpret(this.drag(-999, ClickType.UNKNOWN, MenuInput.Common.DragPhase.START))
        );

        assertRejected(
                ClickInterpreter.Rejection.INVALID_DRAG_SEQUENCE,
                this.interpret(this.drag(0, ClickType.LEFT, MenuInput.Common.DragPhase.ADD))
        );
    }

    @Test
    void rejectsForgedClicksAndDragSlotsInVirtualTail() {
        WindowLayout virtualLayout = WindowLayout.of(
                WindowLayout.Region.upper(Pane.empty(2, 1)),
                WindowLayout.Region.lower(Pane.empty(9, 4)),
                WindowLayout.Region.virtual(Pane.empty(4, 1))
        );
        ClickInterpreter virtualInterpreter = new ClickInterpreter();

        assertRejected(
                ClickInterpreter.Rejection.INVALID_SLOT,
                virtualInterpreter.interpret(this.click(38, ClickType.LEFT), virtualLayout, 1)
        );

        assertEquals(
                ClickInterpreter.Result.Pending.INSTANCE,
                virtualInterpreter.interpret(
                        this.drag(-999, ClickType.LEFT, MenuInput.Common.DragPhase.START),
                        virtualLayout,
                        1
                )
        );

        assertRejected(
                ClickInterpreter.Rejection.INVALID_SLOT,
                virtualInterpreter.interpret(
                        this.drag(38, ClickType.LEFT, MenuInput.Common.DragPhase.ADD),
                        virtualLayout,
                        1
                )
        );
    }

    private ClickInterpreter.Result.SingleClick singleClick(MenuInput.Common.Click click) {
        return assertInstanceOf(ClickInterpreter.Result.SingleClick.class, this.interpret(click));
    }

    private ClickInterpreter.Result interpret(MenuInput.Common.Interaction interaction) {
        return this.interpret(interaction, 1);
    }

    private ClickInterpreter.Result interpret(MenuInput.Common.Interaction interaction, long generation) {
        return this.interpreter.interpret(interaction, this.layout, generation);
    }

    private MenuInput.Common.Click click(int slot, ClickType clickType) {
        return this.click(slot, clickType, -1);
    }

    private MenuInput.Common.Click click(int slot, ClickType clickType, int hotbarButton) {
        return new MenuInput.Common.Click(1, 0, slot, clickType, hotbarButton);
    }

    private MenuInput.Common.DragStep drag(
            int slot,
            ClickType clickType,
            MenuInput.Common.DragPhase phase
    ) {
        return new MenuInput.Common.DragStep(1, 0, slot, clickType, phase);
    }

    private static void assertRejected(
            ClickInterpreter.Rejection expected,
            ClickInterpreter.Result result
    ) {
        assertEquals(expected, assertInstanceOf(ClickInterpreter.Result.Rejected.class, result).reason());
    }
}
