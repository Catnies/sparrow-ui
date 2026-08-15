package net.momirealms.sparrow.ui;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import net.momirealms.sparrow.ui.internal.map.MapColorPalette;
import net.momirealms.sparrow.ui.window.WindowManager;
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
import java.util.function.Consumer;

public class SparrowUI implements Listener {
    private static final String WARNINGS_PROPERTY = "sparrow.ui.warnings";
    private static final SparrowUI INSTANCE = new SparrowUI();

    private Plugin plugin;
    private WindowManager windowManager;
    private volatile boolean fireBukkitInventoryEvents = true;
    private boolean warningsEnabled = Boolean.parseBoolean(System.getProperty(WARNINGS_PROPERTY, "true"));
    private Consumer<? super String> warningHandler = msg -> this.getPlugin().getComponentLogger().warn(msg);
    private BiConsumer<? super String, ? super Throwable> exceptionHandler = (msg, e) -> this.getPlugin().getComponentLogger().error(msg, e);
    private final List<Runnable> disableHandlers = new ArrayList<>();

    private SparrowUI() {}

    public static SparrowUI getInstance() {
        return INSTANCE;
    }

    /**
     * 设置 SparrowUI 运行所在的插件实例.
     * 该实例用于注册事件监听器, 调度任务等.
     *
     * @param plugin 要设置的插件实例
     * @throws IllegalStateException 如果插件实例已设置
     */
    public void setUp(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        if (this.plugin != null) {
            throw new IllegalStateException("Plugin is already set");
        }

        BukkitProxyInstaller.setUp();
        MapColorPalette.initialize();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.plugin = plugin;
        this.windowManager = WindowManager.create();
        this.addDisableHandler(this.windowManager::shutdown);
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
                return Optional.ofNullable(pluginClassLoader.getPlugin());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        return Optional.empty();
    }

    /**
     * 是否应在与 UI 交互时触发 Bukkit 的相关事件:
     * {@link org.bukkit.event.inventory.InventoryClickEvent}
     * {@link org.bukkit.event.inventory.InventoryDragEvent}
     *
     * @return 是否应在与 UI 交互时触发 Bukkit 物品栏事件
     */
    public boolean fireBukkitInventoryEvents() {
        return this.fireBukkitInventoryEvents;
    }

    /**
     * 是否应在与 UI 交互时触发 Bukkit 的相关事件:
     *
     * @param fireBukkitInventoryEvents 是否允许派发 Bukkit Inventory 事件
     */
    public void setFireBukkitInventoryEvents(boolean fireBukkitInventoryEvents) {
        this.fireBukkitInventoryEvents = fireBukkitInventoryEvents;
    }

    /**
     * 设置用于处理用户代码抛出但被 UI 抑制的异常的处理器.
     *
     * @param exceptionHandler 新的异常处理器
     */
    public void setExceptionHandler(BiConsumer<? super String, ? super Throwable> exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    /**
     * 将 UI 已隔离的异常交给当前异常处理器处理.
     *
     * @param message 异常发生原因
     * @param throwable 原始异常
     */
    public void handleException(String message, Throwable throwable) {
        exceptionHandler.accept(
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(throwable, "throwable")
        );
    }

    /**
     * 是否报告 UI 用法警告.
     * <p>默认开启, 可以用 JVM 参数 {@code -Dsparrow.ui.warnings=false} 改掉默认值.
     * 只有值恰好是 {@code true}(忽略大小写)才算开启.
     *
     * @return 是否报告 UI 用法警告
     */
    public boolean warningsEnabled() {
        return this.warningsEnabled;
    }

    /**
     * 设置是否报告 UI 用法警告.
     *
     * @param warningsEnabled 是否报告 UI 用法警告
     */
    public void setWarningsEnabled(boolean warningsEnabled) {
        this.warningsEnabled = warningsEnabled;
    }

    /**
     * 返回当前用于处理 UI 用法警告的处理器.
     *
     * @return 当前的警告处理器
     */
    public Consumer<? super String> warningHandler() {
        return this.warningHandler;
    }

    /**
     * 设置用于处理 UI 用法警告的处理器.
     *
     * @param warningHandler 新的警告处理器
     */
    public void setWarningHandler(Consumer<? super String> warningHandler) {
        this.warningHandler = Objects.requireNonNull(warningHandler, "warningHandler");
    }

    /**
     * 报告一条 UI 用法警告.
     * <p>用于那些不会抛异常, 但插件作者多半想不到的行为, 例如交互在提交前被整体丢弃.
     * 这类问题在被发现时肇事者早已返回, 堆栈没有价值, 所以只带一段说明文本.
     * <p>{@link #warningsEnabled()} 关闭时本方法什么都不做.
     *
     * @param message 警告内容
     */
    public void warn(String message) {
        if (this.warningsEnabled) {
            this.warningHandler.accept(Objects.requireNonNull(message, "message"));
        }
    }

    /**
     * 添加一个在插件禁用时执行的 {@link Runnable}.
     *
     * @param runnable 当插件禁用时执行的 {@link Runnable} 任务
     */
    public void addDisableHandler(Runnable runnable) {
        this.disableHandlers.add(runnable);
    }

    /**
     * 处理插件关闭时的预设任务.
     *
     * @param event 插件关闭事件
     */
    @EventHandler
    private void handlePluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(this.plugin)) {
            this.disableHandlers.forEach(Runnable::run);
        }
    }

    /**
     * 获取当前持有的 Window 管理器.
     *
     * @return Window 管理器
     */
    public WindowManager windowManager() {
        if (this.windowManager == null) {
            this.getPlugin();
        }
        return this.windowManager;
    }
}
