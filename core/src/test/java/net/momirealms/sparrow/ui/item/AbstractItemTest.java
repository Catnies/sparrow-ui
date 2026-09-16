package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.item.guard.ItemGuard;
import net.momirealms.sparrow.ui.item.guard.ItemGuards;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.item.provider.RenderContext;
import net.momirealms.sparrow.ui.state.KeyedSignal;
import net.momirealms.sparrow.ui.state.MutableKeyedSignal;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.RenderCell;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AbstractItemTest {

    private ServerMock server;
    private Player player;

    @BeforeEach
    void setUp() {
        this.server = MockBukkit.mock();
        this.player = this.server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void declaredDependencyInvalidatesAttachedItem() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractItem item = new DependentItem(dependent -> dependent.dependsOn(season));
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        season.set("summer");

        assertEquals(1, invalidations.get());
    }

    @Test
    void eachAttachmentIsInvalidatedIndependently() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractItem item = new DependentItem(dependent -> dependent.dependsOn(season));
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        ItemAttachment firstAttachment = AttachSupport.attach(item, ignoredItem -> first.incrementAndGet());
        AttachSupport.attach(item, ignoredItem -> second.incrementAndGet());
        season.set("summer");

        assertEquals(1, first.get());
        assertEquals(1, second.get());
        firstAttachment.close();
        season.set("autumn");

        assertEquals(1, first.get());
        assertEquals(2, second.get());
    }

    @Test
    void detachStopsDependencyInvalidation() {
        MutableSignal<String> season = Signal.of("spring");
        AbstractItem item = new DependentItem(dependent -> dependent.dependsOn(season));
        AtomicInteger invalidations = new AtomicInteger();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> invalidations.incrementAndGet());
        attachment.close();
        season.set("summer");

        assertEquals(0, invalidations.get());
    }

    @Test
    void keyedDependencyOnlyInvalidatesTheMatchingViewer() {
        MutableKeyedSignal<UUID, Integer> coins = KeyedSignal.of(key -> 0);
        AbstractItem item = new DependentItem(dependent -> dependent.dependsOn(coins, Player::getUniqueId));
        Player alice = AttachSupport.player();
        Player bob = AttachSupport.player();
        AtomicInteger aliceInvalidations = new AtomicInteger();
        AtomicInteger bobInvalidations = new AtomicInteger();
        item.attach(AttachSupport.window(alice), ignoredItem -> aliceInvalidations.incrementAndGet());
        item.attach(AttachSupport.window(bob), ignoredItem -> bobInvalidations.incrementAndGet());
        coins.set(alice.getUniqueId(), 100);

        assertEquals(1, aliceInvalidations.get());
        assertEquals(0, bobInvalidations.get());
    }

    private static final class DependentItem extends AbstractItem {
        private DependentItem(Consumer<DependentItem> declaration) {
            declaration.accept(this);
        }
        @Override
        protected @NonNull CompletableFuture<ItemStack> render(@NonNull RenderContext context) {
            return CompletableFuture.completedFuture(ItemStack.empty());
        }
    }

    @Test
    void statefulItemRendersAndNotifiesAttachedSlots() {
        CounterItem item = new CounterItem();
        WindowStub window = new WindowStub(this.player);
        RenderContext context = new RenderContext(window, 0);
        ItemProvider provider = item.getItemProvider();
        AtomicInteger notifications = new AtomicInteger();
        ItemAttachment first = AttachSupport.attach(item, notifiedItem -> {
            assertSame(item, notifiedItem);
            notifications.incrementAndGet();
        });
        ItemAttachment second = AttachSupport.attach(item, notifiedItem -> {
            assertSame(item, notifiedItem);
            notifications.incrementAndGet();
        });

        assertSame(provider, item.getItemProvider());
        assertEquals(1, provider.provide(context).join().getAmount());
        item.handleClick(click(this.player, window));

        assertEquals(1, item.count.get());
        assertEquals(2, notifications.get());
        assertEquals(2, provider.provide(context).join().getAmount());
        first.close();
        item.handleClick(click(this.player, window));

        assertEquals(3, notifications.get());
        second.close();
        item.handleClick(click(this.player, window));

        assertEquals(3, notifications.get());
    }

    @Test
    void subclassRenderCanReturnAPendingFutureDirectly() {
        CompletableFuture<ItemStack> result = new CompletableFuture<>();
        AbstractItem item = new AbstractItem() {
            @Override
            protected @NonNull CompletableFuture<ItemStack> render(@NonNull RenderContext context) {
                return result;
            }
        };
        RenderContext context = new RenderContext(new WindowStub(this.player), 0);

        assertSame(result, item.getItemProvider().provide(context));
    }

    @Test
    void placeholderIsUsedOnlyBeforeTheFirstSuccessfulResult() {
        CompletableFuture<ItemStack> first = new CompletableFuture<>();
        CompletableFuture<ItemStack> second = new CompletableFuture<>();
        ItemStack placeholder = new ItemStack(Material.PAPER);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger placeholderRenders = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        AbstractItem item = new AbstractItem() {
            @Override
            protected @NonNull ItemStack placeholder(@NonNull RenderContext context) {
                placeholderRenders.incrementAndGet();
                return placeholder;
            }
            @Override
            protected @NonNull CompletableFuture<ItemStack> render(@NonNull RenderContext context) {
                return requests.getAndIncrement() == 0 ? first : second;
            }
        };
        RenderContext context = new RenderContext(new WindowStub(this.player), 0);
        RenderCell cell = new RenderCell(context, invalidations::incrementAndGet, throwable -> { });

        assertSame(placeholder, cell.render(intent(item)));
        assertEquals(1, placeholderRenders.get());
        ItemStack rendered = new ItemStack(Material.DIAMOND);
        first.complete(rendered);

        assertEquals(1, invalidations.get());
        assertSame(rendered, cell.render(intent(item)));
        cell.dirty();

        assertSame(rendered, cell.render(intent(item)));
        assertEquals(2, requests.get());
        assertEquals(1, placeholderRenders.get());
        cell.close();
    }

    @Test
    void completedRenderFutureSkipsPlaceholderInTheSameRender() {
        ItemStack rendered = new ItemStack(Material.DIAMOND);
        AtomicInteger placeholderRenders = new AtomicInteger();
        AbstractItem item = new AbstractItem() {
            @Override
            protected @NonNull ItemStack placeholder(@NonNull RenderContext context) {
                placeholderRenders.incrementAndGet();
                return new ItemStack(Material.PAPER);
            }
            @Override
            protected @NonNull CompletableFuture<ItemStack> render(@NonNull RenderContext context) {
                return CompletableFuture.completedFuture(rendered);
            }
        };
        RenderContext context = new RenderContext(new WindowStub(this.player), 0);
        RenderCell cell = new RenderCell(context, () -> { }, throwable -> { });

        assertSame(rendered, cell.render(intent(item)));
        assertEquals(0, placeholderRenders.get());
        cell.close();
    }

    private static RenderCell.Intent intent(Item item) {
        return new RenderCell.Intent.Projected(item.getItemProvider(), item.getPlaceholder(), ItemStack.empty());
    }

    @Test
    void placeholderProviderIsStableAcrossCalls() {
        AbstractItem item = new AbstractItem() {
            @Override
            protected @NonNull CompletableFuture<ItemStack> render(@NonNull RenderContext context) {
                return CompletableFuture.completedFuture(ItemStack.empty());
            }
        };

        assertSame(item.getPlaceholder(), item.getPlaceholder());
    }

    @Test
    void commonGuardCanGateDirectClickHandler() {
        CounterItem item = new CounterItem();
        WindowStub window = new WindowStub(this.player);
        AtomicInteger notifications = new AtomicInteger();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredItem -> notifications.incrementAndGet());
        item.clickGuard = ItemGuards.gameMode(GameMode.CREATIVE);
        this.player.setGameMode(GameMode.SURVIVAL);
        item.handleClick(click(this.player, window));

        assertEquals(0, item.count.get());
        assertEquals(1, item.rejections.get());
        assertEquals(0, notifications.get());
        this.player.setGameMode(GameMode.CREATIVE);
        item.handleClick(click(this.player, window));
        attachment.close();

        assertEquals(1, item.count.get());
        assertEquals(1, notifications.get());
    }

    @Test
    void subclassCanOverrideDragAndBundleSelectDirectly() {
        CounterItem item = new CounterItem();
        WindowStub window = new WindowStub(this.player);
        ItemDrag drag = new ItemDrag(
                ClickType.LEFT,
                this.player,
                window,
                ItemStack.empty(),
                0,
                List.of(new ItemDrag.Stop(0))
        );
        BundleSelectClick select = new BundleSelectClick(this.player, window, 0, 1);
        item.handleDrag(drag);
        item.handleBundleSelect(select);

        assertSame(drag, item.lastDrag);
        assertSame(select, item.lastSelect);
    }

    private static ItemClick click(Player player, WindowStub window) {
        return new ItemClick(player, ClickType.LEFT, window, ItemStack.empty(), 0);
    }

    private static final class CounterItem extends AbstractItem {
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicInteger rejections = new AtomicInteger();
        private ItemGuard<ItemClick> clickGuard = (ignoredItem, ignoredClick) -> true;
        private ItemDrag lastDrag;
        private BundleSelectClick lastSelect;
        @Override
        protected @NonNull CompletableFuture<ItemStack> render(@NonNull RenderContext context) {
            return CompletableFuture.completedFuture(new ItemStack(Material.DIAMOND, this.count.get() + 1));
        }
        @Override
        public void handleClick(ItemClick click) {
            if (!this.clickGuard.test(this, click)) {
                this.rejections.incrementAndGet();
                return;
            }
            this.count.incrementAndGet();
            this.notifyWindows();
        }
        @Override
        public void handleDrag(ItemDrag drag) {
            this.lastDrag = drag;
        }
        @Override
        public void handleBundleSelect(BundleSelectClick select) {
            this.lastSelect = select;
        }
    }
}
