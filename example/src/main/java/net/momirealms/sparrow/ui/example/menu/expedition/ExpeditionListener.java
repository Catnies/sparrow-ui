package net.momirealms.sparrow.ui.example.menu.expedition;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

final class ExpeditionListener implements Listener {
    private final ExpeditionRoom room;
    private final Plugin plugin;

    ExpeditionListener(@NotNull ExpeditionRoom room, @NotNull Plugin plugin) {
        this.room = room;
        this.plugin = plugin;
    }

    @EventHandler
    private void onQuit(@NotNull PlayerQuitEvent event) {
        this.room.leave(event.getPlayer().getUniqueId());
    }

    @EventHandler
    private void onDisable(@NotNull PluginDisableEvent event) {
        if (event.getPlugin() == this.plugin) {
            this.room.close();
        }
    }
}
