package net.momirealms.sparrow.ui.internal.menu;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@SuppressWarnings("UnstableApiUsage")
final class MerchantMenuHandleImpl extends PaperMenuHandle implements MerchantMenuHandle {
    private static final int FIRST_INPUT_SLOT = 0;
    private static final int SECOND_INPUT_SLOT = 1;
    private static final int RESULT_SLOT = 2;
    private static final Set<Class<?>> DISCARDED_OUTGOING = Set.of(ClientboundMerchantOffersPacketProxy.CLASS);

    private final BiConsumer<? super String, ? super Throwable> reporter;
    private final NamespacedKey markerKey;
    private final RenderContext firstInputContext;
    private final RenderContext secondInputContext;
    private final RenderContext resultContext;
    private final AtomicLong offerRevision = new AtomicLong();

    private @Nullable TradeBindings bindings;
    private int level;
    private double progress = -1.0;
    private boolean restockMessageEnabled;
    private long offerTick;
    private long committedOfferRevision = -1;
    private long queuedOfferRevision;
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
    public boolean tickOffers() {
        TradeBindings bindings = this.bindings;
        if (bindings != null && bindings.refreshPlan().isDue(++this.offerTick)) {
            this.invalidateOffers();
        }
        return this.offerRevision.get() != this.committedOfferRevision;
    }

    @Override
    public void close(@NotNull InventoryCloseEvent.Reason reason) {
        Throwable failure = this.closeBindings();
        try {
            super.close(reason);
        } catch (RuntimeException | Error throwable) {
            failure = MerchantMenuHandleImpl.combine(failure, throwable);
        }
        ThrowableUtils.throwIfUnchecked(failure);
    }

    @Override
    public void retire() {
        Throwable failure = this.closeBindings();
        try {
            super.retire();
        } catch (RuntimeException | Error throwable) {
            failure = MerchantMenuHandleImpl.combine(failure, throwable);
        }
        ThrowableUtils.throwIfUnchecked(failure);
    }

    @Override
    @NotNull
    protected Set<Class<?>> discardedClientboundPackets() {
        return DISCARDED_OUTGOING;
    }

    @Override
    protected void submitPackets(@NotNull List<Object> outgoing, boolean forceFull) {
        long revision = this.offerRevision.get();
        this.offersQueued = forceFull || revision != this.committedOfferRevision;
        if (!this.offersQueued) {
            return;
        }
        this.queuedOfferRevision = revision;
        outgoing.add(this.createOffersPacket());
    }

    @Override
    protected void commitPackets() {
        if (this.offersQueued) {
            this.committedOfferRevision = this.queuedOfferRevision;
            this.offersQueued = false;
        }
    }

    private void invalidateOffers() {
        this.offerRevision.incrementAndGet();
    }

