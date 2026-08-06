package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.click.WindowOutsideClick;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.SlotElement;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 由一名玩家查看的 GUI 会话.
 * <p>所有变更方法都可以从任意线程调用. 命令会按玩家串行执行, 并只在玩家实体线程修改
 * GUI, 菜单和协议状态. 查询方法返回最近一次已应用状态的线程安全快照.
 */
public interface Window {

    /**
     * 创建普通窗口的 Builder.
     * 普通窗口由 {@code gui} 作为上半部分, 下半部分映射玩家原生物品栏.
     *
     * @param gui 上半部分 GUI
     * @return 可重复使用的 Builder
     */
    static @NotNull NormalWindow.Builder builder(@NotNull Gui gui) {
        return NormalWindow.builder().setUpperGui(gui);
    }

    /**
     * 创建上下分离窗口的 Builder.
     * 上下两个 GUI 分别控制容器和玩家物品栏区域.
     *
     * @param upperGui 上半部分 GUI
     * @param lowerGui 下半部分 9x4 GUI
     * @return 可重复使用的 Builder
     */
    static @NotNull NormalWindow.Builder splitBuilder(@NotNull Gui upperGui, @NotNull Gui lowerGui) {
        return NormalWindow.builder().setUpperGui(upperGui).setLowerGui(lowerGui);
    }

    /**
     * 创建合并窗口的 Builder.
     * 单个 GUI 同时覆盖容器和玩家物品栏区域.
     *
     * @param gui 合并后的 GUI
     * @return 可重复使用的 Builder
     */
    static @NotNull NormalWindow.Builder mergedBuilder(@NotNull Gui gui) {
        return NormalWindow.mergedBuilder(gui);
    }

    /**
     * 请求打开 Window, Stage 完成表示服务端打开流程已经执行, 不表示客户端已经显示.
     *
     * @return 打开请求的执行结果
     */
    @NotNull CompletionStage<OpenResult> open();

    /**
     * 请求关闭 Window.
     * closeable 只限制玩家主动关闭, 不限制插件命令.
     *
     * @return 关闭请求的执行结果
     */
    @NotNull CompletionStage<CloseResult> close();

