package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.gui.SlotElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把有序区域一次编译为 Window 逻辑槽位到 GUI 或玩家物品栏的路由.
 * <p>Region 的声明顺序就是最终逻辑顺序, 尾部 virtual region 不进入菜单协议.
 * 编译后的布局不可变, Window tick 只读取 route, 不再重复计算区域映射.
 */
final class WindowLayout {
    private static final GuiSize LOWER_SIZE = new GuiSize(9, 4);

    private final int upperSize;
    private final int lowerStart;
    private final int protocolSize;
    private final Route[] routes;
    private final List<Gui> guis;
    private final boolean playerLower; // lower 区域是否映射真实玩家背包

    private WindowLayout(int upperSize, int lowerStart, int protocolSize, Route[] routes, Region[] regions) {
        this.upperSize = upperSize;
        this.lowerStart = lowerStart;
        this.protocolSize = protocolSize;
        this.routes = routes;
        this.guis = collectGuis(regions);
        boolean playerLower = false;
        for (int index = 0; index < regions.length; index++) {
            playerLower |= regions[index] == Region.PlayerRegion.INSTANCE;
        }
        this.playerLower = playerLower;
    }

    /**
     * 返回布局的 lower 区域是否映射真实玩家背包; split 与 merged 布局返回 false.
     */
    boolean hasPlayerLower() {
        return this.playerLower;
    }

    /**
     * 按声明顺序编译 Window 的全部区域.
     *
     * <p>布局必须且只能包含一个 9x4 lower 区域, 至少包含一个 upper 区域.
     * upper 可以位于 lower 前后, 因而能够表达合成器结果槽位位于玩家物品栏之后
     * 的协议顺序. virtual region 如果存在, 必须全部位于物理区域之后.
     *
     * @param regions 按最终 Window 槽位顺序排列的区域
     * @return 编译后的不可变布局
     */
    @NotNull
    static WindowLayout of(Region @NotNull ... regions) {
        if (regions.length == 0) {
            throw new IllegalArgumentException("window layout requires at least one region");
        }

        int upperSize = 0;
        int lowerStart = -1;
        int lowerRegions = 0;
        int size = 0;
        int protocolSize = 0;
        boolean virtualSeen = false;
        for (int index = 0; index < regions.length; index++) {
            Region region = regions[index];
            if (region.role() == Region.Role.UPPER) {
                if (virtualSeen) {
                    throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                }
                upperSize = Math.addExact(upperSize, region.size());
            } else if (region.role() == Region.Role.LOWER) {
                if (virtualSeen) {
                    throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                }
                lowerRegions++;
                if (lowerRegions > 1) {
                    throw new IllegalArgumentException("window layout requires exactly one lower region");
                }
                lowerStart = size;
            } else {
                virtualSeen = true;
            }

            size = Math.addExact(size, region.size());
            if (!virtualSeen) {
                protocolSize = size;
            }
        }

        if (upperSize == 0) {
            throw new IllegalArgumentException("window layout requires at least one upper region");
        }
        if (lowerRegions != 1) {
            throw new IllegalArgumentException("window layout requires exactly one lower region");
        }

        Route[] routes = new Route[size];
        int offset = 0;
        for (int index = 0; index < regions.length; index++) {
            Region region = regions[index];
            switch (region) {
                case Region.GuiRegion guiRegion -> {
                    for (int slot = 0; slot < guiRegion.size; slot++) {
                        routes[offset + slot] = new Route.GuiRoute(guiRegion.gui, guiRegion.guiSlot + slot);
                    }
                }
                case Region.PlayerRegion ignoredRegion -> {
                    for (int lowerSlot = 0; lowerSlot < LOWER_SIZE.area(); lowerSlot++) {
                        int inventorySlot = lowerSlot < 27 ? lowerSlot + 9 : lowerSlot - 27;
                        routes[offset + lowerSlot] = new Route.PlayerRoute(inventorySlot);
                    }
                }
            }
            offset += region.size();
        }
        return new WindowLayout(upperSize, lowerStart, protocolSize, routes, regions);
    }

    /**
     * 编译上部 GUI 与下方玩家真实物品栏组成的布局.
     * GUI 占据上半部分, 下半部分映射玩家原生物品栏.
     */
    @NotNull
    static WindowLayout upper(@NotNull Gui gui) {
        return WindowLayout.of(Region.upper(gui), Region.lower(null));
    }

    /**
     * 编译分离窗口布局.
     * 两个 GUI 分别占据容器和 9x4 玩家物品栏区域.
     */
    @NotNull
    static WindowLayout split(@NotNull Gui upperGui, @NotNull Gui lowerGui) {
        return WindowLayout.of(Region.upper(upperGui), Region.lower(lowerGui));
    }

    /**
     * 编译合并窗口布局.
     * 单个 GUI 的底部 4 行对应客户端玩家物品栏区域.
     */
    @NotNull
    static WindowLayout merged(@NotNull Gui gui) {
        if (gui.area() <= LOWER_SIZE.area()) {
            throw new IllegalArgumentException("merged GUI must contain more than 36 slots");
        }
        int lowerStart = gui.area() - LOWER_SIZE.area();
        return WindowLayout.of(
                Region.upper(gui, 0, lowerStart),
                Region.lower(gui, lowerStart, LOWER_SIZE.area())
        );
    }

