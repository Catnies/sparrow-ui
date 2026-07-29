package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.gui.Gui;
import net.momirealms.sparrow.ui.gui.GuiSize;
import net.momirealms.sparrow.ui.gui.SlotElement;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 Window 的有序结构区域预编译为不可变的槽位到 GUI 映射.
 * <p>Region 的声明顺序就是最终逻辑顺序, 尾部 virtual region 不进入菜单协议.
 * Region 仅存在于构建期, 运行时所有槽位都直接解析为普通根 GuiLink.
 * <p>编译结果是一个扁平数组, 窗口槽位号即下标, 运行期查询无需再遍历区域.
 */
final class WindowLayout {
    private static final GuiSize LOWER_SIZE = new GuiSize(9, 4);

    private final int upperSize;               // 容器 upper 区域的槽位总数
    private final int lowerStart;              // lower 区域在窗口中的起始槽位
    private final int protocolSize;            // 进入原版菜单协议的物理槽位数, 不含尾部 virtual 区域
    private final SlotElement.GuiLink[] links; // 窗口槽位号(下标) -> GuiLink
    private final List<Gui> guis;              // 布局引用的全部根 GUI, 按首次出现顺序去重

    private WindowLayout(int upperSize, int lowerStart, int protocolSize, SlotElement.GuiLink[] links, List<Gui> guis) {
        this.upperSize = upperSize;
        this.lowerStart = lowerStart;
        this.protocolSize = protocolSize;
        this.links = links;
        this.guis = guis;
    }

    /**
     * 按声明顺序编译 Window 的全部区域.
     *
     * <p>布局必须且只能包含一个 9x4 lower 区域, 至少包含一个 upper 区域.
     * 传入的参数顺序决定了协议顺序, virtual region 如果存在, 必须全部位于物理区域之后.
     *
     * @param regions 按最终 Window 槽位顺序排列的区域
     * @return 编译后的不可变布局
     */
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
        // 校验区域顺序与数量约束, 并累计各部分的槽位数
        for (int index = 0; index < regions.length; index++) {
            Region region = regions[index];
            switch (region.role()) {
                case UPPER -> {
                    // virtual 后缀开始后不允许再出现物理区域
                    if (virtualSeen)
                        throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                    upperSize = Math.addExact(upperSize, region.size());
                }
                case LOWER -> {
                    // virtual 后缀开始后不允许再出现物理区域
                    if (virtualSeen)
                        throw new IllegalArgumentException("virtual regions must be a trailing suffix");
                    // 布局必须且只能有一个 lower 区域, 记录其窗口起始槽位
                    lowerRegions++;
                    if (lowerRegions > 1)
                        throw new IllegalArgumentException("window layout requires exactly one lower region");
                    lowerStart = size;
                }
                // 进入 virtual 后缀
                default -> virtualSeen = true;
            }

            size = Math.addExact(size, region.size());
            // 协议长度只累计 virtual 之前的物理部分
            if (!virtualSeen) {
                protocolSize = size;
            }
        }

        if (upperSize == 0)
            throw new IllegalArgumentException("window layout requires at least one upper region");
        if (lowerRegions != 1)
            throw new IllegalArgumentException("window layout requires exactly one lower region");

