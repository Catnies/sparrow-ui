package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.network.ClientboundPacketFilter;
import net.momirealms.sparrow.ui.network.ConnectionState;
import net.momirealms.sparrow.ui.network.PacketFlow;
import net.momirealms.sparrow.ui.network.PacketIdRegistry;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.window.RenderCell;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.proxy.minecraft.core.component.DataComponentExactPredicateProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.network.protocol.game.ClientboundMerchantOffersPacketProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.npc.VillagerDataProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.inventory.MenuTypeProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading.ItemCostProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading.MerchantOfferProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.trading.MerchantOffersProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.ThrowableUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
import net.momirealms.sparrow.ui.window.MerchantWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@SuppressWarnings("UnstableApiUsage")
final class MerchantMenuHandleImpl extends ContainerMenuHandle implements MerchantMenuHandle {
    private static final int FIRST_INPUT_SLOT = 0;
    private static final int SECOND_INPUT_SLOT = 1;
    private static final int RESULT_SLOT = 2;
    private static final ClientboundPacketFilter MERCHANT_OFFERS_FILTER = new ClientboundPacketFilter() {
        @Override
        public int[] suppressedPacketIds(@NotNull PacketIdRegistry packetIds) {
            return new int[]{packetIds.byName("minecraft:merchant_offers", ConnectionState.PLAY, PacketFlow.CLIENTBOUND)};
        }

        @Override
        public boolean suppresses(@NotNull Object packet) {
            return ClientboundMerchantOffersPacketProxy.CLASS.isInstance(packet);
        }
    };

    // 渲染依赖与当前挂载
    private final BiConsumer<? super String, ? super Throwable> reporter;
    private final NamespacedKey markerKey;
    private final Window window;
    private @Nullable TradeBindings bindings;

    // 商人显示状态
    private int level;
    private double progress = -1.0;
    private boolean restockMessageEnabled;

    // 客户端预测纠正
    private boolean selectionReconciliationPending;
    private boolean selectionContentRecoveryOnly;
    private boolean resultReconciliationPending;

    // Offers 版本与当前网络批次
    private final AtomicLong offerRevision = new AtomicLong(); // 任意线程只递增此值, 实际渲染仍在实体线程
    private long committedOfferRevision = -1; // 最近成功进入网络发送批次的 revision
    private long queuedOfferRevision;
    private @Nullable Object committedOffersPacket;
    private @Nullable Object queuedOffersPacket;
    private boolean offersQueued;

    MerchantMenuHandleImpl(
            @NotNull MenuPacketGateway packets,
            @NotNull Player player,
            long generation,
            @NotNull MerchantWindow window,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    ) {
        super(packets, player, MenuTypeProxy.MERCHANT, InventoryType.MERCHANT, org.bukkit.inventory.MenuType.MERCHANT, 3, generation);
        this.reporter = reporter;
        this.markerKey = new NamespacedKey(SparrowUI.getInstance().getPlugin(), "merchant_offer");
        this.window = window;
    }

    @Override
    public void setLevel(int level) {
        if (this.level == level) {
            return;
        }
        this.level = level;
        this.dirtyOffers();
    }

    @Override
    public void setProgress(double progress) {
        if (Double.compare(this.progress, progress) == 0) {
            return;
        }
        this.progress = progress;
        this.dirtyOffers();
    }

    @Override
    public void setRestockMessageEnabled(boolean enabled) {
        if (this.restockMessageEnabled == enabled) {
            return;
        }
        this.restockMessageEnabled = enabled;
        this.dirtyOffers();
    }

    @Override
    public void setTrades(@NotNull List<MerchantWindow.Trade> trades) {
        TradeBindings candidate = new TradeBindings(trades, this.window, this::dirtyOffers);
        TradeBindings previous = this.bindings;
        if (previous != null) {
            previous.retire();
        }
        this.bindings = candidate;
        candidate.activate();
        if (previous != null) {
            try {
                previous.close();
            } catch (RuntimeException | Error throwable) {
                this.reporter.accept("Failed to close previous Merchant trade bindings", throwable);
            }
        }
        this.dirtyOffers();
    }

    @Override
    public void invalidateClientContents() {
        this.selectionReconciliationPending = true;
    }

    @Override
    public boolean tickOffers() {
        return this.offerRevision.get() != this.committedOfferRevision;
    }

    private void dirtyOffers() {
        this.offerRevision.incrementAndGet();
    }

    @Override
    @NotNull
    protected ClientboundPacketFilter clientboundPacketFilter() {
        return MERCHANT_OFFERS_FILTER;
    }

