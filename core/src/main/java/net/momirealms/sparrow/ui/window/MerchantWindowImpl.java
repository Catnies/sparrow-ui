package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.ItemClick;
import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.internal.ObservableDispatcher;
import net.momirealms.sparrow.ui.internal.menu.MenuFactory;
import net.momirealms.sparrow.ui.internal.menu.MenuInput;
import net.momirealms.sparrow.ui.internal.menu.MerchantMenuHandle;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.util.HandlerList;
import net.momirealms.sparrow.ui.util.MiscUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link MerchantWindow} 的实体线程实现.
 * <p>公开修改方法允许从任意线程调用: 参数在调用线程完成校验和快照复制, 实际状态变更再通过
 * {@link AbstractWindow} 的命令通道进入玩家实体线程. volatile 字段只负责向 getter 发布最近已应用快照,
 * Merchant 协议状态与 Trade/Item 挂载由当前 {@link MerchantMenuHandle} 会话持有.
 */
final class MerchantWindowImpl extends AbstractWindow<MerchantMenuHandle> implements MerchantWindow {
    private final HandlerList<Consumer<TradeSelection>> tradeSelectionHandlers;

    private volatile int level;
    private volatile double progress;
    private volatile boolean restockMessageEnabled;
    private volatile List<MerchantWindow.Trade> trades; // 已应用的不可修改有序快照

    private int previousTradeIndex = -1; // 当前客户端会话最近一次成功选择的索引
    private long selectionResetVersion; // 识别 Item 处理器内重入触发的选择重置

    MerchantWindowImpl(
            @NotNull WindowManager manager,
            @NotNull Player viewer,
            @NotNull WindowLayout layout,
            @NotNull AbstractWindow.Settings settings,
            int level,
            double progress,
            boolean restockMessageEnabled,
            @NotNull List<MerchantWindow.Trade> trades,
            @NotNull List<Consumer<TradeSelection>> tradeSelectionHandlers
    ) {
        super(manager, viewer, layout, settings);
        this.level = level;
        this.progress = progress;
        this.restockMessageEnabled = restockMessageEnabled;
        this.trades = trades;
        this.tradeSelectionHandlers = new HandlerList<>(tradeSelectionHandlers);
    }

