package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.inventory.InventorySequence;
import net.momirealms.sparrow.ui.inventory.SparrowInventory;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaneLinkTest {

    @Test
    void oneByOneAndWholeSequenceAreReportedSeparately() {
        NormalPane pane = Pane.empty(3, 1);
        SparrowInventory chest = new VirtualInventory(9);
        SparrowInventory member = new VirtualInventory(9);
        InventorySequence extra = InventorySequence.of(member);
        pane.linkInventory(chest);
        pane.linkInventory(extra);

        assertEquals(List.of(chest), pane.linkedInventories());
        assertEquals(Set.of(extra), pane.linkedSequences());
        assertEquals(2, pane.participatingSequences().size());
        assertTrue(pane.participatingSequences().contains(extra));
    }

    @Test
    void clearingDeclaredSequencesLeavesOneByOneDeclarationsParticipating() {
        NormalPane pane = Pane.empty(3, 1);
        SparrowInventory chest = new VirtualInventory(9);
        InventorySequence extra = InventorySequence.of(new VirtualInventory(9));
        pane.linkInventory(chest);
        pane.linkInventory(extra);
        for (InventorySequence sequence : pane.linkedSequences()) {
            pane.unlinkInventory(sequence);
        }

        assertTrue(pane.linkedSequences().isEmpty());
        assertEquals(List.of(chest), pane.linkedInventories());
        assertEquals(1, pane.participatingSequences().size());
    }

    @Test
    void ownSequenceCannotBeDeclaredAndThenUnlinked() {
        NormalPane pane = Pane.empty(3, 1);
        SparrowInventory chest = new VirtualInventory(9);
        pane.linkInventory(chest);
        InventorySequence own = pane.participatingSequences().iterator().next();
        pane.linkInventory(own);

        assertFalse(pane.unlinkInventory(own));
        assertEquals(List.of(chest), pane.linkedInventories());
        assertTrue(pane.participatingSequences().contains(own));
    }

    @Test
    void unlinkingOneByOneInventoryKeepsSequenceMembers() {
        NormalPane pane = Pane.empty(3, 1);
        SparrowInventory chest = new VirtualInventory(9);
        SparrowInventory member = new VirtualInventory(9);
        pane.linkInventory(chest);
        pane.linkInventory(InventorySequence.of(member));

        assertFalse(pane.unlinkInventory(member));
        assertTrue(pane.unlinkInventory(chest));
        assertTrue(pane.linkedInventories().isEmpty());
        assertEquals(1, pane.linkedSequences().size());
    }

    @Test
    void participatingSequencesKeepsItsIdentityWhileDeclarationsHoldStill() {
        NormalPane pane = Pane.empty(3, 1);
        pane.linkInventory(new VirtualInventory(9));
        Set<InventorySequence> first = pane.participatingSequences();
        pane.linkInventory(new VirtualInventory(9));

        assertSame(first, pane.participatingSequences());
    }
}
