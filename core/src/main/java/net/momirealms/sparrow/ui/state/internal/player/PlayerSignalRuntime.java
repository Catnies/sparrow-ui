package net.momirealms.sparrow.ui.state.internal.player;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.internal.AbstractSignal;
import net.momirealms.sparrow.ui.state.internal.collection.ReadOnlyListSignal;
import net.momirealms.sparrow.ui.state.internal.time.Delayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

@ApiStatus.Internal
public final class PlayerSignalRuntime extends AbstractSignal<List<Player>> implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final Delayer delayer;
    private final Object lock = new Object();
    private final Set<KeyedSignal<UUID, ?>> signals = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private final ReadOnlyListSignal<Player> onlinePlayers = new ReadOnlyListSignal<>(this);
    @Nullable private List<Player> snapshot;
    @Nullable private Delayer.Handle pending;
    private volatile long version;
    private boolean closed;

    public PlayerSignalRuntime(Plugin plugin) {
        this(plugin, Delayer.ticks());
    }

    PlayerSignalRuntime(Plugin plugin, Delayer delayer) {
        this.plugin = plugin;
        this.delayer = delayer;
    }

    public void initialize() {
        synchronized (this.lock) {
            Bukkit.getPluginManager().registerEvents(this, this.plugin);
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.players.put(player.getUniqueId(), player);
            }
        }
    }

    @NotNull
    public ListSignal<Player> onlinePlayers() {
        return this.onlinePlayers;
    }

    @Override
    public long version() {
        return this.version;
    }

    @Override
    @NotNull
    public List<Player> get() {
        synchronized (this.lock) {
            if (this.snapshot == null) {
                this.snapshot = List.copyOf(this.players.values());
            }
            return this.snapshot;
        }
    }

    public void track(KeyedSignal<UUID, ?> signal) {
        synchronized (this.lock) {
            if (this.closed) return;
            this.signals.add(signal);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        synchronized (this.lock) {
            if (this.closed) return;
            if (this.players.put(player.getUniqueId(), player) != player) {
                this.changed();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void handleQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        List<KeyedSignal<UUID, ?>> snapshot;
        synchronized (this.lock) {
            if (this.closed) return;
            // 同 UUID 已由新连接替换时, 旧连接的退出事件保留新玩家
            if (this.players.get(uuid) == player) {
                this.players.remove(uuid);
                this.changed();
            }
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

    // 调用方持有 lock. 立即更新版本供派生值拉取, 下一 tick 合并发送失效
    private void changed() {
        this.snapshot = null;
        this.version++;
        if (this.pending == null) {
            this.pending = this.delayer.schedule(this::publish, 1L);
        }
    }

    private void publish() {
        synchronized (this.lock) {
            if (this.closed) return;
            this.pending = null;
        }
        // 回调在锁外执行, 派发期间的新变化可以排入下一 tick
        this.notifyDirty();
    }

    @Override
    public void close() {
        synchronized (this.lock) {
            if (this.closed) return;
            this.closed = true;
            if (this.pending != null) {
                this.pending.cancel();
                this.pending = null;
            }
            this.players.clear();
            this.snapshot = List.of();
            this.version++;
            this.signals.clear();
        }
        HandlerList.unregisterAll(this);
        this.onlinePlayers.retire();
        this.retire();
    }
}
