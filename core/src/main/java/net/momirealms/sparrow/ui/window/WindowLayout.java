package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.pane.Element;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class WindowLayout {
    private static final PaneSize LOWER_SIZE = new PaneSize(9, 4);

    private final int upperSize;
    private final int lowerStart;           // lower 区域在 Window 中的起始槽位
    private final int protocolSize;         // 不含尾部虚拟区域
    private final Element.PaneLink[] links; // Window 槽位 -> PaneLink
    private final List<Pane> panes;         // 根 Pane, 按首次出现顺序去重

    private WindowLayout(int upperSize, int lowerStart, int protocolSize, Element.PaneLink[] links, List<Pane> panes) {
        this.upperSize = upperSize;
        this.lowerStart = lowerStart;
        this.protocolSize = protocolSize;
        this.links = links;
        this.panes = panes;
    }

    // 区域声明顺序就是最终槽位顺序, 虚拟区域只能出现在末尾.
    @NotNull
    static WindowLayout of(Region @NotNull ... regions) {
        if (regions.length == 0)
            throw new IllegalArgumentException("window layout requires at least one region");

        int upperSize = 0;
        int lowerStart = -1;
        int lowerRegions = 0;
        int size = 0;
        int protocolSize = 0;
        boolean virtualSeen = false;
        // 校验区域顺序, 同时记下协议边界与 lower 起点
        for (int index = 0; index < regions.length; index++) {
            Region region = regions[index];
            switch (region.role()) {
                case UPPER -> {
                    // Window 虚拟区域开始后不允许再出现协议区域
                    if (virtualSeen)
                        throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                    upperSize = Math.addExact(upperSize, region.size());
                }
                case LOWER -> {
                    // Window 虚拟区域开始后不允许再出现协议区域
                    if (virtualSeen)
                        throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                    // 布局必须且只能有一个 lower 区域, 记录其窗口起始槽位
                    lowerRegions++;
                    if (lowerRegions > 1)
                        throw new IllegalArgumentException("window layout requires exactly one lower region");
                    lowerStart = size;
                }
                // 进入 Window 虚拟区域
                default -> virtualSeen = true;
            }

            size = Math.addExact(size, region.size());
            // 协议长度只累计 Window 虚拟区域之前的协议槽位
            if (!virtualSeen) {
                protocolSize = size;
            }
        }

        if (upperSize == 0)
            throw new IllegalArgumentException("window layout requires at least one upper region");
        if (lowerRegions != 1)
            throw new IllegalArgumentException("window layout requires exactly one lower region");

        // 展开槽位映射, 根 Pane 按首次出现顺序去重
        Element.PaneLink[] links = new Element.PaneLink[size];
        ArrayList<Pane> panes = new ArrayList<>(regions.length);
        int offset = 0;
        for (int regionIndex = 0; regionIndex < regions.length; regionIndex++) {
            Region region = regions[regionIndex];
            if (!panes.contains(region.pane())) {
                panes.add(region.pane());
            }
            // 逐槽位生成 Window 槽位到 Pane 槽位的链接
            for (int slot = 0; slot < region.size(); slot++) {
                links[offset + slot] = new Element.PaneLink(region.pane(), region.paneSlot() + slot);
            }
            offset += region.size();
        }
        return new WindowLayout(upperSize, lowerStart, protocolSize, links, List.copyOf(panes));
    }

    /**
     * 编译分离窗口布局.
     * 两个 Pane 分别占据容器和 9x4 玩家物品栏区域.
     *
     * @param upperPane 作为容器区域的 Pane
     * @param lowerPane 作为玩家物品栏区域的 9x4 Pane
     * @return 编译后的不可变布局
     */
    @NotNull
    static WindowLayout split(@NotNull Pane upperPane, @NotNull Pane lowerPane) {
        return WindowLayout.of(Region.upper(upperPane), Region.lower(lowerPane));
    }

    /**
     * 编译合并窗口布局.
     * 单个 Pane 的底部 4 行对应客户端玩家物品栏区域, 至少需要 5 行才能正常显示.
     *
     * @param pane 同时包含容器区与玩家物品栏区的 Pane, 必须超过 45 个槽位
     * @return 编译后的不可变布局
     * @throws IllegalArgumentException Pane 不超过 45 个槽位时抛出
     */
    @NotNull
    static WindowLayout merged(@NotNull Pane pane) {
        if (pane.area() <= 45) {
            throw new IllegalArgumentException("merged Pane must contain more than 45 slots");
        }
        // 底部 36 个槽位切给 lower 区域, 其余作为 upper 区域
        int lowerStart = pane.area() - 36;
        return WindowLayout.of(
                Region.upper(pane, 0, lowerStart),
                Region.lower(pane, lowerStart, 36)
        );
    }

    /**
     * 返回指定 Window 槽位的根 PaneLink.
     *
     * @param windowSlot Window 槽位号
     * @return 该槽位对应的根 PaneLink
     */
    @NotNull
    Element.PaneLink paneAt(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= this.links.length)
            throw new IndexOutOfBoundsException("window slot out of bounds: " + windowSlot);

        return this.links[windowSlot];
    }

    int upperSize() {
        return this.upperSize;
    }

    int protocolSize() {
        return this.protocolSize;
    }

    int size() {
        return this.links.length;
    }

    // lower 前 27 格是背包主区, 快捷栏从偏移 27 开始.
    int windowSlotAtHotbar(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= 9)
            throw new IndexOutOfBoundsException("hotbar slot out of bounds: " + hotbarSlot);

        return this.lowerStart + 27 + hotbarSlot;
    }

    @NotNull
    Pane lowerPane() {
        return this.links[this.lowerStart].pane();
    }

    @NotNull
    List<Pane> panes() {
        return this.panes;
    }

    /**
     * Window 中一个连续区域的声明.
     *
     * @param role 区域在 Window 中承担的结构角色
     * @param pane 区域所属的根 Pane
     * @param paneSlot 区域在 Pane 中的起始槽位
     * @param size 区域包含的槽位数
     */
    record Region(@NotNull Role role, @NotNull Pane pane, int paneSlot, int size) {

        public Region {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(pane, "pane");
            if (paneSlot < 0 || size < 0 || paneSlot > pane.area() - size)
                throw new IndexOutOfBoundsException("Pane region out of bounds: slot=" + paneSlot + ", size=" + size);
            if (role != Role.VIRTUAL && size == 0)
                throw new IllegalArgumentException("physical Pane region must contain at least one slot");
            if (role == Role.LOWER && size != LOWER_SIZE.area())
                throw new IllegalArgumentException("lower region must contain 36 slots");
        }

        @NotNull
        static Region upper(@NotNull Pane pane) {
            return Region.upper(pane, 0, pane.area());
        }

        @NotNull
        static Region upper(@NotNull Pane pane, int startSlot, int size) {
            return new Region(Role.UPPER, pane, startSlot, size);
        }

        @NotNull
        static Region lower(@NotNull Pane pane) {
            if (!pane.size().equals(LOWER_SIZE))
                throw new IllegalArgumentException("lower Pane must be 9x4");

            return Region.lower(pane, 0, pane.area());
        }

        @NotNull
        static Region lower(@NotNull Pane pane, int startSlot, int size) {
            return new Region(Role.LOWER, pane, startSlot, size);
        }

        @NotNull
        static Region virtual(@NotNull Pane pane) {
            return new Region(Role.VIRTUAL, pane, 0, pane.area());
        }

        enum Role {
            UPPER,   // 容器区域
            LOWER,   // 玩家物品栏区域, 布局中必须恰好一个且为 36 槽位
            VIRTUAL  // Window 虚拟区域, 不进入原版菜单协议, 必须全部位于尾部
        }
    }
}