    @Override
    protected void handleAcceptedInteraction() {
        this.forceRemoteSlot(RESULT_SLOT);
        this.resultReconciliationPending = true;
    }

    @Override
    protected void prepareSynchronize(@NotNull BitSet dirtySlots, boolean forceFull) {
        boolean inputDirty = dirtySlots.get(FIRST_INPUT_SLOT) || dirtySlots.get(SECOND_INPUT_SLOT);
        if (inputDirty) {
            this.forceRemoteSlot(RESULT_SLOT);
        }
        if (forceFull || inputDirty || dirtySlots.get(RESULT_SLOT)) {
            this.resultReconciliationPending = true;
        }
    }

    // 交易选择包没有 changed-slots, 用完整内容恢复客户端自动搬运到未知背包槽的物品.
    @Override
    public void synchronize(
            ItemStack @NotNull [] slots,
            @NotNull BitSet dirtySlots,
            @NotNull CursorSnapshot cursor,
            boolean cursorDirty,
            boolean forceFull
    ) {
        boolean reconcileSelection = this.selectionReconciliationPending;
        this.selectionContentRecoveryOnly = reconcileSelection && !forceFull;
        try {
            super.synchronize(slots, dirtySlots, cursor, cursorDirty, forceFull || reconcileSelection);
            if (reconcileSelection) {
                this.selectionReconciliationPending = false;
            }
            this.resultReconciliationPending = false;
        } finally {
            this.selectionContentRecoveryOnly = false;
        }
    }

