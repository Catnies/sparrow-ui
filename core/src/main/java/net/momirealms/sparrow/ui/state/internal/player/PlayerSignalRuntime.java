package net.momirealms.sparrow.ui.state.internal.player;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@ApiStatus.Internal
public final class PlayerSignalRuntime implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Object lock = new Object();
    private final Set<KeyedSignal<UUID, ?>> signals = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean closed;

    public PlayerSignalRuntime(Plugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void track(KeyedSignal<UUID, ?> signal) {
        synchronized (this.lock) {
            if (this.closed) return;
            this.signals.add(signal);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        List<KeyedSignal<UUID, ?>> snapshot;
        synchronized (this.lock) {
            if (this.closed) return;
            snapshot = new ArrayList<>(this.signals);
        }
        // UUID 分区在退出事件中驱逐, 用户回调在运行时锁外执行
        for (int index = 0; index < snapshot.size(); index++) {
            try {
                snapshot.get(index).remove(uuid);
            } catch (RuntimeException exception) {
                SparrowUI.getInstance().handleException("Failed to evict player signal partition on quit", exception);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            if (this.closed) return;
            this.closed = true;
            this.signals.clear();
        }
        HandlerList.unregisterAll(this);
    }
}
