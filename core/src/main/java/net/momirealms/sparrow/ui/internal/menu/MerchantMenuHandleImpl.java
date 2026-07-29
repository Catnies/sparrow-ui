package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.internal.network.PacketListener;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.RefreshPlan;
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

/**
 * 将 {@link MerchantWindow} 的状态投影为原版 Merchant 客户端协议.
 * <p>offers 只承担展示职责, 不参与真实输入匹配, 结果生成或交易次数计算. 每次菜单会话独立持有
 * Trade 与 Item 挂载; 任意线程到达的失效只推进 revision, 渲染和发包仍由玩家实体线程完成.
 */
@SuppressWarnings("UnstableApiUsage")
final class MerchantMenuHandleImpl extends ContainerMenuHandle implements MerchantMenuHandle {
    private static final int FIRST_INPUT_SLOT = 0;
    private static final int SECOND_INPUT_SLOT = 1;
    private static final int RESULT_SLOT = 2;
    private static final Set<Class<?>> DISCARDED_OUTGOING = Set.of(ClientboundMerchantOffersPacketProxy.CLASS);

    private final BiConsumer<? super String, ? super Throwable> reporter;
    private final NamespacedKey markerKey;
    private final RenderContext firstInputContext;
    private final RenderContext secondInputContext;
    private final RenderContext resultContext;

    private @Nullable TradeBindings bindings;
    private int level;
    private double progress = -1.0;
    private boolean restockMessageEnabled;
    private boolean selectionReconciliationPending;
    private boolean selectionContentRecoveryOnly;
    private boolean resultReconciliationPending;

    private final AtomicLong offerRevision = new AtomicLong(); // 任意线程只递增此值, 实际渲染仍在实体线程
    private long offerTick;
    private long committedOfferRevision = -1; // 最近成功进入网络发送批次的 revision
    private long queuedOfferRevision;
    private @Nullable Object committedOffersPacket;
    private @Nullable Object queuedOffersPacket;
    private boolean offersQueued;

    MerchantMenuHandleImpl(
            @NotNull PacketListener packets,
            @NotNull Player player,
            long generation,
            @NotNull MerchantWindow window,
            @NotNull BiConsumer<? super String, ? super Throwable> reporter
    ) {
        super(
                packets,
                player,
                MenuTypeProxy.MERCHANT,
                InventoryType.MERCHANT,
                org.bukkit.inventory.MenuType.MERCHANT,
                3,
                generation
        );
        this.reporter = reporter;
        this.markerKey = new NamespacedKey(SparrowUI.getInstance().getPlugin(), "merchant_offer");
        this.firstInputContext = new RenderContext(window, FIRST_INPUT_SLOT);
        this.secondInputContext = new RenderContext(window, SECOND_INPUT_SLOT);
        this.resultContext = new RenderContext(window, RESULT_SLOT);
    }

    @Override
    public void setLevel(int level) {
        if (this.level == level) {
            return;
        }
        this.level = level;
        this.invalidateOffers();
    }

    @Override
    public void setProgress(double progress) {
        if (Double.compare(this.progress, progress) == 0) {
            return;
        }
        this.progress = progress;
        this.invalidateOffers();
    }

    @Override
    public void setRestockMessageEnabled(boolean enabled) {
        if (this.restockMessageEnabled == enabled) {
            return;
        }
        this.restockMessageEnabled = enabled;
        this.invalidateOffers();
    }

    /**
     * {@inheritDoc}
     * <p>先完整创建候选挂载, 成功后才退役旧挂载. 因此准备失败不会破坏当前 offers.
     */
    @Override
    public void setTrades(@NotNull List<MerchantWindow.Trade> trades) {
        TradeBindings candidate = new TradeBindings(trades, this::invalidateOffers);
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
        this.invalidateOffers();
    }

    @Override
    public void invalidateClientContents() {
        this.selectionReconciliationPending = true;
    }

    @Override
    public boolean tickOffers() {
        TradeBindings bindings = this.bindings;
        if (bindings != null && bindings.refreshPlan().isDue(++this.offerTick)) {
            this.invalidateOffers();
        }
        return this.offerRevision.get() != this.committedOfferRevision;
    }

    private void invalidateOffers() {
        this.offerRevision.incrementAndGet();
    }

