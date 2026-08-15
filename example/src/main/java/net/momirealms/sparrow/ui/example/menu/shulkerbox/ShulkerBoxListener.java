package net.momirealms.sparrow.ui.example.menu.shulkerbox;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * 右键主手或副手潜影盒时打开交互菜单。
 */
public final class ShulkerBoxListener implements Listener {

    @EventHandler
    private void onRightClick(@NotNull PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        EquipmentSlot hand = event.getHand();
        Inventory playerInventory = ((CraftPlayer) player).getHandle().getInventory();
        int sourceSlot = hand == EquipmentSlot.HAND ? playerInventory.getSelectedSlot() : Inventory.SLOT_OFFHAND;
        ItemStack shulker = playerInventory.getItem(sourceSlot);
        if (!shulker.is(ItemTags.SHULKER_BOXES)) return;

        event.setCancelled(true);

        // 双手同时持有潜影盒时只处理主手事件，避免同一次右键打开两次。
        if (hand == EquipmentSlot.OFF_HAND && playerInventory.getSelectedItem().is(ItemTags.SHULKER_BOXES)) return;

        ShulkerBoxMenu.open(player, sourceSlot);
    }
}