    /**
     * 返回指定窗口槽位的 GUI 链接, 玩家物品栏槽位返回 null.
     */
    @Nullable
    SlotElement.GuiLink guiAt(int windowSlot) {
        return switch (this.route(windowSlot)) {
            case Route.GuiRoute route -> new SlotElement.GuiLink(route.gui(), route.guiSlot());
            case Route.PlayerRoute ignoredRoute -> null;
        };
    }

    /**
     * 返回指定 Window 槽位的预编译 Route.
     */
    @NotNull
    Route route(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= this.routes.length) {
            throw new IndexOutOfBoundsException("window slot out of bounds: " + windowSlot);
        }
        return this.routes[windowSlot];
    }

    int upperSize() {
        return this.upperSize;
    }

    int size() {
        return this.routes.length;
    }

    /**
     * 返回实际发送给原版菜单协议的物理槽位长度.
     */
    int protocolSize() {
        return this.protocolSize;
    }

    int windowSlotAtHotbar(int hotbarSlot) {
        return this.lowerStart + 27 + hotbarSlot;
    }

    /**
     * 返回玩家背包槽号映射到的 Window 槽位, 是 PlayerRoute 编译映射的逆运算.
     */
    int windowSlotOfInventorySlot(int inventorySlot) {
        int lowerSlot = inventorySlot >= 9 ? inventorySlot - 9 : inventorySlot + 27;
        return this.lowerStart + lowerSlot;
    }

    @NotNull
    List<Gui> guis() {
        return this.guis;
    }

    /**
     * 以首次出现顺序收集不同的根 GUI, 用于 Window 的公开查询 API.
     */
    private static List<Gui> collectGuis(Region[] regions) {
        ArrayList<Gui> guis = new ArrayList<>(regions.length);
        for (int index = 0; index < regions.length; index++) {
            if (regions[index] instanceof Region.GuiRegion(var ignoredRole, var gui, var ignoredGuiSlot, var ignoredSize) && !guis.contains(gui)) {
                guis.add(gui);
            }
        }
        return List.copyOf(guis);
    }

    /**
     * Window 中一个连续区域的声明.
     */
    sealed interface Region permits Region.GuiRegion, Region.PlayerRegion {

        @NotNull
        static Region upper(@NotNull Gui gui) {
            return Region.upper(gui, 0, gui.area());
        }

        @NotNull
        static Region upper(@NotNull Gui gui, int startSlot, int size) {
            return new GuiRegion(Role.UPPER, gui, startSlot, size);
        }

        @NotNull
        static Region lower(@Nullable Gui gui) {
            if (gui == null) {
                return PlayerRegion.INSTANCE;
            }
            if (!gui.size().equals(LOWER_SIZE)) {
                throw new IllegalArgumentException("lower GUI must be 9x4");
            }
            return Region.lower(gui, 0, gui.area());
        }

        @NotNull
        static Region lower(@NotNull Gui gui, int startSlot, int size) {
            return new GuiRegion(Role.LOWER, gui, startSlot, size);
        }

        @NotNull
        static Region virtual(@NotNull Gui gui) {
            return new GuiRegion(Role.VIRTUAL, gui, 0, gui.area());
        }

        @NotNull
        Role role();

        int size();

        /**
         * 区域在 Window 中承担的结构角色.
         */
        enum Role {
            UPPER,
            LOWER,
            VIRTUAL
        }

        /**
         * 映射到一个 GUI 连续片段的区域.
         */
        record GuiRegion(@NotNull Role role, @NotNull Gui gui, int guiSlot, int size) implements Region {

            public GuiRegion {
                Objects.requireNonNull(role, "role");
                Objects.requireNonNull(gui, "gui");
                if (guiSlot < 0 || size < 0 || guiSlot > gui.area() - size) {
                    throw new IndexOutOfBoundsException("GUI region out of bounds: slot=" + guiSlot + ", size=" + size);
                }
                if (role != Role.VIRTUAL && size == 0) {
                    throw new IllegalArgumentException("physical GUI region must contain at least one slot");
                }
                if (role == Role.LOWER && size != LOWER_SIZE.area()) {
                    throw new IllegalArgumentException("lower region must contain 36 slots");
                }
            }
        }

        /**
         * 映射玩家真实物品栏的固定 9x4 lower 区域.
         */
        enum PlayerRegion implements Region {
            INSTANCE;

            @Override
            @NotNull
            public Role role() {
                return Role.LOWER;
            }

            @Override
            public int size() {
                return LOWER_SIZE.area();
            }
        }
    }

    /**
     * 一个 Window 槽位的内容所有权.
     */
    sealed interface Route permits Route.GuiRoute, Route.PlayerRoute {

        /**
         * 由 GUI 管理的 Window 槽位.
         */
        record GuiRoute(@NotNull Gui gui, int guiSlot) implements Route {
        }

        /**
         * 映射到玩家原生物品栏的协议槽位.
         */
        record PlayerRoute(int inventorySlot) implements Route {
        }
    }
}