        // 展开为扁平的槽位链接数组, 同时按首次出现顺序收集去重后的 GUI
        SlotElement.GuiLink[] links = new SlotElement.GuiLink[size];
        ArrayList<Gui> guis = new ArrayList<>(regions.length);
        int offset = 0;
        for (int regionIndex = 0; regionIndex < regions.length; regionIndex++) {
            Region region = regions[regionIndex];
            if (!guis.contains(region.gui())) {
                guis.add(region.gui());
            }
            // 逐槽位生成窗口槽位到 GUI 槽位的链接
            for (int slot = 0; slot < region.size(); slot++) {
                links[offset + slot] = new SlotElement.GuiLink(region.gui(), region.guiSlot() + slot);
            }
            offset += region.size();
        }
        return new WindowLayout(upperSize, lowerStart, protocolSize, links, List.copyOf(guis));
    }

    /**
     * 编译分离窗口布局.
     * 两个 GUI 分别占据容器和 9x4 玩家物品栏区域.
     *
     * @param upperGui 作为容器区域的 GUI
     * @param lowerGui 作为玩家物品栏区域的 9x4 GUI
     * @return 编译后的不可变布局
     */
    @NotNull
    static WindowLayout split(@NotNull Gui upperGui, @NotNull Gui lowerGui) {
        return WindowLayout.of(Region.upper(upperGui), Region.lower(lowerGui));
    }

    /**
     * 编译合并窗口布局.
     * 单个 GUI 的底部 4 行对应客户端玩家物品栏区域, 至少需要 5 行才能正常显示.
     *
     * @param gui 同时包含容器区与玩家物品栏区的 GUI, 必须超过 45 个槽位
     * @return 编译后的不可变布局
     * @throws IllegalArgumentException GUI 不超过 45 个槽位时抛出
     */
    @NotNull
    static WindowLayout merged(@NotNull Gui gui) {
        if (gui.area() <= 45) {
            throw new IllegalArgumentException("merged GUI must contain more than 45 slots");
        }
        // 底部 36 个槽位切给 lower 区域, 其余作为 upper 区域
        int lowerStart = gui.area() - 36;
        return WindowLayout.of(
                Region.upper(gui, 0, lowerStart),
                Region.lower(gui, lowerStart, 36)
        );
    }

    /**
     * 返回指定 Window 槽位的根 GuiLink.
     *
     * @param windowSlot Window 槽位号
     * @return 该槽位对应的根 GuiLink
     * @throws IndexOutOfBoundsException 槽位号超出 Window 范围时抛出
     */
    @NotNull
    SlotElement.GuiLink guiAt(int windowSlot) {
        if (windowSlot < 0 || windowSlot >= this.links.length)
            throw new IndexOutOfBoundsException("window slot out of bounds: " + windowSlot);

        return this.links[windowSlot];
    }

    /**
     * 返回容器区域的槽位总数, 即全部 upper 区域的长度之和.
     *
     * @return 容器区域槽位总数
     */
    int upperSize() {
        return this.upperSize;
    }

    /**
     * 返回实际发送给原版菜单协议的物理槽位长度.
     *
     * @return 物理槽位长度, 不含尾部 virtual 区域
     */
    int protocolSize() {
        return this.protocolSize;
    }

    /**
     * 返回 Window 的逻辑槽位总数, 包含不进入协议的 Virtual 区域.
     *
     * @return 逻辑槽位总数
     */
    int size() {
        return this.links.length;
    }

    /**
     * 将快捷栏槽位映射为 Window 槽位号.
     * lower 区域内前 27 个槽位是背包主区, 快捷栏从偏移 27 开始.
     *
     * @param hotbarSlot 快捷栏槽位号(0-8)
     * @return 对应的窗口槽位号
     * @throws IndexOutOfBoundsException 快捷栏槽位号超出 0-8 时抛出
     */
    int windowSlotAtHotbar(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot >= 9)
            throw new IndexOutOfBoundsException("hotbar slot out of bounds: " + hotbarSlot);

        return this.lowerStart + 27 + hotbarSlot;
    }

    /**
     * 返回布局引用的全部根 GUI.
     *
     * @return 按首次出现顺序去重后的 GUI 列表
     */
    @NotNull
    List<Gui> guis() {
        return this.guis;
    }

    /**
     * Window 中一个连续区域的声明.
     *
     * @param role 区域在 Window 中承担的结构角色
     * @param gui 区域所属的根 GUI
     * @param guiSlot 区域在 GUI 中的起始槽位
     * @param size 区域包含的槽位数
     */
    record Region(@NotNull Role role, @NotNull Gui gui, int guiSlot, int size) {

        public Region {
            // 基本边界: 区域必须完整落在所属 GUI 内
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(gui, "gui");
            if (guiSlot < 0 || size < 0 || guiSlot > gui.area() - size)
                throw new IndexOutOfBoundsException("GUI region out of bounds: slot=" + guiSlot + ", size=" + size);
            // 物理区域不允许为空
            if (role != Role.VIRTUAL && size == 0)
                throw new IllegalArgumentException("physical GUI region must contain at least one slot");
            // lower 区域对应玩家物品栏, 必须恰好是 9x4
            if (role == Role.LOWER && size != LOWER_SIZE.area())
                throw new IllegalArgumentException("lower region must contain 36 slots");
        }

        /**
         * 声明覆盖整个 GUI 的 upper 区域.
         *
         * @param gui 区域所属的根 GUI
         * @return upper 区域声明
         */
        @NotNull
        static Region upper(@NotNull Gui gui) {
            return Region.upper(gui, 0, gui.area());
        }

        /**
         * 声明 GUI 中一段连续槽位作为 upper 区域.
         *
         * @param gui 区域所属的根 GUI
         * @param startSlot 区域在 GUI 中的起始槽位
         * @param size 区域包含的槽位数
         * @return upper 区域声明
         */
        @NotNull
        static Region upper(@NotNull Gui gui, int startSlot, int size) {
            return new Region(Role.UPPER, gui, startSlot, size);
        }

        /**
         * 声明整个 9x4 GUI 作为 lower 区域.
         *
         * @param gui 区域所属的根 GUI, 尺寸必须是 9x4
         * @return lower 区域声明
         * @throws IllegalArgumentException GUI 尺寸不是 9x4 时抛出
         */
        @NotNull
        static Region lower(@NotNull Gui gui) {
            if (!gui.size().equals(LOWER_SIZE))
                throw new IllegalArgumentException("lower GUI must be 9x4");

            return Region.lower(gui, 0, gui.area());
        }

        /**
         * 声明 GUI 中一段连续槽位作为 lower 区域.
         *
         * @param gui 区域所属的根 GUI
         * @param startSlot 区域在 GUI 中的起始槽位
         * @param size 区域包含的槽位数, 必须是 36
         * @return lower 区域声明
         */
        @NotNull
        static Region lower(@NotNull Gui gui, int startSlot, int size) {
            return new Region(Role.LOWER, gui, startSlot, size);
        }

        /**
         * 声明不进入原版菜单协议的 virtual 区域, 覆盖整个 GUI.
         *
         * @param gui 区域所属的根 GUI
         * @return virtual 区域声明
         */
        @NotNull
        static Region virtual(@NotNull Gui gui) {
            return new Region(Role.VIRTUAL, gui, 0, gui.area());
        }

        /**
         * 区域在 Window 中承担的结构角色.
         */
        enum Role {
            UPPER,   // 容器区域
            LOWER,   // 玩家物品栏区域, 布局中必须恰好一个且为 36 槽位
            VIRTUAL  // 虚拟区域, 不进入原版菜单协议, 必须全部位于尾部
        }
    }
}
