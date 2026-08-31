package net.momirealms.sparrow.ui.scheduler;

import net.momirealms.sparrow.ui.scheduler.executor.BukkitEntityExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.BukkitExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.EntityExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaEntityExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.FoliaExecutor;
import net.momirealms.sparrow.ui.scheduler.executor.RegionExecutor;
import net.momirealms.sparrow.ui.util.VersionHelper;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * 在 Bukkit 主线程、Paper 实体调度器和 Folia 区域调度器之间选择运行时实现.
 */
public final class BukkitSchedulerAdapter extends AbstractJavaScheduler<World> {
    private final RegionExecutor<World> sync;
    private final EntityExecutor entity;

    /**
     * 为指定 Bukkit 插件创建平台调度器.
     *
     * @param plugin 任务所属插件
     */
    public BukkitSchedulerAdapter(@NotNull Plugin plugin) {
        super(plugin);
        this.sync = VersionHelper.isFolia() ? new FoliaExecutor(plugin) : new BukkitExecutor(plugin);
        this.entity = BukkitSchedulerAdapter.hasEntityScheduler()
                ? new FoliaEntityExecutor(plugin)
                : new BukkitEntityExecutor(plugin);
    }

    @Override
    @NotNull
    public RegionExecutor<World> sync() {
        return this.sync;
    }

    /**
     * 返回当前平台的实体执行器.
     *
     * @return 实体执行器
     */
    @NotNull
    public EntityExecutor entity() {
        return this.entity;
    }

    private static boolean hasEntityScheduler() {
        try {
            Entity.class.getMethod("getScheduler");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }
}
