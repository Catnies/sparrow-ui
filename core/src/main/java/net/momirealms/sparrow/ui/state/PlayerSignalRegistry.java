package net.momirealms.sparrow.ui.state;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

final class PlayerSignalRegistry implements Listener {
    private static final Object LOCK = new Object();
    private static final Set<KeyedSignal<UUID, ?>> SIGNALS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean listenerRegistered;

    private PlayerSignalRegistry() {
    }

    static void track(KeyedSignal<UUID, ?> signal) {
        synchronized (LOCK) {
            if (!listenerRegistered) {
                Bukkit.getPluginManager().registerEvents(new QuitListener(), SparrowUI.getInstance().getPlugin());
                listenerRegistered = true;
            }
            SIGNALS.add(signal);
        }
    }

    static void evict(UUID uuid) {
        List<KeyedSignal<UUID, ?>> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(SIGNALS);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            try {
                snapshot.get(i).remove(uuid);
            } catch (RuntimeException exception) {
                SparrowUI.getInstance().handleException("Failed to evict player signal partition on quit", exception);
            }
        }
    }

    static final class QuitListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        private void handleQuit(PlayerQuitEvent event) {
            evict(event.getPlayer().getUniqueId());
        }
    }
}
