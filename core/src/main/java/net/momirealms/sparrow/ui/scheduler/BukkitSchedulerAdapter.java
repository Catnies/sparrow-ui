package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.EntityExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.PaperEntityExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * 汇集 Bukkit/Paper 运行时的异步、区域与实体调度能力.
 */
public final class BukkitSchedulerAdapter extends AbstractJavaScheduler<World> {
    private final RegionExecutor<World> sync;
    private final EntityExecutor entity;

    public BukkitSchedulerAdapter(@NotNull Plugin plugin) {
        super(plugin);
        this.sync = VersionHelper.isFolia() ? new FoliaExecutor(plugin) : new BukkitExecutor(plugin);
        this.entity = new PaperEntityExecutor(plugin);
    }

    @Override
    public RegionExecutor<World> sync() {
        return this.sync;
    }

    /**
     * 返回跟随实体所有权执行任务的调度器.
     *
     * @return 实体调度器
     */
    public @NotNull EntityExecutor entity() {
        return this.entity;
    }
}
