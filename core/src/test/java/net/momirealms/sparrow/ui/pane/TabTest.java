package net.momirealms.sparrow.ui.pane;

import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.item.AttachSupport;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.pane.page.Tab;
import net.momirealms.sparrow.ui.state.GcSupport;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TabTest {

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
    void switchesBetweenPanes() {
        NormalPane weapons = Pane.empty(1, 1);
        NormalPane armor = Pane.empty(1, 1);
        Tab<String> tabs = Tab.of(Map.of("weapons", weapons, "armor", armor), "weapons");

        assertSame(weapons, tabs.pane().get());
        assertEquals("weapons", tabs.selected().get());
        tabs.select("armor");

        assertSame(armor, tabs.pane().get());
    }

    @Test
    void rejectsUnknownKeys() {
        Tab<String> tabs = Tab.of(Map.of("weapons", Pane.empty(1, 1)), "weapons");

        assertThrows(IllegalArgumentException.class, () -> tabs.select("nope"));
        assertThrows(IllegalArgumentException.class, () -> Tab.of(Map.of("weapons", Pane.empty(1, 1)), "nope"));
        assertThrows(IllegalArgumentException.class, () -> Tab.lazy(Map.of("weapons", (Supplier<Pane>) () -> Pane.empty(1, 1)), "nope"));
    }

    @Test
    void laterChangesToTheGivenMapDoNotLeakIn() {
        Map<String, Pane> panes = new LinkedHashMap<>();
        panes.put("weapons", Pane.empty(1, 1));
        Tab<String> tabs = Tab.of(panes, "weapons");
        panes.put("armor", Pane.empty(1, 1));

        assertThrows(IllegalArgumentException.class, () -> tabs.select("armor"), "建好之后再往原 Map 里加是不算数的");
    }

    @Test
    void lazyBuildsAPaneOnlyWhenItIsFirstShown() {
        AtomicInteger weaponsBuilds = new AtomicInteger();
        AtomicInteger marketBuilds = new AtomicInteger();
        Map<String, Supplier<Pane>> suppliers = Map.of(
                "weapons", () -> {
                    weaponsBuilds.incrementAndGet();
                    return Pane.empty(1, 1);
                },
                "market", () -> {
                    marketBuilds.incrementAndGet();
                    return Pane.empty(1, 1);
                });
        Tab<String> tabs = Tab.lazy(suppliers, "weapons");

        assertEquals(0, weaponsBuilds.get(), "还没人显示它, 一个都不建");
        NormalPane pane = Pane.builder("V").addIngredient('V', tabs).build();

        assertEquals(1, weaponsBuilds.get(), "初始标签在第一次显示时建");
        assertEquals(0, marketBuilds.get(), "没点开的标签不建, 它里面的数据源自然一次都不会被拉");
        assertEquals(Element.pane(tabs.pane().get(), 0), pane.element(0));
        tabs.select("market");

        assertEquals(1, marketBuilds.get(), "点开才建");
    }

    @Test
    void lazyBuildsEachPaneOnlyOnce() {
        AtomicInteger builds = new AtomicInteger();
        Map<String, Supplier<Pane>> suppliers = Map.of(
                "a", () -> {
                    builds.incrementAndGet();
                    return Pane.empty(1, 1);
                },
                "b", () -> Pane.empty(1, 1));
        Tab<String> tabs = Tab.lazy(suppliers, "a");
        NormalPane pane = Pane.builder("V").addIngredient('V', tabs).build();
        Pane firstShown = ((Element.PaneLink) pane.element(0)).pane();
        tabs.select("b");
        tabs.select("a");

        assertEquals(1, builds.get(), "切走再切回不重建");
        assertSame(firstShown, ((Element.PaneLink) pane.element(0)).pane(), "回来还是原来那一片");
    }

    @Test
    void lazySupplierMustProduceAPane() {
        Map<String, Supplier<Pane>> suppliers = Map.of("broken", () -> null);
        Tab<String> tabs = Tab.lazy(suppliers, "broken");

        assertThrows(NullPointerException.class, () -> tabs.pane().get(), "建造函数给出 null 当场抛");
    }

    @Test
    void builderConnectsTheRegionToTheSelectedPane() {
        NormalPane weapons = Pane.empty(2, 2);
        NormalPane armor = Pane.empty(2, 2);
        Tab<String> tabs = Tab.of(Map.of("weapons", weapons, "armor", armor), "weapons");
        NormalPane pane = Pane.builder("VV", "VV", "WA")
                .addIngredient('V', tabs)
                .addIngredient('W', this.tabButton(tabs, "weapons"))
                .addIngredient('A', this.tabButton(tabs, "armor"))
                .build();

        assertEquals(Element.pane(weapons, 0), pane.element(0));
        assertEquals(Element.pane(weapons, 3), pane.element(3), "区域按二维形状连接, 右下角对右下角");
        pane.item(5).handleClick(this.click());

        assertEquals(Element.pane(armor, 0), pane.element(0));
        assertEquals(Element.pane(armor, 3), pane.element(3));
    }

    @Test
    void tabRegionPadsWhereTheSelectedPaneFallsShort() {
        NormalPane small = Pane.empty(1, 1);
        NormalPane full = Pane.empty(2, 2);
        Tab<String> tabs = Tab.of(Map.of("small", small, "full", full), "small");
        NormalPane pane = Pane.builder("VV", "VV").addIngredient('V', tabs).build();

        assertEquals(Element.pane(small, 0), pane.element(0), "盖得住的那格连接过去");
        assertSame(Element.empty(), pane.element(1), "子 Pane 盖不住的槽位补空");
        assertSame(Element.empty(), pane.element(3));
        tabs.select("full");

        assertEquals(Element.pane(full, 1), pane.element(1), "切到盖得住的子 Pane 后整片都有了");
        assertEquals(Element.pane(full, 3), pane.element(3));
    }

    @Test
    void twoTabsSharingOnePaneRewriteNothing() {
        NormalPane shared = Pane.empty(2, 1);
        Tab<String> tabs = Tab.of(Map.of("a", shared, "b", shared), "a");
        NormalPane pane = Pane.builder("VV").addIngredient('V', tabs).build();
        Element before = pane.element(0);
        tabs.select("b");

        assertSame(before, pane.element(0), "换了标签但还是同一个子 Pane, 内容没变的槽位不写");
    }

    @Test
    void selectedCarriesSwitchesToItsDependents() {
        Tab<String> tabs = Tab.of(Map.of(
                "weapons", Pane.empty(1, 1),
                "armor", Pane.empty(1, 1)), "weapons");
        Item icon = Item.builder()
                .dependsOn(tabs.selected())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .build();
        AtomicInteger invalidations = new AtomicInteger();
        AttachSupport.attach(icon, ignoredItem -> invalidations.incrementAndGet());
        tabs.select("armor");

        assertEquals(1, invalidations.get(), "选中态变了, 按钮要重画");
        tabs.select("armor");

        assertEquals(1, invalidations.get(), "切到已经选中的那一个什么都不该发生");
    }

    @Test
    void tabIsCollectedAlongWithTheHostPane() {
        NormalPane child = Pane.empty(1, 1);
        Tab<String> tabs = Tab.of(Map.of("only", child), "only");
        NormalPane pane = Pane.builder("V").addIngredient('V', tabs).build();
        WeakReference<Tab<String>> probe = new WeakReference<>(tabs);
        tabs = null;
        pane = null;
        GcSupport.awaitCollected(probe);
    }

    private Item tabButton(Tab<String> tabs, String key) {
        return Item.builder()
                .dependsOn(tabs.selected())
                .setItemProvider(ignoredContext -> ItemStack.empty())
                .addClickHandler(ignoredClick -> tabs.select(key))
                .build();
    }

    private ItemClick click() {
        return new ItemClick(this.player, ClickType.LEFT, new WindowStub(this.player), ItemStack.empty(), 0);
    }
}
