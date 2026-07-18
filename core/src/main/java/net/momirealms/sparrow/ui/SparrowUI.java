package net.momirealms.sparrow.ui;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

public class SparrowUI implements Listener {
    private static final SparrowUI INSTANCE = new SparrowUI();

    private Plugin plugin;
    private boolean fireBukkitInventoryEvents = true;
    private final List<Runnable> disableHandlers = new ArrayList<>();
    private BiConsumer<? super String, ? super Throwable> exceptionHandler = (msg, e) -> this.getPlugin().getComponentLogger().error(msg, e);

    private SparrowUI() {}

    public static SparrowUI getInstance() {
        return INSTANCE;
    }

    /**
     * 获取UI运行时所属的插件实例.
     * 如果可能, 插件实例将从类加载器中推断得出.
     * 如果无法推断, 则必须事先使用 {@link #setUp(Plugin)} 手动设置插件实例.
     *
     * @return 插件实例
     * @throws IllegalStateException 如果插件实例未设置且无法推断
     */
    public Plugin getPlugin() {
        if (this.plugin == null) {
            Plugin discovered = tryFindPlugin().orElseThrow(() -> new IllegalStateException(
                    "Plugin is not set. Set it using SparrowUI.getInstance().setUp(plugin);"
            ));
            this.setUp(discovered);
        }
        return this.plugin;
    }

    @SuppressWarnings({"CallToPrintStackTrace", "UnstableApiUsage"})
    private Optional<Plugin> tryFindPlugin() {
        ClassLoader classLoader = getClass().getClassLoader();

        try {
            if (classLoader instanceof ConfiguredPluginClassLoader pluginClassLoader) {
                return Optional.of(pluginClassLoader.getPlugin());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return Optional.empty();
    }

    /**
     * 设置 SparrowUI 运行所在的插件实例.
     * 该实例用于注册事件监听器、调度任务等.
     *
     * @param plugin 要设置的插件实例
     * @throws IllegalStateException 如果插件实例已设置
     */
    public void setUp(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (this.plugin != null) {
            throw new IllegalStateException("Plugin is already set");
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.plugin = plugin;
    }

    /**
     * 设置用于处理用户代码抛出但被 SparrowUI 抑制的异常的处理器,
     * 例如处理物品栏事件时发生的异常.
     *
     * @param exceptionHandler 新的异常处理器
     */
    public void setExceptionHandler(BiConsumer<? super String, ? super Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * 是否应在与 SparrowUI 物品栏交互时触发 Bukkit 的
     * {@link org.bukkit.event.inventory.InventoryClickEvent} 和
     * {@link org.bukkit.event.inventory.InventoryDragEvent}.
     * <p>
     * 默认值为 {@code true}. 可通过 {@link #setFireBukkitInventoryEvents(boolean)} 或系统属性
     * {@code invui.fireBukkitClickEvents} 修改. 如果该系统属性存在, 它会覆盖通过
     * {@link #setFireBukkitInventoryEvents(boolean)} 设置的值.
     *
     * @return 是否应在与 SparrowUI 物品栏交互时触发 Bukkit 物品栏事件
     */
    public boolean isFireBukkitInventoryEvents() {
        return this.fireBukkitInventoryEvents;
    }

    /**
     * 设置是否应在与 SparrowUI 物品栏交互时触发 Bukkit 的
     * {@link org.bukkit.event.inventory.InventoryClickEvent} 和
     * {@link org.bukkit.event.inventory.InventoryDragEvent}.
     * <p>
     * 默认值为 {@code true}. 可通过此方法或系统属性 {@code invui.fireBukkitInventoryEvents} 修改.
     * 如果该系统属性存在, 它会覆盖通过此方法设置的值.
     *
     * @param fireBukkitInventoryEvents 是否应在与 SparrowUI 物品栏交互时触发 Bukkit 的
     *                                  {@link org.bukkit.event.inventory.InventoryClickEvent} 和
     *                                  {@link org.bukkit.event.inventory.InventoryDragEvent}
     */
    public void setFireBukkitInventoryEvents(boolean fireBukkitInventoryEvents) {
        this.fireBukkitInventoryEvents = fireBukkitInventoryEvents;
    }

    /**
     * 添加一个在插件禁用时执行的 {@link Runnable}.
     *
     * @param runnable 当插件禁用时执行的 {@link Runnable} 任务
     */
    public void addDisableHandler(Runnable runnable) {
        this.disableHandlers.add(runnable);
    }

    @EventHandler
    private void handlePluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(this.plugin)) {
            this.disableHandlers.forEach(Runnable::run);
        }
    }
}