    // 标题重开自带完整内容, 同时完成待处理的选择纠正.
    @Override
    public void reopenWithTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor) {
        boolean reconcileResult = this.resultReconciliationPending;
        this.resultReconciliationPending = false;
        try {
            super.reopenWithTitle(title, slots, cursor);
            this.selectionReconciliationPending = false;
        } catch (RuntimeException | Error throwable) {
            this.resultReconciliationPending = reconcileResult;
            throw throwable;
        }
    }

    // 每批固定一个 revision, 发送期间到达的新失效留给后续 tick.
    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        long revision = this.offerRevision.get();
        // 选择纠正借用完整内容包, offers 仍按 revision 判断是否重建.
        boolean forceOffers = forceFull && !this.selectionContentRecoveryOnly;
        this.offersQueued = forceOffers || revision != this.committedOfferRevision || this.committedOffersPacket == null;
        this.queuedOffersPacket = null;
        if (this.offersQueued) {
            this.queuedOfferRevision = revision;
            this.queuedOffersPacket = this.createOffersPacket();
        }

        if (this.resultReconciliationPending) {
            // MerchantContainer 会在 Slot.setChanged 后重算并清空不匹配 offers 的结果槽
            outgoing.add(0, this.createOffersPacket(List.of()));
            outgoing.add(this.currentOffersPacket());
        } else if (this.offersQueued) {
            outgoing.add(this.currentOffersPacket());
        }
    }

    @Override
    protected void commitPackets() {
        if (this.offersQueued) {
            this.committedOfferRevision = this.queuedOfferRevision;
            this.committedOffersPacket = this.queuedOffersPacket;
            this.queuedOffersPacket = null;
            this.offersQueued = false;
        }
    }

    // 结果槽纠正需要复用本轮即将提交的 offers 包.
    private Object currentOffersPacket() {
        Object packet = this.offersQueued ? this.queuedOffersPacket : this.committedOffersPacket;
        if (packet == null) {
            throw new IllegalStateException("Merchant offers packet is unavailable");
        }
        return packet;
    }

    // 渲染当前全部 Trade, 客户端索引与 entries 保持一致.
    private Object createOffersPacket() {
        TradeBindings bindings = this.bindings;
        List<TradeBinding> entries = bindings == null ? List.of() : bindings.entries();

        ArrayList<Object> offers = new ArrayList<>(entries.size()); // NMS MerchantOffer 列表
        for (int index = 0; index < entries.size(); index++) {
            offers.add(this.createOffer(entries.get(index), index));
        }
        return this.createOffersPacket(offers);
    }

    private Object createOffersPacket(List<Object> offers) {
        // progress=-1.0 明确表示隐藏经验条, 其他值换算为当前等级内的 XP
        Object merchantOffers = MerchantOffersProxy.INSTANCE.newInstance(offers);
        boolean showProgress = this.progress != -1.0;
        int villagerXp = showProgress ? (int) Math.floor(VillagerDataProxy.INSTANCE.getMaxXpPerLevel(this.level) * this.progress) : 0;
        return ClientboundMerchantOffersPacketProxy.INSTANCE.newInstance(
                this.containerId(),
                merchantOffers,
                this.level,
                villagerXp,
                showProgress,
                this.restockMessageEnabled
        );
    }

    private Object createOffer(TradeBinding binding, int tradeIndex) {
        // 每个位置缓存最近一次成功结果, 渲染异常时由 render 保留旧快照
        ItemStack firstInput = binding.renderFirstInput(this, tradeIndex);
        ItemStack secondInput = binding.renderSecondInput(this, tradeIndex);
        ItemStack result = binding.renderResult(this, tradeIndex);

        // 随机标记写入协议展示副本, Trade Item 提供的服务端物品保持不变.
        MarkedStack markedFirstInput = this.mark(firstInput, true);
        Object firstCost = this.createCost(markedFirstInput);
        Optional<Object> secondCost = secondInput.isEmpty()
                ? Optional.empty()
                : Optional.of(this.createCost(this.mark(secondInput, false)));
        MarkedStack markedResult = this.mark(result, true);
        int discount = binding.trade().getDiscount();
        int specialPriceDiff = OfferMath.specialPriceDiff(discount);
        boolean available = binding.trade().isAvailable();
        return MerchantOfferProxy.INSTANCE.newInstance(
                firstCost,
                secondCost,
                markedResult.stack(),
                available ? 0 : 1,
                1,
                false,
                specialPriceDiff,
                0,
                0.0F,
                0,
                false
        );
    }

    // 随机组件参与精确匹配, 玩家背包物品不会自动填入展示交易.
    private Object createCost(MarkedStack marked) {
        Object stack = marked.stack(); // NMS ItemStack

        // 版本差异位于 Item holder 的访问入口.
        Object holder = VersionHelper.isOrAbove26_1()
                ? ItemStackProxy.INSTANCE.typeHolder(stack)
                : ItemStackProxy.INSTANCE.getItemHolder(stack);

        Object predicate = DataComponentExactPredicateProxy.INSTANCE.allOf(
                ItemStackProxy.INSTANCE.getComponents(stack)
        );
        return ItemCostProxy.INSTANCE.newInstance(holder, marked.count(), predicate, stack);
    }

    // 复制展示物品并加入会话随机标记, 必需空位用不可见占位保持 offer 索引.
    private MarkedStack mark(ItemStack source, boolean required) {
        ItemStack display;
        if (source.isEmpty()) {
            if (!required) {
                throw new IllegalArgumentException("optional Merchant item must not be marked while empty");
            }
            display = ItemStackProxy.INSTANCE.getBukkitStack(ItemUtils.invisibleBarrier());
        } else {
            display = source.clone();
        }

        // 每次构建使用新值, 客户端不会复用上一次 offers 的输入匹配结果.
        ItemMeta meta = display.getItemMeta();
        meta.getPersistentDataContainer().set(
                this.markerKey,
                PersistentDataType.STRING,
                UUID.randomUUID().toString()
        );
        display.setItemMeta(meta);
        Object stack = ItemStackProxy.INSTANCE.copy(ItemUtils.getItemStackHandle(display)); // NMS ItemStack
        return new MarkedStack(stack, Math.max(1, display.getAmount()));
    }

    // 渲染失败时上报异常并保留该位置最近一次成功快照.
    private ItemStack render(
            @NotNull Item item,
            @NotNull RenderCell renderCell,
            @NotNull ItemStack fallback,
            int tradeIndex,
            @NotNull String role
    ) {
        try {
            return renderCell.render(new RenderCell.Intent.Projected(item.getItemProvider(), item.getPlaceholder(), fallback));
        } catch (Throwable throwable) {
            this.reporter.accept(
                    "Failed to render Merchant trade " + tradeIndex + " " + role,
                    throwable
            );
            return fallback;
        }
    }

    @Override
    public void close(@NotNull InventoryCloseEvent.Reason reason) {
        Throwable failure = this.closeBindings();
        try {
            super.close(reason);
        } catch (RuntimeException | Error throwable) {
            failure = ThrowableUtils.combine(failure, throwable);
        }
        ThrowableUtils.throwIfUnchecked(failure);
    }

    @Override
    public void retire() {
        Throwable failure = this.closeBindings();
        try {
            super.retire();
        } catch (RuntimeException | Error throwable) {
            failure = ThrowableUtils.combine(failure, throwable);
        }
        ThrowableUtils.throwIfUnchecked(failure);
    }

    @Nullable
    private Throwable closeBindings() {
        TradeBindings bindings = this.bindings;
        this.bindings = null;
        if (bindings == null) {
            return null;
        }
        try {
            bindings.close();
            return null;
        } catch (RuntimeException | Error throwable) {
            return throwable;
        }
    }

    static final class OfferMath {
        private OfferMath() {
        }

        // API 正折扣映射为原版负差值, MIN_VALUE 饱和为最大加价.
        static int specialPriceDiff(int discount) {
            return discount == Integer.MIN_VALUE ? Integer.MAX_VALUE : -discount;
        }
    }

    private record MarkedStack(Object stack, int count) {
    }

    private enum GateState {
        PREPARING, // 组装挂载, 忽略准备期通知
        ACTIVE,    // 向当前菜单转发失效
        RETIRED    // 丢弃替换或关闭后的迟到通知
    }

    /**
     * 持有一次菜单会话的全部 Trade 挂载, 并合并它们的失效通知.
     * <p>所有挂载成功后才激活 gate, 退役后按所有权逆序关闭资源.
     */
    static final class TradeBindings implements AutoCloseable {
        private final Runnable invalidator;
        private final List<TradeBinding> entries;
        private final AtomicReference<GateState> gate = new AtomicReference<>(GateState.PREPARING);
        private boolean closed;

        TradeBindings(
                @NotNull List<MerchantWindow.Trade> trades,
                @NotNull Window window,
                @NotNull Runnable invalidator
        ) {
            this.invalidator = invalidator;
            ArrayList<TradeBinding> entries = new ArrayList<>(trades.size());

            // 准备期通知不会投递给尚未发布的候选会话.
            try {
                for (int index = 0; index < trades.size(); index++) {
                    TradeBinding binding = new TradeBinding(trades.get(index), window, this::dirty);
                    entries.add(binding);
                }
            } catch (RuntimeException | Error throwable) {
                // 逆序回滚已取得资源, 清理异常附加到原始失败.
                this.gate.set(GateState.RETIRED);
                TradeBindings.closeEntries(entries, throwable);
                throw throwable;
            }

            // 全部准备成功后发布不可变快照.
            this.entries = List.copyOf(entries);
        }

        void activate() {
            GateState previous = this.gate.compareAndExchange(GateState.PREPARING, GateState.ACTIVE);
            if (previous == GateState.RETIRED) {
                throw new IllegalStateException("Merchant trade bindings have already retired");
            }
        }

        private List<TradeBinding> entries() {
            return this.entries;
        }

        private void dirty() {
            if (this.gate.get() == GateState.ACTIVE) {
                this.invalidator.run();
            }
        }

        void retire() {
            this.gate.set(GateState.RETIRED);
        }

        @Override
        public void close() {
            this.retire();
            if (this.closed) {
                return;
            }
            this.closed = true;

            // 单项失败不打断后续关闭, 最后抛出聚合结果.
            Throwable failure = TradeBindings.closeEntries(this.entries, null);
            ThrowableUtils.throwIfUnchecked(failure);
        }

        // 逆序关闭 TradeBinding 并聚合非受检异常.
        @Nullable
        private static Throwable closeEntries(@NotNull List<TradeBinding> entries, @Nullable Throwable failure) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                try {
                    entries.get(index).close();
                } catch (RuntimeException | Error throwable) {
                    failure = ThrowableUtils.combine(failure, throwable);
                }
            }
            return failure;
        }
    }

    /**
     * 持有一条 Trade 的订阅, 三个 Item 挂载和最近成功结果.
     */
    private static final class TradeBinding implements AutoCloseable {
        // Trade 与失效入口
        private final MerchantWindow.Trade trade;
        private final Runnable invalidator;

        // 订阅与 Item 挂载
        private final Subscription tradeSubscription;
        private final ItemAttachment firstInputAttachment;
        private final ItemAttachment secondInputAttachment;
        private final ItemAttachment resultAttachment;

        // 三个显示位置的渲染状态
        private final RenderCell firstInputRenderCell;
        private final RenderCell secondInputRenderCell;
        private final RenderCell resultRenderCell;

        // 最近成功结果与生命周期
        private ItemStack firstInput = ItemUtils.EMPTY;
        private ItemStack secondInput = ItemUtils.EMPTY;
        private ItemStack result = ItemUtils.EMPTY;
        private boolean closed;

        private TradeBinding(
                @NotNull MerchantWindow.Trade trade,
                @NotNull Window window,
                @NotNull Runnable invalidator
        ) {
            this.trade = trade;
            this.invalidator = invalidator;
            // 交易列表不占容器槽位, 三个位置共用 off-slot 渲染上下文.
            RenderContext renderContext = RenderContext.offSlot(window);
            this.firstInputRenderCell = new RenderCell(
                    renderContext,
                    invalidator,
                    throwable -> SparrowUI.getInstance().handleException("Failed to render asynchronous Merchant first input", throwable)
            );
            this.secondInputRenderCell = new RenderCell(
                    renderContext,
                    invalidator,
                    throwable -> SparrowUI.getInstance().handleException("Failed to render asynchronous Merchant second input", throwable)
            );
            this.resultRenderCell = new RenderCell(
                    renderContext,
                    invalidator,
                    throwable -> SparrowUI.getInstance().handleException("Failed to render asynchronous Merchant result", throwable)
            );
            Subscription tradeSubscription = null;
            ItemAttachment firstInputAttachment = null;
            ItemAttachment secondInputAttachment = null;
            ItemAttachment resultAttachment = null;

            // 三个位置各自挂载, 相同 Item 引用也拥有独立显示生命周期.
            try {
                tradeSubscription = trade.subscribe(ignoredChange -> this.dirtyAll());
                firstInputAttachment = trade.getFirstInput().attach(window, ignoredItem -> this.dirty(this.firstInputRenderCell));
                secondInputAttachment = trade.getSecondInput().attach(window, ignoredItem -> this.dirty(this.secondInputRenderCell));
                resultAttachment = trade.getResult().attach(window, ignoredItem -> this.dirty(this.resultRenderCell));
            } catch (RuntimeException | Error throwable) {
                this.closeRenderCells();
                // 构造中途失败时关闭已经取得的资源.
                TradeBinding.close(
                        resultAttachment,
                        secondInputAttachment,
                        firstInputAttachment,
                        tradeSubscription,
                        throwable
                );
                throw throwable;
            }
            this.tradeSubscription = tradeSubscription;
            this.firstInputAttachment = firstInputAttachment;
            this.secondInputAttachment = secondInputAttachment;
            this.resultAttachment = resultAttachment;
        }

        private ItemStack renderFirstInput(MerchantMenuHandleImpl owner, int tradeIndex) {
            this.firstInput = owner.render(
                    this.trade.getFirstInput(),
                    this.firstInputRenderCell,
                    this.firstInput,
                    tradeIndex,
                    "first input"
            );
            return this.firstInput;
        }

        private ItemStack renderSecondInput(MerchantMenuHandleImpl owner, int tradeIndex) {
            this.secondInput = owner.render(
                    this.trade.getSecondInput(),
                    this.secondInputRenderCell,
                    this.secondInput,
                    tradeIndex,
                    "second input"
            );
            return this.secondInput;
        }

        private ItemStack renderResult(MerchantMenuHandleImpl owner, int tradeIndex) {
            this.result = owner.render(
                    this.trade.getResult(),
                    this.resultRenderCell,
                    this.result,
                    tradeIndex,
                    "result"
            );
            return this.result;
        }

        private MerchantWindow.Trade trade() {
            return this.trade;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.closeRenderCells();
            Throwable failure = TradeBinding.close(
                    this.resultAttachment,
                    this.secondInputAttachment,
                    this.firstInputAttachment,
                    this.tradeSubscription,
                    null
            );
            ThrowableUtils.throwIfUnchecked(failure);
        }

        private void dirty(RenderCell renderCell) {
            renderCell.dirty();
            this.invalidator.run();
        }

        private void dirtyAll() {
            this.firstInputRenderCell.dirty();
            this.secondInputRenderCell.dirty();
            this.resultRenderCell.dirty();
            this.invalidator.run();
        }

        private void closeRenderCells() {
            this.resultRenderCell.close();
            this.secondInputRenderCell.close();
            this.firstInputRenderCell.close();
        }

        // 按所有权逆序关闭, 构造失败留下的空引用直接跳过.
        @Nullable
        private static Throwable close(
                @Nullable ItemAttachment result,
                @Nullable ItemAttachment second,
                @Nullable ItemAttachment first,
                @Nullable Subscription trade,
                @Nullable Throwable failure
        ) {
            failure = TradeBinding.close(result, failure);
            failure = TradeBinding.close(second, failure);
            failure = TradeBinding.close(first, failure);
            return TradeBinding.close(trade, failure);
        }

        @Nullable
        private static Throwable close(@Nullable AutoCloseable closeable, @Nullable Throwable failure) {
            if (closeable == null) {
                return failure;
            }
            try {
                closeable.close();
            } catch (Throwable throwable) {
                failure = ThrowableUtils.combine(failure, throwable);
            }
            return failure;
        }
    }
}
