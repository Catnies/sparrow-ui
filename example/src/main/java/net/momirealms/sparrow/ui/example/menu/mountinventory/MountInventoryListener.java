package net.momirealms.sparrow.ui.example.menu.mountinventory;

import org.bukkit.Material;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 手持绿宝石右键坐骑时打开它的映射背包.
 * <p>马、驴、骡、羊驼和骆驼都属于 {@link AbstractHorse}, 各有自己的背包容器.
 */
public final class MountInventoryListener implements Listener {

    @EventHandler
    private void onRightClick(@NotNull PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof AbstractHorse mount)) return;

        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        ItemStack held = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (held.getType() != Material.EMERALD) return;

        event.setCancelled(true);

        // 双手同时拿着绿宝石时只处理主手事件, 避免同一次右键打开两次菜单
        if (hand == EquipmentSlot.OFF_HAND && player.getInventory().getItemInMainHand().getType() == Material.EMERALD) return;

        MountInventoryMenu.open(player, mount);
    }
}
