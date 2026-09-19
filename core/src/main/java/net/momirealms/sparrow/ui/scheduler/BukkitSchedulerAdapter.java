package net.momirealms.sparrow.ui.scheduler;

import org.bukkit.plugin.Plugin;
import net.momirealms.sparrow.ui.scheduler.executor.AbstractBukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.ui.util.VersionHelper;


public final class BukkitSchedulerAdapter extends AbstractJavaScheduler {
    private final Plugin plugin;
    private final AbstractBukkitExecutor sync;

    public BukkitSchedulerAdapter(Plugin plugin) {
        super(plugin);
        this.plugin = plugin;
        if (VersionHelper.hasFoliaPatch) {
            this.sync = new FoliaExecutor(plugin);
        } else {
            this.sync = new BukkitExecutor(plugin);
        }
    }

    @Override
    public AbstractBukkitExecutor platform() {
        return this.sync;
    }
}