    @Override
    public void setLevel(int level) {
        if (level < 0 || level > 5) {
            throw new IllegalArgumentException("merchant level must be between 0 and 5: " + level);
        }
        this.submit(
                () -> {
                    if (this.level == level) {
                        return;
                    }
                    this.level = level;
                    MerchantMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setLevel(level);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Merchant Window level"
        );
    }

    @Override
    public void setProgress(double progress) {
        if (progress != -1.0 && (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0)) {
            throw new IllegalArgumentException("merchant progress must be -1.0 or between 0.0 and 1.0: " + progress);
        }
        this.submit(
                () -> {
                    if (Double.compare(this.progress, progress) == 0) {
                        return;
                    }
                    this.progress = progress;
                    MerchantMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setProgress(progress);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Merchant Window progress"
        );
    }

    @Override
    public void setRestockMessageEnabled(boolean enabled) {
        this.submit(
                () -> {
                    if (this.restockMessageEnabled == enabled) {
                        return;
                    }
                    this.restockMessageEnabled = enabled;
                    MerchantMenuHandle menuHandle = this.menuHandle();
                    if (menuHandle != null) {
                        menuHandle.setRestockMessageEnabled(enabled);
                        this.notifySynchronize();
                    }
                },
                "Failed to update Merchant Window restock message"
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>列表及其元素在调用线程同步校验并复制, 实体线程只接收不可修改快照.
     */
    @Override
    public void setTrades(@NotNull List<? extends MerchantWindow.Trade> trades) {
        List<MerchantWindow.Trade> copy = List.copyOf(trades);
        this.submit(
                () -> this.replaceTrades(copy),
                "Failed to replace Merchant Window trades"
        );
    }

    /**
     * 在实体线程事务性替换 Trade 挂载和已发布快照.
     */
    private void replaceTrades(List<MerchantWindow.Trade> trades) {
        List<MerchantWindow.Trade> previous = this.trades;
        MerchantMenuHandle menuHandle = this.menuHandle();

        // 先让菜单完整准备候选挂载, 失败时 Window 仍保留旧快照
        if (menuHandle != null) {
            menuHandle.setTrades(trades);
        }
        this.trades = trades;
        if (menuHandle != null) {
            this.notifySynchronize();
        }

        // 客户端会保留选择索引, 列表缩短后重开同一界面以清除可能悬空的索引
        if (trades.size() < previous.size()) {
            this.previousTradeIndex = -1;
            this.selectionResetVersion++;
            this.notifyUpdateTitle(this.title());
        }
    }

    @Override
    public void setTradeSelectionHandlers(@NotNull List<? extends Consumer<? super TradeSelection>> handlers) {
        List<Consumer<TradeSelection>> copy = MiscUtils.copyConsumers(handlers);
        this.submit(
                () -> this.tradeSelectionHandlers.set(copy),
                "Failed to replace Merchant Window trade selection handlers"
        );
    }

    @Override
    public void addTradeSelectionHandler(@NotNull Consumer<? super TradeSelection> handler) {
        Consumer<TradeSelection> copied = MiscUtils.narrowConsumer(Objects.requireNonNull(handler, "handler"));
        this.submit(
                () -> this.tradeSelectionHandlers.append(copied),
                "Failed to add Merchant Window trade selection handler"
        );
    }

    @Override
    public void removeTradeSelectionHandler(@NotNull Consumer<? super TradeSelection> handler) {
        Consumer<TradeSelection> copied = MiscUtils.narrowConsumer(Objects.requireNonNull(handler, "handler"));
        this.submit(
                () -> this.tradeSelectionHandlers.remove(copied),
                "Failed to remove Merchant Window trade selection handler"
        );
    }

    /**
     * 创建并初始化一次 Merchant 菜单会话. 任一步骤失败都会关闭已创建的部分会话.
     */
    @NotNull
    @Override
    protected MerchantMenuHandle createMenuHandle(@NotNull MenuFactory factory, long generation) {
        // 每次真正打开新会话都从未选择状态开始
        this.previousTradeIndex = -1;
        this.selectionResetVersion++;
        MerchantMenuHandle menuHandle = factory.merchant(this.viewer(), generation, this, this::report);
        try {
            menuHandle.setLevel(this.level);
            menuHandle.setProgress(this.progress);
            menuHandle.setRestockMessageEnabled(this.restockMessageEnabled);
            menuHandle.setTrades(this.trades);
            return menuHandle;
        } catch (RuntimeException | Error throwable) {
            // 初始化失败仍要释放菜单和已经挂载的 Trade, 关闭失败作为 suppressed 保留
            try {
                menuHandle.close(InventoryCloseEvent.Reason.PLUGIN);
            } catch (RuntimeException | Error closeFailure) {
                ThrowableUtils.combine(throwable, closeFailure);
            }
            throw throwable;
        }
    }

    @Override
    void tick(ScheduledTask task) {
        MerchantMenuHandle menuHandle = this.menuHandle();
        if (menuHandle != null && menuHandle.tickOffers()) {
            this.notifySynchronize();
        }
        super.tick(task);
    }

    @Override
    protected void handleWindowInput(@NotNull MenuInput.WindowSpecific input) {
        if (input instanceof MenuInput.WindowSpecific.TradeSelect tradeSelect) {
            this.handleTradeSelection(tradeSelect);
        }
    }

    /**
     * 处理原版客户端的交易选择包.
     * <p>三个 Item 处理器按输入槽 0, 1, 2 的顺序 fail-fast 执行. 处理器内即使重入修改 Trade 列表,
     * 本次调用仍使用入口快照; 列表缩短触发的选择重置不会被旧索引覆盖.
     */
    private void handleTradeSelection(MenuInput.WindowSpecific.TradeSelect selection) {
        MerchantMenuHandle menuHandle = this.menuHandle();
        if (menuHandle == null || selection.containerId() != menuHandle.containerId()) {
            return;
        }

        // 客户端在发包前已尝试把付款槽物品搬回背包, 即使索引随后被拒绝也必须恢复完整容器
        menuHandle.invalidateClientContents();
        this.notifySynchronize();

        // available 只影响展示, 当前快照中的任意合法索引都允许触发选择
        List<MerchantWindow.Trade> snapshot = this.trades;
        int selectedIndex = selection.index();
        if (selectedIndex < 0 || selectedIndex >= snapshot.size()) {
            return;
        }

        int previousIndex = this.previousTradeIndex;
        MerchantWindow.Trade previousTrade = previousIndex >= 0 && previousIndex < snapshot.size()
                ? snapshot.get(previousIndex)
                : null;
        MerchantWindow.Trade selectedTrade = snapshot.get(selectedIndex);
        long resetVersion = this.selectionResetVersion;

        // 任一 Item 抛错都会终止后续 Item 和 TradeSelection, previous 也不会前移
        selectedTrade.getFirstInput().handleClick(new ItemClick(this.viewer(), ClickType.LEFT, this, 0));
        selectedTrade.getSecondInput().handleClick(new ItemClick(this.viewer(), ClickType.LEFT, this, 1));
        selectedTrade.getResult().handleClick(new ItemClick(this.viewer(), ClickType.LEFT, this, 2));

        // Item 处理器可能重入缩短列表; 只有选择状态未被重置时才提交本次索引
        if (this.selectionResetVersion == resetVersion) {
            this.previousTradeIndex = selectedIndex;
        }

        // 重复选择仍执行三个 Item, 但不重复发布 TradeSelection
        if (previousIndex == selectedIndex) {
            return;
        }

        TradeSelection event = new TradeSelection(
                this.viewer(),
                this,
                previousIndex,
                selectedIndex,
                previousTrade,
                selectedTrade
        );
        this.tradeSelectionHandlers.forEachIsolated(
                handler -> handler.accept(event),
                "Failed to handle Merchant Window trade selection",
                this::report
        );
    }

    @Override
    public int getLevel() {
        return this.level;
    }

    @Override
    public double getProgress() {
        return this.progress;
    }

    @Override
    public boolean isRestockMessageEnabled() {
        return this.restockMessageEnabled;
    }

    @Override
    @NotNull
    public List<MerchantWindow.Trade> getTrades() {
        return this.trades;
    }

    @Override
    @NotNull
    public List<Consumer<TradeSelection>> getTradeSelectionHandlers() {
        return this.tradeSelectionHandlers.snapshot();
    }

    /**
     * Trade 的线程安全实现. 三个 Item 引用构建后固定, discount 与 available 使用原子字段跨线程发布;
     * setter 只在值实际变化时于调用线程同步发送对应的 TradeChange.
     */
    static final class TradeImpl implements MerchantWindow.Trade {
        private final Item firstInput;
        private final Item secondInput;
        private final Item result;
        private final AtomicInteger discount;
        private final AtomicBoolean available;
        private final ObservableDispatcher<MerchantWindow.TradeChange> changes = new ObservableDispatcher<>();

        private TradeImpl(
                @NotNull Item firstInput,
                @NotNull Item secondInput,
                @NotNull Item result,
                int discount,
                boolean available
        ) {
            this.firstInput = firstInput;
            this.secondInput = secondInput;
            this.result = result;
            this.discount = new AtomicInteger(discount);
            this.available = new AtomicBoolean(available);
        }

        @Override
        @NotNull
        public Item getFirstInput() {
            return this.firstInput;
        }

        @Override
        @NotNull
        public Item getSecondInput() {
            return this.secondInput;
        }

        @Override
        @NotNull
        public Item getResult() {
            return this.result;
        }

        @Override
        public int getDiscount() {
            return this.discount.get();
        }

        @Override
        public void setDiscount(int discount) {
            if (this.discount.getAndSet(discount) != discount) {
                this.changes.publish(MerchantWindow.TradeChange.DISCOUNT);
            }
        }

        @Override
        public boolean isAvailable() {
            return this.available.get();
        }

        @Override
        public void setAvailable(boolean available) {
            if (this.available.getAndSet(available) != available) {
                this.changes.publish(MerchantWindow.TradeChange.AVAILABLE);
            }
        }

        @Override
        @NotNull
        public Subscription subscribe(@NotNull Observer<? super MerchantWindow.TradeChange> observer) {
            return this.changes.subscribe(observer);
        }

        static final class BuilderImpl implements MerchantWindow.Trade.Builder {
            private Item firstInput = Item.empty();
            private Item secondInput = Item.empty();
            private Item result = Item.empty();
            private int discount;
            private boolean available = true;

            @Override
            @NotNull
            public MerchantWindow.Trade.Builder setFirstInput(@NotNull Item item) {
                this.firstInput = Objects.requireNonNull(item, "item");
                return this;
            }

            @Override
            @NotNull
            public MerchantWindow.Trade.Builder setSecondInput(@NotNull Item item) {
                this.secondInput = Objects.requireNonNull(item, "item");
                return this;
            }

            @Override
            @NotNull
            public MerchantWindow.Trade.Builder setResult(@NotNull Item item) {
                this.result = Objects.requireNonNull(item, "item");
                return this;
            }

            @Override
            @NotNull
            public MerchantWindow.Trade.Builder setDiscount(int discount) {
                this.discount = discount;
                return this;
            }

            @Override
            @NotNull
            public MerchantWindow.Trade.Builder setAvailable(boolean available) {
                this.available = available;
                return this;
            }

            @Override
            @NotNull
            public MerchantWindow.Trade build() {
                return new TradeImpl(
                        this.firstInput,
                        this.secondInput,
                        this.result,
                        this.discount,
                        this.available
                );
            }
        }
    }

    static final class BuilderImpl extends AbstractWindowBuilder<MerchantWindow, MerchantWindow.Builder> implements MerchantWindow.Builder {
        private Gui upperGui;
        private @Nullable Gui lowerGui;
        private int level;
        private double progress = -1.0;
        private boolean restockMessageEnabled;
        private List<MerchantWindow.Trade> trades = List.of();
        private List<Consumer<TradeSelection>> tradeSelectionHandlers = new ArrayList<>();

        BuilderImpl() {
        }

        private BuilderImpl(@NotNull BuilderImpl source) {
            super(source);
            this.upperGui = source.upperGui;
            this.lowerGui = source.lowerGui;
            this.level = source.level;
            this.progress = source.progress;
            this.restockMessageEnabled = source.restockMessageEnabled;
            this.trades = source.trades;
            this.tradeSelectionHandlers = new ArrayList<>(source.tradeSelectionHandlers);
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setUpperGui(@NotNull Gui upperGui) {
            this.upperGui = Objects.requireNonNull(upperGui, "upperGui");
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setLowerGui(@Nullable Gui lowerGui) {
            this.lowerGui = lowerGui;
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setLevel(int level) {
            if (level < 0 || level > 5) {
                throw new IllegalArgumentException("merchant level must be between 0 and 5: " + level);
            }
            this.level = level;
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setProgress(double progress) {
            if (progress != -1.0 && (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0)) {
                throw new IllegalArgumentException("merchant progress must be -1.0 or between 0.0 and 1.0: " + progress);
            }
            this.progress = progress;
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setRestockMessageEnabled(boolean enabled) {
            this.restockMessageEnabled = enabled;
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setTrades(@NotNull List<? extends MerchantWindow.Trade> trades) {
            this.trades = List.copyOf(trades);
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder setTradeSelectionHandlers(@NotNull List<? extends Consumer<? super TradeSelection>> handlers) {
            this.tradeSelectionHandlers = new ArrayList<>(MiscUtils.copyConsumers(handlers));
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder addTradeSelectionHandler(@NotNull Consumer<? super TradeSelection> handler) {
            this.tradeSelectionHandlers.add(
                    MiscUtils.narrowConsumer(Objects.requireNonNull(handler, "handler"))
            );
            return this;
        }

        @Override
        @NotNull
        public MerchantWindow.Builder clone() {
            return new BuilderImpl(this);
        }

        @Override
        @NotNull
        protected MerchantWindow.Builder self() {
            return this;
        }

        @Override
        @NotNull
        protected MerchantWindow createWindow(@NotNull Player viewer, @NotNull AbstractWindow.Settings settings) {
            if (this.upperGui == null) {
                this.upperGui = Gui.empty(new GuiSize(3, 1));
            } else if (this.upperGui.width() != 3 || this.upperGui.height() != 1) {
                throw new IllegalArgumentException("merchant upper GUI must have size 3x1");
            }
            WindowLayout layout = WindowLayout.of(
                    WindowLayout.Region.upper(this.upperGui),
                    WindowLayout.Region.lower(this.lowerGui)
            );
            return new MerchantWindowImpl(
                    WindowManager.getInstance(),
                    viewer,
                    layout,
                    settings,
                    this.level,
                    this.progress,
                    this.restockMessageEnabled,
                    this.trades,
                    List.copyOf(this.tradeSelectionHandlers)
            );
        }
    }
}
