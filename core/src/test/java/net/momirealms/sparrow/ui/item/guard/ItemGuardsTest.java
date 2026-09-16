package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemGuardsTest {

    private ServerMock server;
    private Player player;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.player = this.server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void permissionChecksTheInteractingPlayer() {
        ItemGuard<ItemClick> guard = ItemGuards.permission("sparrow.menu.use");
        ItemClick click = click(this.player);

        assertFalse(guard.test(Item.empty(), click));
        PermissionAttachment attachment = this.player.addAttachment(
                MockBukkit.createMockPlugin(),
                "sparrow.menu.use",
                true
        );

        assertTrue(guard.test(Item.empty(), click));
        attachment.remove();
    }

    @Test
    void gameModeChecksTheInteractingPlayer() {
        ItemGuard<ItemClick> guard = ItemGuards.gameMode(GameMode.CREATIVE);
        ItemClick click = click(this.player);
        this.player.setGameMode(GameMode.SURVIVAL);

        assertFalse(guard.test(Item.empty(), click));
        this.player.setGameMode(GameMode.CREATIVE);

        assertTrue(guard.test(Item.empty(), click));
    }

    @Test
    void guardsCanBeChainedDirectly() {
        List<String> calls = new ArrayList<>();
        ItemGuard<ItemClick> first = (ignoredItem, ignoredClick) -> {
            calls.add("first");
            return true;
        };
        ItemGuard<ItemClick> guard = first
                .and((ignoredItem, ignoredClick) -> {
                    calls.add("second");
                    return false;
                }, (ignoredItem, ignoredClick) -> calls.add("rejected"))
                .and((ignoredItem, ignoredClick) -> {
                    calls.add("later");
                    return true;
                });

        assertFalse(guard.test(Item.empty(), click(this.player)));
        assertEquals(List.of("first", "second", "rejected"), calls);
    }

    @Test
    void itemGuardDoesNotExtendBiPredicate() {
        assertFalse(BiPredicate.class.isAssignableFrom(ItemGuard.class));
    }

    private static ItemClick click(Player player) {
        return new ItemClick(player, ClickType.LEFT, new WindowStub(player), ItemStack.empty(), 0);
    }
}
