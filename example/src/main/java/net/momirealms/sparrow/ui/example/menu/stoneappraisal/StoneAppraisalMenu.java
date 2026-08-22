package net.momirealms.sparrow.ui.example.menu.stoneappraisal;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.util.Scheduling;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.PlayerUpdateReason;
import net.momirealms.sparrow.ui.inventory.event.SlotChange;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 把一块石头砸开, 用老虎机式的动画抽出一样矿物.
 *
 * <p>这个示例的重点是<strong>动画与数据的分工</strong>: 玩家放入石头的那一刻,
 * 石头就被换成了奖励, 结果当场落进容器; 之后播的动画只是盖在那一格上的幕布, 不参与任何决定.
 * 冻结因此才有真正的意义 —— 格子里确实已经有东西了, 是冻结拦住玩家提前把它拿走.
 * 中途关掉菜单也不会丢东西: 结果早就在容器里, 关窗归还照常把它交出去.
 *
 * <p>两层背景各管各的, 正好在同一个菜单里对照:
 * <ul>
 *     <li><strong>Pane 背景</strong>盖住四周的空槽位元素, 也就是那圈黑色玻璃板;</li>
 *     <li><strong>Inventory 背景</strong>只在容器自己空着时露出来, 也就是中间那盏灯的提示.</li>
 * </ul>
 * 两者互不外溢: 容器空着时显示的是它自己的背景, 不会退到 Pane 背景上去.
 *
 * <p>四处值得照抄的用法:
 * <ol>
 *     <li><strong>先写数据, 再盖幕布</strong>. {@link #startSpin} 里先把石头换成奖励, 最后才 play。
 *         关窗兜底之所以只是"把容器里剩下的还回去", 就是因为那时结果已经是既定事实。</li>
 *     <li><strong>非等长节奏用帧函数底座</strong>. 老虎机要由快到慢, 而预设糖的每帧都等长,
 *         所以这里走 {@link AnimationDefinition#of}: 周期取 1 tick, 由 {@link #STEP_STARTS}
 *         这张递增的时刻表决定这一刻该显示第几样矿物。</li>
 *     <li><strong>遮罩作用域跟着动画作用域走</strong>. 动画盖住哪一格, 就冻结那一格所在的 Inventory;
 *         冻结只挡玩家侧, 程序写入照旧, 所以换奖励这一步不受影响。</li>
 *     <li><strong>结束回调恰好触发一次</strong>. 播完与关窗取消都走同一个回调, 解冻因此既不会漏也不会重。</li>
 * </ol>
 */
public final class StoneAppraisalMenu {
    private static final int INPUT_SLOT = 13; // 模板里 X 的位置
    private static final List<Ore> ORES = List.of(
            new Ore(Material.COAL, "煤炭", NamedTextColor.DARK_GRAY, 30),
            new Ore(Material.RAW_COPPER, "粗铜", NamedTextColor.GOLD, 24),
            new Ore(Material.RAW_IRON, "粗铁", NamedTextColor.WHITE, 20),
            new Ore(Material.REDSTONE, "红石", NamedTextColor.RED, 12),
            new Ore(Material.LAPIS_LAZULI, "青金石", NamedTextColor.BLUE, 8),
            new Ore(Material.RAW_GOLD, "粗金", NamedTextColor.YELLOW, 4),
            new Ore(Material.EMERALD, "绿宝石", NamedTextColor.GREEN, 3),
            new Ore(Material.DIAMOND, "钻石", NamedTextColor.AQUA, 1)
    );
    private static final int TOTAL_WEIGHT = totalWeight();
    // 转盘每一步的起始时刻: 间隔从 2 tick 一路拉到 9 tick, 于是看上去由快到慢
    private static final long[] STEP_STARTS = {0, 2, 4, 6, 8, 10, 13, 16, 19, 23, 27, 32, 38, 45, 53, 62};
    private static final long HOLD_TICKS = 20;                                            // 停在结果上停留多久
    private static final long TOTAL_TICKS = STEP_STARTS[STEP_STARTS.length - 1] + HOLD_TICKS;
    private static final int[] ANIMATED_SLOTS = {INPUT_SLOT};

    private final Player viewer;
    private final VirtualInventory input;                // 只有一格, 单件上限
    private final NormalPane pane;                       // 动画宿主
    private final NormalWindow window;
    private volatile @Nullable AnimationHandle spinning; // 有值即正在转, 结束回调在时钟线程读它

    /**
     * 为指定玩家异步构建并打开一次鉴定菜单.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return Scheduling.async(SparrowExample.INSTANCE, () -> new StoneAppraisalMenu(viewer).window.open())
                .thenCompose(opening -> opening);
    }

    /**
     * 创建输入槽与窗口, 并接上输入过滤和关窗归还.
     *
     * @param viewer 要查看菜单的玩家
     */
    private StoneAppraisalMenu(@NotNull Player viewer) {
        this.viewer = viewer;
        this.input = new VirtualInventory(1);
        this.input.setMaxStackSize(0, 1);                  // 一次只鉴定一块
        this.input.setBackgroundItem(hintItem());          // 容器空着时的提示, 放进东西就被真实内容顶掉
        // 订阅由 Inventory 自己强持有, 回执只用来提前退订, 这里不需要
        this.input.subscribePreUpdate(this::rejectNonStone);
        this.input.subscribePostUpdate(ignoredEvent -> this.scheduleSpin());

        this.pane = Pane.builder(
                        "#########",
                        "####X####",
                        "#########"
                )
                .addIngredient('X', Element.inventory(this.input, 0))
                .build();
        // Pane 背景只管空槽位元素, 也就是 X 以外那一圈
        this.pane.setBackgroundItem(fillerItem());
        this.window = NormalWindow.builder()
                .setTitle(Component.text("石头鉴定", NamedTextColor.DARK_GRAY))
                .setUpperPane(this.pane)
                .addCloseHandler((ignoredWindow, ignoredReason) -> this.closeOut())
                .build(viewer);
    }

    /**
     * 只放石头进来. 转盘转着的时候一律拒绝, 冻结之外再加一道.
     *
     * <p><strong>只管玩家自己的放入</strong>: 换奖励和关窗归还都是程序写入, 它们要照常落地,
     * 被这道过滤拦下的话石头就永远换不成结果.
     *
     * @param event 输入槽的写入前事件
     */
    private void rejectNonStone(@NotNull InventoryPreUpdateEvent event) {
        if (!(event.reason() instanceof PlayerUpdateReason)) {
            return;
        }
        if (this.spinning != null) {
            event.setCancelled(true);
            return;
        }
        SlotChange change = event.changeAt(0);
        if (change == null) {
            return;
        }
        // 只在这一帧读一下类型, 因此用零拷贝的那个访问器; 取空这一格时为 null
        ItemStack after = change.unsafeAfter();
        if (after != null && after.getType() != Material.STONE) {
            event.setCancelled(true);
        }
    }

    /**
     * 输入槽变动后排一次开转检查.
     *
     * <p>事务通知里不适合再改 Inventory, 因此排到玩家自己的实体线程再动手, 那里也正是点击的落点.
     */
    private void scheduleSpin() {
        if (this.spinning != null) {
            return;
        }
        this.viewer.getScheduler().run(SparrowExample.INSTANCE, ignoredTask -> this.startSpin(), null);
    }

    /**
     * 石头还在就开转. 换奖励与抽奖励都发生在这里, 动画之后只负责好看.
     */
    private void startSpin() {
        if (this.spinning != null) {
            return;
        }
        ItemStack placed = this.input.itemAt(0);
        if (placed == null || placed.getType() != Material.STONE) {
            return;
        }

        // 先写数据: 石头当场换成奖励. 之后无论怎么收场, 容器里躺着的都已经是结果
        Ore reward = rollReward();
        this.input.setItem(UpdateReason.Program.INSTANCE, 0, new ItemStack(reward.material()));
        // 遮罩跟着动画走: 动画盖住这一格, 就把这一格所在的 Inventory 对玩家侧关掉, 免得结果被提前拿走
        this.input.frozen(true);

        AnimationHandle handle = this.pane.visual().play(spin(reward));
        this.spinning = handle;
        // 播完与关窗取消都到这儿, 恰好一次
        handle.whenFinished(ignoredReason -> this.settle(handle, reward));
    }

    /**
     * 收场: 解冻并告诉玩家开出了什么. 奖励本身留在容器里等玩家取走.
     *
     * @param handle 这一轮的播放句柄
     * @param reward 开转时就抽定的奖励
     */
    private void settle(@NotNull AnimationHandle handle, @NotNull Ore reward) {
        // 已经换了下一轮时这次回调不再作数
        if (this.spinning != handle) {
            return;
        }
        this.spinning = null;
        this.input.frozen(false);
        // 发消息只是往连接上写一个包, 不碰实体状态, 因此可以就地发
        this.viewer.sendMessage(Component.text("你砸开石头获得了 ", NamedTextColor.GRAY)
                .append(Component.text(reward.title(), reward.color())));
    }

    /**
     * 关窗收尾: 取消动画, 再把容器里剩下的东西还给玩家.
     *
     * <p>动画中途关窗与结果没取走这两种情况在这里合成了同一件事 —— 结果早就在容器里,
     * 归还容器内容自然就把它交出去了.
     */
    private void closeOut() {
        AnimationHandle handle = this.spinning;
        if (handle != null) {
            handle.cancel();
        }
        ItemStack left = this.input.itemAt(0);
        if (left == null || left.isEmpty()) {
            return;
        }
        this.input.setItem(UpdateReason.Program.INSTANCE, 0, null);
        // 关窗处理器跑在玩家自己的实体线程上, 因此可以就地动背包
        Map<Integer, ItemStack> leftover = this.viewer.getInventory().addItem(left);
        for (ItemStack dropped : leftover.values()) {
            this.viewer.getWorld().dropItemNaturally(this.viewer.getLocation(), dropped);
        }
        this.viewer.sendMessage(leftover.isEmpty()
                ? Component.text("鉴定结果已放入你的背包。", NamedTextColor.GRAY)
                : Component.text("背包已满, 鉴定结果掉在了你的脚下。", NamedTextColor.GRAY));
    }

    /**
     * 组一份这一轮专用的转盘动画.
     *
     * <p>预设糖的每帧都等长, 而老虎机要由快到慢, 所以这里用帧函数底座:
     * 周期取 1 tick 意味着每 tick 都问一次"现在该显示什么", 真正的节奏由 {@link #STEP_STARTS} 决定.
     *
     * @param reward 转盘最后要停在的矿物
     * @return 一次性使用的动画描述
     */
    @NotNull
    private static AnimationDefinition spin(@NotNull Ore reward) {
        return AnimationDefinition.of(ANIMATED_SLOTS, 1, TOTAL_TICKS,
                (ignoredOrderIndex, ignoredSlot, elapsedTicks, ignoredActual) -> {
                    int step = stepAt(elapsedTicks);
                    // 最后一步停在真正开出来的那一样, 前面的只是转过去的过场
                    return step >= STEP_STARTS.length - 1
                            ? reward.frame()
                            : ORES.get(step % ORES.size()).frame();
                });
    }

    /**
     * 查这一刻走到了转盘的第几步.
     *
     * @param elapsedTicks 从开转起经过的 tick 数
     * @return 步号, 从 0 开始
     */
    private static int stepAt(long elapsedTicks) {
        // 表很短, 从后往前扫比二分更直接
        for (int step = STEP_STARTS.length - 1; step > 0; step--) {
            if (elapsedTicks >= STEP_STARTS[step]) {
                return step;
            }
        }
        return 0;
    }

    /**
     * 按权重抽一样矿物.
     *
     * @return 这一轮开出来的矿物
     */
    @NotNull
    private static Ore rollReward() {
        int roll = ThreadLocalRandom.current().nextInt(TOTAL_WEIGHT);
        for (int index = 0; index < ORES.size(); index++) {
            Ore ore = ORES.get(index);
            roll -= ore.weight();
            if (roll < 0) {
                return ore;
            }
        }
        return ORES.getFirst();
    }

    /**
     * 统计全部矿物的权重之和.
     *
     * @return 权重之和
     */
    private static int totalWeight() {
        int total = 0;
        for (int index = 0; index < ORES.size(); index++) {
            total += ORES.get(index).weight();
        }
        return total;
    }

    /**
     * 创建四周的黑色玻璃板背景.
     *
     * @return 无名称的黑色玻璃板
     */
    @NotNull
    private static ItemStack fillerItem() {
        ItemStack itemStack = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        return itemStack;
    }

    /**
     * 创建容器空着时显示的提示物品.
     *
     * @return 提示用的光源方块
     */
    @NotNull
    private static ItemStack hintItem() {
        ItemStack itemStack = new ItemStack(Material.LIGHT);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("放入 1 个石头", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Component.text("把一块石头放进这一格, 就会开始鉴定。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("一次只收一块, 其它物品放不进来。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("鉴定期间这一格会被锁住,", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("结果要等动画结束才能取走。", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        )));
        return itemStack;
    }

    /**
     * 转盘上的一样矿物. 帧物品在这里包一次, 之后每一轮转盘共用同一个实例.
     *
     * @param material 矿物材质
     * @param title 面向玩家的名称
     * @param color 名称颜色
     * @param weight 抽中的相对权重
     * @param frame 这一样矿物在转盘上的那一帧
     */
    private record Ore(
            @NotNull Material material,
            @NotNull String title,
            @NotNull NamedTextColor color,
            int weight,
            @NotNull ImmediateItemProvider frame
    ) {

        private Ore(@NotNull Material material, @NotNull String title, @NotNull NamedTextColor color, int weight) {
            this(material, title, color, weight, spinFrame(material, color));
        }

        /**
         * 创建转盘转动时显示的那一帧.
         *
         * @param material 矿物材质
         * @param color 名称颜色
         * @return 常量帧
         */
        @NotNull
        private static ImmediateItemProvider spinFrame(@NotNull Material material, @NotNull NamedTextColor color) {
            ItemStack itemStack = new ItemStack(material);
            itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("鉴定中...", color).decoration(TextDecoration.ITALIC, false));
            return ItemProvider.constant(itemStack);
        }
    }
}