    @Override
    @NotNull
    protected Set<Class<?>> discardedClientboundPackets() {
        return DISCARDED_OUTGOING;
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

    /**
     * 选择交易没有 changed-slots 可供吸收, 必须用完整内容覆盖客户端本地搬运到未知背包槽的物品.
     */
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

    /**
     * 标题重开已经携带完整内容, 同样可以提交待处理的选择纠正.
     */
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

    /**
     * {@inheritDoc}
     * <p>本批次只捕获一次 revision. 发送期间到达的新失效会保留为更大的 revision,
     * 由后续 tick 再次提交.
     */
    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        long revision = this.offerRevision.get();
        // 选择纠正借用完整内容包恢复全部槽位, 但不应因此重新渲染未变化的 offers
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

    /**
     * 返回本轮将成为客户端当前状态的 offers 包.
     */
    private Object currentOffersPacket() {
        Object packet = this.offersQueued ? this.queuedOffersPacket : this.committedOffersPacket;
        if (packet == null) {
            throw new IllegalStateException("Merchant offers packet is unavailable");
        }
        return packet;
    }

    /**
     * 将当前 Trade 挂载渲染为一份完整的 offers 数据包.
     */
    private Object createOffersPacket() {
        TradeBindings bindings = this.bindings;
        List<TradeBinding> entries = bindings == null ? List.of() : bindings.entries();

        // offers 与 entries 保持一一对应, 单项渲染失败也不能改变客户端索引
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

    /**
     * 将一条 Trade 转换为纯展示的原版 MerchantOffer.
     */
    private Object createOffer(TradeBinding binding, int tradeIndex) {
        // 每个位置缓存最近一次成功结果, 渲染异常时由 render 保留旧快照
        ItemStack firstInput = binding.renderFirstInput(this, tradeIndex);
        ItemStack secondInput = binding.renderSecondInput(this, tradeIndex);
        ItemStack result = binding.renderResult(this, tradeIndex);

        // 只标记协议展示副本, 不修改 Trade Item 提供的真实物品
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

    /**
     * 创建要求完整组件精确匹配的 ItemCost, 阻止真实背包物品自动填入展示交易.
     */
    private Object createCost(MarkedStack marked) {
        Object stack = marked.stack(); // NMS ItemStack

        // 1.21.8 与 26.1+ 只在 Item holder 的访问方法上存在差异
        Object holder = VersionHelper.isOrAbove26_1()
                ? ItemStackProxy.INSTANCE.typeHolder(stack)
                : ItemStackProxy.INSTANCE.getItemHolder(stack);

        // predicate 包含随机 CUSTOM_DATA, 客户端背包中的真实物品无法精确匹配
        Object predicate = DataComponentExactPredicateProxy.INSTANCE.allOf(
                ItemStackProxy.INSTANCE.getComponents(stack)
        );
        return ItemCostProxy.INSTANCE.newInstance(holder, marked.count(), predicate, stack);
    }

    /**
     * 复制展示物品并写入会话内随机标记. 必需位置为空时使用不可见占位以保持 offer 索引.
     */
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

        // 每次构建都生成新值, 防止客户端复用上一次 offers 的输入匹配结果
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

    /**
     * 渲染一个 Trade Item. 失败时上报异常并保留该位置最近一次成功快照.
     */
    private ItemStack render(
            @NotNull Item item,
            @NotNull RenderContext context,
            @NotNull ItemStack fallback,
            int tradeIndex,
            @NotNull String role
    ) {
        try {
            return ItemUtils.copyOrEmpty(item.getItemProvider().provide(context));
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

        /**
         * 把 API 的正折扣映射为原版负差值. {@link Integer#MIN_VALUE} 无法取反, 因此饱和为最大加价.
         */
        static int specialPriceDiff(int discount) {
            return discount == Integer.MIN_VALUE ? Integer.MAX_VALUE : -discount;
        }
    }

    private record MarkedStack(Object stack, int count) {
    }

    private enum GateState {
        PREPARING, // 正在组装挂载, 忽略准备期通知
        ACTIVE, // 向当前菜单转发失效
        RETIRED // 忽略替换或关闭后的迟到通知
    }

    /**
     * 持有一次菜单会话内的全部 Trade 挂载, 并把它们合并为一个刷新计划.
     * <p>构造阶段保持 PREPARING gate, 只有全部挂载成功后才激活. 替换或关闭时先退役 gate,
     * 再按所有权逆序释放资源.
     */
    static final class TradeBindings implements AutoCloseable {
        private final Runnable invalidator;
        private final List<TradeBinding> entries;
        private final RefreshPlan refreshPlan;
        private final AtomicReference<GateState> gate = new AtomicReference<>(GateState.PREPARING);
        private boolean closed;

        TradeBindings(@NotNull List<MerchantWindow.Trade> trades, @NotNull Runnable invalidator) {
            this.invalidator = invalidator;
            ArrayList<TradeBinding> entries = new ArrayList<>(trades.size());
            RefreshPlan refreshPlan = RefreshPlan.none();

            // 准备期通知被 gate 拦截, 不会让尚未发布的候选会话变脏
            try {
                for (int index = 0; index < trades.size(); index++) {
                    TradeBinding binding = new TradeBinding(trades.get(index), this::invalidate);
                    entries.add(binding);
                    refreshPlan = refreshPlan.or(binding.refreshPlan());
                }
            } catch (RuntimeException | Error throwable) {
                // 逆序回滚已经取得的资源, 并把关闭异常附加到原始失败
                this.gate.set(GateState.RETIRED);
                TradeBindings.closeEntries(entries, throwable);
                throw throwable;
            }

            // 全部准备成功后才发布不可修改快照
            this.entries = List.copyOf(entries);
            this.refreshPlan = refreshPlan;
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

        RefreshPlan refreshPlan() {
            return this.refreshPlan;
        }

        private void invalidate() {
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

            // 继续关闭其余条目, 最后统一抛出首个失败
            Throwable failure = TradeBindings.closeEntries(this.entries, null);
            ThrowableUtils.throwIfUnchecked(failure);
        }

        /**
         * 按创建顺序的反方向关闭 TradeBinding, 同时聚合所有非受检异常.
         */
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
     * 持有一条 Trade 的订阅, 三个 Item 挂载及最近一次成功渲染结果.
     */
    private static final class TradeBinding implements AutoCloseable {
        private final MerchantWindow.Trade trade;
        private final Subscription tradeSubscription;
        private final ItemAttachment firstInputAttachment;
        private final ItemAttachment secondInputAttachment;
        private final ItemAttachment resultAttachment;
        private final RefreshPlan refreshPlan;

        private ItemStack firstInput = ItemStack.empty();
        private ItemStack secondInput = ItemStack.empty();
        private ItemStack result = ItemStack.empty();
        private boolean closed;

        private TradeBinding(@NotNull MerchantWindow.Trade trade, @NotNull Runnable invalidator) {
            this.trade = trade;
            Subscription tradeSubscription = null;
            ItemAttachment firstInputAttachment = null;
            ItemAttachment secondInputAttachment = null;
            ItemAttachment resultAttachment = null;

            // 三个 Item 独立挂载, 相同 Item 引用也拥有独立的显示生命周期
            try {
                tradeSubscription = trade.subscribe(ignoredChange -> invalidator.run());
                firstInputAttachment = trade.getFirstInput().attach(ignoredItem -> invalidator.run());
                secondInputAttachment = trade.getSecondInput().attach(ignoredItem -> invalidator.run());
                resultAttachment = trade.getResult().attach(ignoredItem -> invalidator.run());
            } catch (RuntimeException | Error throwable) {
                // 构造中途失败时只关闭已经取得的资源
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

            // 周期计划只决定何时重渲染, 主动失效仍通过各自订阅立即推进 revision
            this.refreshPlan = firstInputAttachment.refreshPlan()
                    .or(secondInputAttachment.refreshPlan())
                    .or(resultAttachment.refreshPlan());
        }

        private ItemStack renderFirstInput(MerchantMenuHandleImpl owner, int tradeIndex) {
            this.firstInput = owner.render(
                    this.trade.getFirstInput(),
                    owner.firstInputContext,
                    this.firstInput,
                    tradeIndex,
                    "first input"
            );
            return this.firstInput;
        }

        private ItemStack renderSecondInput(MerchantMenuHandleImpl owner, int tradeIndex) {
            this.secondInput = owner.render(
                    this.trade.getSecondInput(),
                    owner.secondInputContext,
                    this.secondInput,
                    tradeIndex,
                    "second input"
            );
            return this.secondInput;
        }

        private ItemStack renderResult(MerchantMenuHandleImpl owner, int tradeIndex) {
            this.result = owner.render(
                    this.trade.getResult(),
                    owner.resultContext,
                    this.result,
                    tradeIndex,
                    "result"
            );
            return this.result;
        }

        private MerchantWindow.Trade trade() {
            return this.trade;
        }

        private RefreshPlan refreshPlan() {
            return this.refreshPlan;
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            Throwable failure = TradeBinding.close(
                    this.resultAttachment,
                    this.secondInputAttachment,
                    this.firstInputAttachment,
                    this.tradeSubscription,
                    null
            );
            ThrowableUtils.throwIfUnchecked(failure);
        }

        /**
         * 按结果, 第二输入, 第一输入, Trade 订阅的顺序关闭, 兼容构造失败留下的空引用.
         */
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