    /**
     * 设置动态标题来源并请求刷新.
     * Supplier 会在玩家实体线程读取, 且不得返回 null.
     *
     * @param titleSupplier 标题来源
     */
    void setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier);

    /**
     * 设置固定标题并请求刷新.
     *
     * @param title 新标题
     */
    void setTitle(@NotNull Component title);

    /**
     * 使用纯文本组件设置固定标题.
     *
     * @param title 新标题
     */
    default void setTitle(@NotNull String title) {
        //todo 接入 minimessage
        this.setTitle(Component.text(title));
    }

    /**
     * 请求重新读取当前标题 Supplier.
     * 多次请求会在玩家实体 tick 中合并.
     */
    void updateTitle();

    /**
     * 当前菜单标题, 也就是最近一次已应用的标题快照.
     *
     * @return 当前标题
     */
    @NotNull Component title();

    /**
     * 设置是否接受客户端主动关闭.
     * 此设置不阻止插件, 断线或 Bukkit 外部关闭.
     *
     * @param closeable 是否可由客户端主动关闭
     */
    void setCloseable(boolean closeable);

    /**
     * 设置玩家主动关闭时要解析并打开的后备 Window.
     * Supplier 仅在玩家主动关闭后读取.
     *
     * @param fallbackWindow 后备 Window 来源
     */
    void setFallbackWindow(@NotNull Supplier<? extends @Nullable Window> fallbackWindow);

    /**
     * 设置玩家主动关闭时要打开的固定后备 Window.
     * 传入 null 可清除后备 Window.
     *
     * @param fallbackWindow 后备 Window
     */
    default void setFallbackWindow(@Nullable Window fallbackWindow) {
        this.setFallbackWindow(() -> fallbackWindow);
    }

    /**
     * 替换打开后依次执行的处理器列表.
     *
     * @param openHandlers 新处理器列表
     */
    void setOpenHandlers(@NotNull List<? extends Runnable> openHandlers);

    /**
     * 当前打开处理器列表的快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Runnable> getOpenHandlers();

    /**
     * 在现有打开处理器末尾追加一个处理器.
     *
     * @param openHandler 打开处理器
     */
    void addOpenHandler(@NotNull Runnable openHandler);

    /**
     * 移除一个与给定对象相等的打开处理器.
     *
     * @param openHandler 要移除的打开处理器
     */
    void removeOpenHandler(@NotNull Runnable openHandler);

    /**
     * 替换关闭后依次执行的处理器列表.
     *
     * @param closeHandlers 新处理器列表
     */
    void setCloseHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers);

    /**
     * 当前打开处理器列表的快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<InventoryCloseEvent.Reason>> getCloseHandlers();

    /**
     * 在现有关闭处理器末尾追加一个处理器.
     *
     * @param closeHandler 关闭处理器, 参数为关闭原因
     */
    void addCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler);

    /**
     * 移除一个与给定对象相等的关闭处理器.
     *
     * @param closeHandler 要移除的关闭处理器
     */
    void removeCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler);

    /**
     * 替换容器外点击处理器列表.
     * 处理器可以取消 {@link WindowOutsideClick} 以阻止该次点击.
     *
     * @param outsideClickHandlers 新处理器列表
     */
    void setOutsideClickHandlers(@NotNull List<? extends Consumer<? super WindowOutsideClick>> outsideClickHandlers);

    /**
     * 当前容器外点击处理器列表的快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<WindowOutsideClick>> getOutsideClickHandlers();

    /**
     * 在现有容器外点击处理器末尾追加一个处理器.
     *
     * @param outsideClickHandler 容器外点击处理器
     */
    void addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler);

    /**
     * 移除一个与给定对象相等的容器外点击处理器.
     *
     * @param outsideClickHandler 要移除的容器外点击处理器
     */
    void removeOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler);

    /**
     * 设置服务器窗口状态, 并在已打开时发送 Ping 等待客户端确认.
     *
     * @param windowState 新服务器窗口状态
     */
    void setWindowState(int windowState);

    /**
     * 将服务器窗口状态加一, 并在已打开时等待客户端确认.
     */
    void incrementWindowState();

    /**
     * 返回最近一次设置的服务器窗口状态.
     *
     * @return 服务器窗口状态
     */
    int getServerWindowState();

    /**
     * 返回最近一次收到 Pong 确认的客户端窗口状态.
     *
     * @return 客户端已确认窗口状态
     */
    int getClientWindowState();

    /**
     * 替换客户端 Pong 确认窗口状态时依次执行的处理器列表.
     *
     * @param handlers 新处理器列表
     */
    void setWindowStateChangeHandlers(@NotNull List<? extends Consumer<? super Integer>> handlers);

    /**
     * 当前客户端 Pong 确认窗口状态的处理器列表的快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<Integer>> getWindowStateChangeHandlers();

    /**
     * 在现有客户端 Pong 确认窗口状态处理器末尾追加一个处理器.
     *
     * @param handler 状态确认处理器
     */
    void addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler);

    /**
     * 移除一个与给定对象相等的客户端 Pong 确认窗口状态处理器.
     *
     * @param handler 要移除的状态确认处理器
     */
    void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler);

    /**
     * 设置光标显示转换器.
     * 参数为实际光标副本, 空光标以 null 表示; 返回 null 时保留实际光标显示.
     *
     * @param cursorVisualizer 光标显示转换器
     */
    void setCursorVisualizer(@NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer);

    /**
     * 当前光标显示转换器.
     *
     * @return 光标显示转换器
     */
    @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> getCursorVisualizer();

    /**
     * 通知 Window 对指定槽位的显示内容进行更新.
     * <p>通知可以来自任意线程; 实际渲染和协议同步会合并到玩家实体 tick.
     *
     * @param windowSlot Window 槽位
     */
    void notifyUpdate(int windowSlot);

    /**
     * 通知 Window 进行一次强制全量更新.
     */
    void notifyUpdateAll();

    /**
     * 此 Window 的所属玩家.
     *
     * @return 查看者
     */
    @NotNull Player viewer();

    /**
     * Window 当前是否打开.
     *
     * @return 是否打开
     */
    boolean isOpen();

    /**
     * 返回是否接受客户端主动发送的关闭请求.
     *
     * @return 是否接受客户端主动关闭
     */
    boolean isCloseable();

    /**
     * 返回占据玩家物品栏区域的下部 GUI.
     * <p>合并窗口的 upper 与 lower 区域会返回同一个根 GUI.
     *
     * @return 下部 GUI
     */
    @NotNull
    Gui lowerGui();

    /**
     * 返回下部 GUI 按默认布局引用的 ReferencingInventory.
     *
     * @return 默认 ReferencingInventory
     */
    @Nullable
    default ReferencingInventory defaultLowerInventory() {
        Gui lowerGui = this.lowerGui();
        if (lowerGui.width() != 9 || lowerGui.height() != 4) {
            return null;
        }
        // 默认 lower 由首槽标识; 只有需要区分同形自定义 GUI 时才记录构建来源.
        return lowerGui.element(0) instanceof SlotElement.InventoryLink(ReferencingInventory inventory, int slot) && slot == 0
                ? inventory
                : null;
    }

    /**
     * 返回 Window 直接拥有的根 GUI, 不包含嵌套 GUI.
     *
     * @return 根 GUI 列表
     */
    @Unmodifiable
    @NotNull List<Gui> guis();

    /**
     * 返回 Window 槽位对应的根 GUI 链接.
     *
     * @param windowSlot Window 槽位
     * @return 根 GUI 链接
     */
    @NotNull
    SlotElement.GuiLink guiAt(int windowSlot);

    /**
     * 返回玩家快捷栏槽位对应的根 GUI 链接.
     *
     * @param hotbarSlot 快捷栏索引
     * @return 根 GUI 链接
     */
    @NotNull
    SlotElement.GuiLink guiAtHotbar(int hotbarSlot);

    /**
     * 打开请求的执行结果.
     */
    enum OpenResult {
        OPENED,
        ALREADY_OPEN,
        VIEWER_UNAVAILABLE
    }

    /**
     * 关闭请求的执行结果.
     */
    enum CloseResult {
        CLOSED,
        ALREADY_CLOSED
    }

    /**
     * 可重复使用的类型化 Window Builder.
     *
     * @param <W> 创建的 Window 类型
     * @param <B> 具体 Builder 类型
     */
    interface Builder<W extends Window, B extends Builder<W, B>> extends Cloneable {

        /**
         * 设置 {@link #build()} 使用的玩家.
         *
         * @param viewer 查看者
         * @return 此 Builder
         */
        @NotNull B setViewer(@NotNull Player viewer);

        /**
         * 设置动态标题来源.
         *
         * @param titleSupplier 标题来源
         * @return 此 Builder
         */
        @NotNull B setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier);

        /**
         * 设置固定标题.
         *
         * @param title 标题
         * @return 此 Builder
         */
        @NotNull B setTitle(@NotNull Component title);

        /**
         * 使用纯文本组件设置固定标题.
         *
         * @param title 标题
         * @return 此 Builder
         */
        default @NotNull B setTitle(@NotNull String title) {
            return this.setTitle(Component.text(title));
        }

        /**
         * 设置是否接受客户端主动关闭.
         *
         * @param closeable 是否可由客户端主动关闭
         * @return 此 Builder
         */
        @NotNull B setCloseable(boolean closeable);

        /**
         * 替换打开后依次执行的处理器列表.
         *
         * @param openHandlers 打开处理器
         * @return 此 Builder
         */
        @NotNull B setOpenHandlers(@NotNull List<? extends Runnable> openHandlers);

        /**
         * 追加一个打开处理器.
         *
         * @param openHandler 打开处理器
         * @return 此 Builder
         */
        @NotNull B addOpenHandler(@NotNull Runnable openHandler);

        /**
         * 替换关闭后依次执行的处理器列表.
         *
         * @param closeHandlers 关闭处理器
         * @return 此 Builder
         */
        @NotNull B setCloseHandlers(
                @NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> closeHandlers
        );

        /**
         * 追加一个关闭处理器.
         *
         * @param closeHandler 关闭处理器
         * @return 此 Builder
         */
        @NotNull B addCloseHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> closeHandler);

        /**
         * 替换容器外点击处理器列表. 每个处理器同时接收本 Builder 创建的具体 Window.
         *
         * @param outsideClickHandlers 容器外点击处理器
         * @return 此 Builder
         */
        @NotNull
        B setOutsideClickHandlers(
                @NotNull List<? extends BiConsumer<? super W, ? super WindowOutsideClick>> outsideClickHandlers
        );

        /**
         * 追加一个容器外点击处理器. 处理器同时接收本 Builder 创建的具体 Window.
         *
         * @param outsideClickHandler 容器外点击处理器
         * @return 此 Builder
         */
        @NotNull
        default B addOutsideClickHandler(@NotNull BiConsumer<? super W, ? super WindowOutsideClick> outsideClickHandler) {
            return this.addModifier(window -> window.addOutsideClickHandler(click -> outsideClickHandler.accept(window, click)));
        }

        /**
         * 追加一个只接收点击上下文的容器外点击处理器.
         *
         * @param outsideClickHandler 容器外点击处理器
         * @return 此 Builder
         */
        @NotNull
        B addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler);

        /**
         * 设置玩家主动关闭时要解析的后备 Window.
         *
         * @param fallbackWindow 后备 Window 来源
         * @return 此 Builder
         */
        @NotNull B setFallbackWindow(@NotNull Supplier<? extends @Nullable Window> fallbackWindow);

        /**
         * 设置玩家主动关闭时要打开的固定后备 Window.
         *
         * @param fallbackWindow 后备 Window, null 表示不打开后备 Window
         * @return 此 Builder
         */
        default @NotNull B setFallbackWindow(@Nullable Window fallbackWindow) {
            return this.setFallbackWindow(() -> fallbackWindow);
        }

        /**
         * 设置初始服务器窗口状态.
         *
         * @param windowState 初始状态
         * @return 此 Builder
         */
        @NotNull B setWindowState(int windowState);

        /**
         * 替换客户端状态确认处理器列表.
         *
         * @param handlers 状态确认处理器
         * @return 此 Builder
         */
        @NotNull B setWindowStateChangeHandlers(
                @NotNull List<? extends Consumer<? super Integer>> handlers
        );

        /**
         * 追加一个客户端状态确认处理器.
         *
         * @param handler 状态确认处理器
         * @return 此 Builder
         */
        @NotNull B addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler);

        /**
         * 设置光标显示转换器.
         *
         * @param cursorVisualizer 光标显示转换器
         * @return 此 Builder
         */
        @NotNull B setCursorVisualizer(
                @NotNull Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizer
        );

        /**
         * 替换创建完成后依次执行的 Window 修改器列表.
         *
         * @param modifiers Window 修改器
         * @return 此 Builder
         */
        @NotNull B setModifiers(@NotNull List<? extends Consumer<? super W>> modifiers);

        /**
         * 追加一个创建完成后执行的 Window 修改器.
         *
         * @param modifier Window 修改器
         * @return 此 Builder
         */
        @NotNull B addModifier(@NotNull Consumer<? super W> modifier);

        /**
         * 创建独立的 Builder 副本.
         * 可变处理器列表会被复制, 已引用的 GUI 与函数对象保持复用.
         *
         * @return Builder 副本
         */
        @NotNull B clone();

        /**
         * 使用已设置的查看者创建 Window.
         * <p>若未显式设置 lower GUI, 此调用会同步读取查看者的 Bukkit 背包来创建
         * {@link ReferencingInventory}. 调用方必须保证当前线程可以合法访问该背包;
         *
         * @return 新的未打开 Window
         * @throws IllegalStateException 未设置查看者时抛出
         */
        @NotNull W build();

        /**
         * 为指定查看者创建 Window.
         * <p>若未显式设置 lower GUI, 此调用会同步读取查看者的 Bukkit 背包来创建
         * {@link ReferencingInventory}. 调用方必须保证当前线程可以合法访问该背包;
         *
         * @param viewer 查看者
         * @return 新的未打开 Window
         */
        @NotNull W build(@NotNull Player viewer);

        /**
         * 为指定查看者创建并请求打开 Window.
         * <p>此方法先同步调用 {@link #build(Player)}, 因而同样受其 Bukkit 背包线程约束.
         *
         * @param viewer 查看者
         * @return 打开请求的执行结果
         */
        default @NotNull CompletionStage<OpenResult> open(@NotNull Player viewer) {
            return this.build(viewer).open();
        }
    }
}
