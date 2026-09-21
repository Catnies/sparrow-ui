package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.pane.Element;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// 一扇窗的槽位划分: 上面是容器区, 下面是玩家物品栏区, 后面还可以挂一段不进协议的虚拟区域.
// 每个 Window 槽位落在那块 Pane 的哪一格, 都在构造时摊平成一张表.
final class WindowLayout {
    private static final PaneSize LOWER_SIZE = new PaneSize(9, 4);

    private final int upperSize;            // 上半区域的槽位数
    private final int lowerStart;           // lower 区域从哪个 Window 槽位开始
    private final int protocolSize;         // 协议范围的长度, 不含尾部的虚拟区域
    private final Element.PaneLink[] links; // 每个 Window 槽位对应的 PaneLink
    private final List<Pane> panes;         // 用到的根 Pane, 按第一次出现的顺序去重

    private WindowLayout(int upperSize, int lowerStart, int protocolSize, Element.PaneLink[] links, List<Pane> panes) {
        this.upperSize = upperSize;
        this.lowerStart = lowerStart;
        this.protocolSize = protocolSize;
        this.links = links;
        this.panes = panes;
    }

    // 区域的声明顺序就是最终的槽位顺序; 虚拟区域只能排在最后.
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
        // 一边查区域顺序, 一边记下协议边界和 lower 的起点
        for (int index = 0; index < regions.length; index++) {
            Region region = regions[index];
            switch (region.role()) {
                case UPPER -> {
                    // 虚拟区域一旦开始, 后面就不能再有协议区域了
                    if (virtualSeen)
                        throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                    upperSize = Math.addExact(upperSize, region.size());
                }
                case LOWER -> {
                    // 虚拟区域一旦开始, 后面就不能再有协议区域了
                    if (virtualSeen)
                        throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                    // lower 区域必须恰好一个, 顺手记下它从哪个槽位开始
                    lowerRegions++;
                    if (lowerRegions > 1)
                        throw new IllegalArgumentException("window layout requires exactly one lower region");
                    lowerStart = size;
                }
                // 从这里开始进虚拟区域
                default -> virtualSeen = true;
            }

            size = Math.addExact(size, region.size());
            // 协议长度只数虚拟区域之前那些槽位
            if (!virtualSeen) {
                protocolSize = size;
            }
        }

        if (upperSize == 0)
            throw new IllegalArgumentException("window layout requires at least one upper region");
        if (lowerRegions != 1)
            throw new IllegalArgumentException("window layout requires exactly one lower region");

        // 展开槽位映射, 根 Pane 按第一次出现的顺序去重
        Element.PaneLink[] links = new Element.PaneLink[size];
        ArrayList<Pane> panes = new ArrayList<>(regions.length);
        int offset = 0;
        for (int regionIndex = 0; regionIndex < regions.length; regionIndex++) {
            Region region = regions[regionIndex];
            if (!panes.contains(region.pane())) {
                panes.add(region.pane());
            }
            // 一槽一槽地把 Window 槽位接到 Pane 的槽位上
            for (int slot = 0; slot < region.size(); slot++) {
                links[offset + slot] = new Element.PaneLink(region.pane(), region.paneSlot() + slot);
            }
            offset += region.size();
        }
        return new WindowLayout(upperSize, lowerStart, protocolSize, links, List.copyOf(panes));
    }

    /**
     * 编出上下分离的布局: 两个 Pane 分别占容器区和 9x4 的玩家物品栏区.
     *
     * @param upperPane 作为容器区域的 Pane
     * @param lowerPane 作为玩家物品栏区域的 9x4 Pane
     * @return 编译好的布局
     */
    @NotNull
    static WindowLayout split(@NotNull Pane upperPane, @NotNull Pane lowerPane) {
        return WindowLayout.of(Region.upper(upperPane), Region.lower(lowerPane));
    }

    /**
     * 编出合并布局: 一个 Pane 的最后 4 行就是客户端的玩家物品栏区, 所以至少得 5 行才摆得下.
     *
     * @param pane 同时包含容器区与玩家物品栏区的 Pane, 必须超过 36 个槽位
     * @return 编译好的布局
     * @throws IllegalArgumentException Pane 的槽位数不超过 36 个时
     */
    @NotNull
    static WindowLayout merged(@NotNull Pane pane) {
        if (pane.area() <= LOWER_SIZE.area()) {
            throw new IllegalArgumentException("merged Pane must contain more than 36 slots");
        }
        // 最后 36 格切给 lower, 剩下的算 upper
        int lowerStart = pane.area() - 36;
        return WindowLayout.of(
                Region.upper(pane, 0, lowerStart),
                Region.lower(pane, lowerStart, 36)
        );
    }

    /**
     * 这个 Window 槽位落在哪个根 Pane 的哪一格.
     *
     * @param windowSlot Window 槽位号
     * @return 对应的根 PaneLink
     * @throws IndexOutOfBoundsException Window 槽位越界时
     */
    @NotNull
    Element.PaneLink paneAt(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= this.links.length)
            throw new IndexOutOfBoundsException("window slot out of bounds: " + windowSlot);

        return this.links[windowSlot];
    }

    // 上半区域占几格
    int upperSize() {
        return this.upperSize;
    }

    // 协议范围有多长
    int protocolSize() {
        return this.protocolSize;
    }

    // Window 槽位总数, 含尾部虚拟区域
    int size() {
        return this.links.length;
    }

    // lower 里前 27 格是背包主区, 快捷栏从那之后开始, 所以快捷栏第 n 格就是 lowerStart + 27 + n
    int windowSlotAtHotbar(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= 9)
            throw new IndexOutOfBoundsException("hotbar slot out of bounds: " + hotbarSlot);

        return this.lowerStart + 27 + hotbarSlot;
    }

    // lower 区域第一格所在的那个 Pane 就是下部 Pane
    @NotNull
    Pane lowerPane() {
        return this.links[this.lowerStart].pane();
    }

    // 这份布局用到的根 Pane 名单
    @NotNull
    List<Pane> panes() {
        return this.panes;
    }

    /**
     * Window 里的一段连续区域.
     *
     * @param role 这段区域在结构里扮什么角色
     * @param pane 它落在哪个根 Pane 上
     * @param paneSlot 它从 Pane 的第几格开始
     * @param size 它占几格
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
            LOWER,   // 玩家物品栏区域; 布局里必须恰好一个, 而且正好 36 格
            VIRTUAL  // Window 虚拟区域; 不进原版菜单协议, 而且只能排在尾部
        }
    }
}
