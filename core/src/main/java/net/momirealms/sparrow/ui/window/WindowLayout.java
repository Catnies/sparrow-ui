package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.gui.SlotElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 把协议窗口槽位一次编译为 GUI 槽位或玩家物品栏槽位.
 * 编译后的布局不可变, Window tick 只读取 route, 不再重复计算区域映射.
 */
final class WindowLayout {

    /**
     * 一个协议窗口槽位的内容所有权.
     */
    sealed interface Route permits GuiRoute, PlayerRoute {
    }

    /**
     * 由 GUI 管理的协议槽位.
     */
    record GuiRoute(@NotNull Gui gui, int guiSlot) implements Route {
    }

    /**
     * 映射到玩家原生物品栏的协议槽位.
     */
    record PlayerRoute(int inventorySlot) implements Route {
    }

    private static final GuiSize LOWER_SIZE = new GuiSize(9, 4);

    private final int topSlots;
    private final Route[] routes;
    private final List<Gui> guis;

    private WindowLayout(int topSlots, Route[] routes) {
        this.topSlots = topSlots;
        this.routes = routes;
        this.guis = collectGuis(routes);
    }

    /**
     * 编译上部 GUI 与下方玩家真实物品栏组成的布局.
     * GUI 占据上半部分, 下半部分映射玩家原生物品栏.
     */
    static @NotNull WindowLayout playerInventoryBelow(@NotNull Gui gui) {
        int topSlots = gui.area();
        Route[] routes = new Route[topSlots + LOWER_SIZE.area()];
        fillGui(routes, 0, gui);
        fillPlayer(routes, topSlots);
        return new WindowLayout(topSlots, routes);
    }

    /**
     * 编译分离窗口布局.
     * 两个 GUI 分别占据容器和 9x4 玩家物品栏区域.
     */
    static @NotNull WindowLayout split(@NotNull Gui upperGui, @NotNull Gui lowerGui) {
        if (!lowerGui.size().equals(LOWER_SIZE)) {
            throw new IllegalArgumentException("lower GUI must be 9x4");
        }
        int topSlots = upperGui.area();
        Route[] routes = new Route[topSlots + lowerGui.area()];
        fillGui(routes, 0, upperGui);
        fillGui(routes, topSlots, lowerGui);
        return new WindowLayout(topSlots, routes);
    }

    /**
     * 编译合并窗口布局.
     * 单个 GUI 的底部 4 行对应客户端玩家物品栏区域.
     */
    static @NotNull WindowLayout merged(@NotNull Gui gui) {
        if (gui.area() <= LOWER_SIZE.area()) {
            throw new IllegalArgumentException("merged GUI must contain more than 36 slots");
        }
        int topSlots = gui.area() - LOWER_SIZE.area();
        Route[] routes = new Route[gui.area()];
        fillGui(routes, 0, gui);
        return new WindowLayout(topSlots, routes);
    }

    int topSlots() {
        return this.topSlots;
    }

    int size() {
        return this.routes.length;
    }

    @NotNull List<Gui> guis() {
        return this.guis;
    }

    /**
     * 返回指定窗口槽位的 GUI 链接, 玩家物品栏槽位返回 null.
     */
    SlotElement.@Nullable GuiLink guiAt(int windowSlot) {
        return switch (this.route(windowSlot)) {
            case GuiRoute route -> new SlotElement.GuiLink(route.gui(), route.guiSlot());
            case PlayerRoute _ -> null;
        };
    }

    /**
     * 返回指定协议槽位的预编译 route.
     */
    @NotNull Route route(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= this.routes.length) {
            throw new IndexOutOfBoundsException("window slot out of bounds: " + windowSlot);
        }
        return this.routes[windowSlot];
    }

    private static void fillGui(Route[] routes, int offset, Gui gui) {
        for (int slot = 0; slot < gui.area(); slot++) {
            routes[offset + slot] = new GuiRoute(gui, slot);
        }
    }

    private static void fillPlayer(Route[] routes, int offset) {
        for (int lowerSlot = 0; lowerSlot < LOWER_SIZE.area(); lowerSlot++) {
            int inventorySlot = lowerSlot < 27 ? lowerSlot + 9 : lowerSlot - 27;
            routes[offset + lowerSlot] = new PlayerRoute(inventorySlot);
        }
    }

    /**
     * 以首次出现顺序收集不同的根 GUI, 用于 Window 的公开查询 API.
     */
    private static List<Gui> collectGuis(Route[] routes) {
        ArrayList<Gui> guis = new ArrayList<>(2);
        for (int index = 0; index < routes.length; index++) {
            if (routes[index] instanceof GuiRoute(var gui, _) && !guis.contains(gui)) {
                guis.add(gui);
            }
        }
        return List.copyOf(guis);
    }
}
