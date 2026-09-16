package net.momirealms.sparrow.ui.item.guard;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.item.AttachSupport;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemAttachment;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.ObservableItem;
import net.momirealms.sparrow.ui.item.click.BundleSelectClick;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.item.click.ItemInteraction;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemThrottleTest {

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
    void unthrottledItemDispatchesClicksDirectly() {
        AtomicInteger clicks = new AtomicInteger();
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .build();
        ItemClick click = click(this.player);
        item.handleClick(click);
        item.handleClick(click);

        assertEquals(2, clicks.get());
    }

    @Test
    void builderInteractionFieldsStartNullAndKeepTheFirstComponentsDirectly() throws ReflectiveOperationException {
        ItemBuilder builder = Item.builder();
        Field clickGuard = field(ItemBuilder.class, "clickGuard");
        Field dragGuard = field(ItemBuilder.class, "dragGuard");
        Field bundleSelectGuard = field(ItemBuilder.class, "bundleSelectGuard");
        Field clickHandler = field(ItemBuilder.class, "clickHandler");
        Field dragHandler = field(ItemBuilder.class, "dragHandler");
        Field bundleHandler = field(ItemBuilder.class, "bundleHandler");

        assertNull(clickGuard.get(builder));
        assertNull(dragGuard.get(builder));
        assertNull(bundleSelectGuard.get(builder));
        assertNull(clickHandler.get(builder));
        assertNull(dragHandler.get(builder));
        assertNull(bundleHandler.get(builder));
        ItemGuard<ItemClick> click = (ignoredItem, ignoredClick) -> true;
        ItemGuard<ItemDrag> drag = (ignoredItem, ignoredDrag) -> true;
        ItemGuard<BundleSelectClick> select = (ignoredItem, ignoredSelect) -> true;
        BiConsumer<Item, ItemClick> clickAction = (ignoredItem, ignoredClick) -> { };
        BiConsumer<Item, ItemDrag> dragAction = (ignoredItem, ignoredDrag) -> { };
        BiConsumer<Item, BundleSelectClick> bundleAction = (ignoredItem, ignoredSelect) -> { };
        builder.addClickGuard(click);
        builder.addDragGuard(drag);
        builder.addBundleSelectGuard(select);
        builder.addClickHandler(clickAction);
        builder.addDragHandler(dragAction);
        builder.addBundleSelectHandler(bundleAction);

        assertSame(click, clickGuard.get(builder));
        assertSame(drag, dragGuard.get(builder));
        assertSame(select, bundleSelectGuard.get(builder));
        assertSame(clickAction, clickHandler.get(builder));
        assertSame(dragAction, dragHandler.get(builder));
        assertSame(bundleAction, bundleHandler.get(builder));
    }

    @Test
    void handlerlessItemKeepsNullHandlersAtRuntime() throws ReflectiveOperationException {
        ObservableItem item = Item.builder().build();

        assertNull(field(item.getClass(), "clickHandler").get(item));
        assertNull(field(item.getClass(), "dragHandler").get(item));
        assertNull(field(item.getClass(), "bundleHandler").get(item));
        WindowStub window = new WindowStub(this.player);
        item.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 0));
        item.handleDrag(new ItemDrag(ClickType.LEFT, this.player, window, ItemStack.empty(), 0, List.of(new ItemDrag.Stop(0))));
        item.handleBundleSelect(new BundleSelectClick(this.player, window, 0, 1));
    }

    @Test
    void unguardedItemKeepsNullGuardsAtRuntime() throws ReflectiveOperationException {
        List<String> calls = new ArrayList<>();
        ObservableItem item = Item.builder()
                .addClickHandler(ignoredClick -> calls.add("click-1"))
                .addClickHandler(ignoredClick -> calls.add("click-2"))
                .addDragHandler(ignoredDrag -> calls.add("drag-1"))
                .addDragHandler(ignoredDrag -> calls.add("drag-2"))
                .addBundleSelectHandler(ignoredSelect -> calls.add("bundle-1"))
                .addBundleSelectHandler(ignoredSelect -> calls.add("bundle-2"))
                .build();

        assertNull(field(item.getClass(), "clickGuard").get(item));
        assertNull(field(item.getClass(), "dragGuard").get(item));
        assertNull(field(item.getClass(), "bundleSelectGuard").get(item));
        WindowStub window = new WindowStub(this.player);
        item.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 0));
        item.handleDrag(new ItemDrag(ClickType.LEFT, this.player, window, ItemStack.empty(), 0, List.of(new ItemDrag.Stop(0))));
        item.handleBundleSelect(new BundleSelectClick(this.player, window, 0, 1));

        assertEquals(List.of("click-1", "click-2", "drag-1", "drag-2", "bundle-1", "bundle-2"), calls);
    }

    @Test
    void typedGuardsCanGateAllItemHandlers() {
        AtomicInteger guardCalls = new AtomicInteger();
        List<String> calls = new ArrayList<>();
        ObservableItem item = Item.builder()
                .addClickGuard(windowSlotGuard(guardCalls), click -> calls.add("click-rejected:" + click.windowSlot()))
                .addClickHandler(click -> calls.add("click:" + click.windowSlot()))
                .addDragGuard(windowSlotGuard(guardCalls), drag -> calls.add("drag-rejected:" + drag.windowSlot()))
                .addDragHandler(drag -> calls.add("drag:" + drag.windowSlot()))
                .addBundleSelectGuard(windowSlotGuard(guardCalls), select -> calls.add("bundle-rejected:" + select.windowSlot()))
                .addBundleSelectHandler(select -> calls.add("bundle:" + select.windowSlot()))
                .build();
        WindowStub window = new WindowStub(this.player);
        List<ItemDrag.Stop> path = List.of(
                new ItemDrag.Stop(0),
                new ItemDrag.Stop(1)
        );
        item.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 0));
        item.handleClick(new ItemClick(this.player, ClickType.LEFT, window, ItemStack.empty(), 1));
        item.handleDrag(new ItemDrag(ClickType.LEFT, this.player, window, ItemStack.empty(), 0, path));
        item.handleDrag(new ItemDrag(ClickType.LEFT, this.player, window, ItemStack.empty(), 1, path));
        item.handleBundleSelect(new BundleSelectClick(this.player, window, 0, 2));
        item.handleBundleSelect(new BundleSelectClick(this.player, window, 1, 2));

        assertEquals(6, guardCalls.get());
        assertEquals(List.of(
                "click:0",
                "click-rejected:1",
                "drag:0",
                "drag-rejected:1",
                "bundle:0",
                "bundle-rejected:1"
        ), calls);
    }

    @Test
    void throttledItemRejectsClicksWithinInterval() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickGuard(ItemGuards.throttle(60_000), ignoredClick -> rejections.incrementAndGet())
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .build();
        ItemClick click = click(this.player);
        item.handleClick(click);
        item.handleClick(click);
        item.handleClick(click);

        assertEquals(1, clicks.get());
        assertEquals(2, rejections.get());
    }

    @Test
    void throttledItemAcceptsClickAgainAfterIntervalExpires() {
        AtomicLong time = new AtomicLong();
        AtomicInteger clicks = new AtomicInteger();
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickGuard(new ThrottleGuard(10, time::get))
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .build();
        ItemClick click = click(this.player);
        item.handleClick(click);
        time.set(10);
        item.handleClick(click);

        assertEquals(2, clicks.get());
    }

    @Test
    void rejectedClickDoesNotNotifyObservers() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger notifications = new AtomicInteger();
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickGuard(ItemGuards.throttle(60_000))
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .updateOnClick()
                .build();
        ItemAttachment attachment = AttachSupport.attach(item, ignoredInvalidation -> notifications.incrementAndGet());
        ItemClick click = click(this.player);
        item.handleClick(click);
        item.handleClick(click);

        assertEquals(1, clicks.get());
        assertEquals(1, notifications.get());
        attachment.close();
    }

    @Test
    void differentPlayersThrottleIndependently() {
        Player other = this.server.addPlayer();
        AtomicInteger clicks = new AtomicInteger();
        ObservableItem item = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickGuard(ItemGuards.throttle(60_000))
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .build();
        item.handleClick(click(this.player));
        item.handleClick(click(other));

        assertEquals(2, clicks.get());
    }

    @Test
    void throttleRejectsNonPositiveInterval() {
        assertThrows(IllegalArgumentException.class, () -> ItemGuards.throttle(0));
        assertThrows(IllegalArgumentException.class, () -> ItemGuards.throttle(-1));
    }

    @Test
    void builderBuildsKeepThrottleStatePerItem() {
        AtomicInteger clicks = new AtomicInteger();
        ItemBuilder builder = Item.builder()
                .setItemProviderAsync(ItemProvider.EMPTY)
                .addClickGuard(ItemGuards.throttle(60_000))
                .addClickHandler(ignoredClick -> clicks.incrementAndGet());
        ObservableItem first = builder.build();
        ObservableItem second = builder.build();
        ItemClick click = click(this.player);
        first.handleClick(click);
        first.handleClick(click);
        second.handleClick(click);

        assertEquals(2, clicks.get());
    }

    @Test
    void rejectedClickDoesNotExtendThrottleInterval() {
        AtomicLong time = new AtomicLong();
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        ObservableItem item = Item.builder()
                .addClickGuard(new ThrottleGuard(10, time::get), ignoredClick -> rejections.incrementAndGet())
                .addClickHandler(ignoredClick -> clicks.incrementAndGet())
                .build();
        ItemClick click = click(this.player);
        item.handleClick(click);
        time.set(5);
        item.handleClick(click);
        time.set(10);
        item.handleClick(click);

        assertEquals(2, clicks.get());
        assertEquals(1, rejections.get());
    }

    @Test
    void firstRejectedGuardStopsLaterGuardsAndClickHandlers() {
        List<String> calls = new ArrayList<>();
        ObservableItem item = Item.builder()
                .addClickGuard((ignoredItem, ignoredClick) -> {
                    calls.add("first-guard");
                    return true;
                })
                .addClickHandler(ignoredClick -> calls.add("click"))
                .addClickGuard(
                        (ignoredItem, ignoredClick) -> {
                            calls.add("second-guard");
                            return false;
                        },
                        ignoredClick -> calls.add("rejected")
                )
                .addClickGuard((ignoredItem, ignoredClick) -> {
                    calls.add("later-guard");
                    return true;
                })
                .build();
        item.handleClick(click(this.player));

        assertEquals(List.of("first-guard", "second-guard", "rejected"), calls);
    }

    @Test
    void builtItemKeepsGuardSnapshot() {
        List<String> calls = new ArrayList<>();
        ItemBuilder builder = Item.builder()
                .addClickGuard((ignoredItem, ignoredClick) -> {
                    calls.add("first-guard");
                    return true;
                });
        ObservableItem item = builder.build();
        builder.addClickGuard((ignoredItem, ignoredClick) -> {
            calls.add("later-guard");
            return true;
        });
        item.handleClick(click(this.player));

        assertEquals(List.of("first-guard"), calls);
    }

    private static ItemClick click(Player player) {
        return new ItemClick(player, ClickType.LEFT, new WindowStub(player), ItemStack.empty(), 0);
    }

    private static <C extends ItemInteraction> ItemGuard<C> windowSlotGuard(AtomicInteger calls) {
        return (ignoredItem, interaction) -> {
            calls.incrementAndGet();
            return interaction.windowSlot() == 0;
        };
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
