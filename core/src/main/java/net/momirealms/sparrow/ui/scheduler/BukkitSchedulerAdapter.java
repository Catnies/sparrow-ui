package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public final class BukkitSchedulerAdapter extends AbstractJavaScheduler<World> {
    private final RegionExecutor<World> sync;

    public BukkitSchedulerAdapter(Plugin plugin) {
        super(plugin);
        this.sync = VersionHelper.isFolia() ? new FoliaExecutor(plugin) : new BukkitExecutor(plugin);
    }

    @Override
    public RegionExecutor<World> sync() {
        return this.sync;
    }
}