    private Object createOffersPacket() {
        TradeBindings bindings = this.bindings;
        List<TradeBinding> entries = bindings == null ? List.of() : bindings.entries();
        ArrayList<Object> offers = new ArrayList<>(entries.size()); // NMS MerchantOffer 列表
        for (int index = 0; index < entries.size(); index++) {
            offers.add(this.createOffer(entries.get(index), index));
        }

        Object merchantOffers = MerchantOffersProxy.INSTANCE.newInstance(offers);
        boolean showProgress = this.progress != -1.0;
        int villagerXp = showProgress
                ? (int) Math.floor(VillagerDataProxy.INSTANCE.getMaxXpPerLevel(this.level) * this.progress)
                : 0;
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
        ItemStack firstInput = binding.renderFirstInput(this, tradeIndex);
        ItemStack secondInput = binding.renderSecondInput(this, tradeIndex);
        ItemStack result = binding.renderResult(this, tradeIndex);

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

    private Object createCost(MarkedStack marked) {
        Object stack = marked.stack(); // NMS ItemStack
        Object holder = VersionHelper.isOrAbove26_1()
                ? ItemStackProxy.INSTANCE.typeHolder(stack)
                : ItemStackProxy.INSTANCE.getItemHolder(stack);
        Object predicate = DataComponentExactPredicateProxy.INSTANCE.allOf(
                ItemStackProxy.INSTANCE.getComponents(stack)
        );
        return ItemCostProxy.INSTANCE.newInstance(holder, marked.count(), predicate, stack);
    }

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

    @Nullable
    private Throwable closeBindings() {
        TradeBindings bindings = this.bindings;
        this.bindings = null;
        if (bindings == null) {
            return null;
        }
        bindings.retire();
        try {
            bindings.close();
            return null;
        } catch (RuntimeException | Error throwable) {
            return throwable;
        }
    }

    @Nullable
    private static Throwable combine(@Nullable Throwable first, @NotNull Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private enum GateState {
        PREPARING,
        ACTIVE,
        RETIRED
    }

    static final class OfferMath {

        private OfferMath() {
        }

        static int specialPriceDiff(int discount) {
            return discount == Integer.MIN_VALUE ? Integer.MAX_VALUE : -discount;
        }
    }

    private record MarkedStack(Object stack, int count) {
    }

    static final class TradeBindings implements AutoCloseable {
        private final Runnable invalidator;
        private final List<TradeBinding> entries;
        private final RefreshPlan refreshPlan;
        private final AtomicReference<GateState> gate = new AtomicReference<>(GateState.PREPARING);
        private boolean closed;

        TradeBindings(
                @NotNull List<MerchantWindow.Trade> trades,
                @NotNull Runnable invalidator
        ) {
            this.invalidator = invalidator;
            ArrayList<TradeBinding> entries = new ArrayList<>(trades.size());
            RefreshPlan refreshPlan = RefreshPlan.none();
            try {
                for (int index = 0; index < trades.size(); index++) {
                    TradeBinding binding = new TradeBinding(trades.get(index), this::invalidate);
                    entries.add(binding);
                    refreshPlan = refreshPlan.or(binding.refreshPlan());
                }
            } catch (RuntimeException | Error throwable) {
                this.gate.set(GateState.RETIRED);
                TradeBindings.closeEntries(entries, throwable);
                throw throwable;
            }
            this.entries = List.copyOf(entries);
            this.refreshPlan = refreshPlan;
        }

        private List<TradeBinding> entries() {
            return this.entries;
        }

        RefreshPlan refreshPlan() {
            return this.refreshPlan;
        }

        void activate() {
            GateState previous = this.gate.compareAndExchange(GateState.PREPARING, GateState.ACTIVE);
            if (previous == GateState.RETIRED) {
                throw new IllegalStateException("Merchant trade bindings have already retired");
            }
        }

        void retire() {
            this.gate.set(GateState.RETIRED);
        }

        private void invalidate() {
            if (this.gate.get() == GateState.ACTIVE) {
                this.invalidator.run();
            }
        }

        @Override
        public void close() {
            this.retire();
            if (this.closed) {
                return;
            }
            this.closed = true;
            Throwable failure = TradeBindings.closeEntries(this.entries, null);
            ThrowableUtils.throwIfUnchecked(failure);
        }

        @Nullable
        private static Throwable closeEntries(
                @NotNull List<TradeBinding> entries,
                @Nullable Throwable failure
        ) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                try {
                    entries.get(index).close();
                } catch (RuntimeException | Error throwable) {
                    failure = MerchantMenuHandleImpl.combine(failure, throwable);
                }
            }
            return failure;
        }
    }

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
            try {
                tradeSubscription = trade.subscribe(ignoredChange -> invalidator.run());
                firstInputAttachment = trade.getFirstInput().attach(ignoredItem -> invalidator.run());
                secondInputAttachment = trade.getSecondInput().attach(ignoredItem -> invalidator.run());
                resultAttachment = trade.getResult().attach(ignoredItem -> invalidator.run());
            } catch (RuntimeException | Error throwable) {
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
            this.refreshPlan = firstInputAttachment.refreshPlan()
                    .or(secondInputAttachment.refreshPlan())
                    .or(resultAttachment.refreshPlan());
        }

        private MerchantWindow.Trade trade() {
            return this.trade;
        }

        private RefreshPlan refreshPlan() {
            return this.refreshPlan;
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
                failure = MerchantMenuHandleImpl.combine(failure, throwable);
            }
            return failure;
        }
    }
}
