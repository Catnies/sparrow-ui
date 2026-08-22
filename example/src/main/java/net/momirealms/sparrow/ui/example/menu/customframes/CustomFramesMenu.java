package net.momirealms.sparrow.ui.example.menu.customframes;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.util.Scheduling;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 两个不靠预设糖的动画: 一条会自己找路的贪吃蛇, 和一片可以叠加的水波.
 *
 * <p>两个都走 {@link AnimationDefinition#of} 帧函数底座, 因为预设糖只能表达"整片按同一节奏换帧"
 * 或"按顺序错峰", 而这里每一格显示什么取决于它和某个中心的几何关系, 或取决于一份提前排好的走位表.
 *
 * <p><strong>贪吃蛇</strong>: 开播前先随机撒 5 份食物, 用 BFS 逐个规划出绕开自己身子的路,
 * 吃完后从最近的一侧离场; 整条路线在 {@link SnakePlan} 里一次算完, 帧函数只是按 tick 翻页.
 * 这不是偷懒 —— 帧函数必须是参数的纯函数, 同一 tick 可能被求值零次或多次, 所以它不能自己推进蛇的位置.
 *
 * <p><strong>水波</strong>: 每点一下舞台就<strong>新起一个动画</strong>, 波纹是"到点击点的曼哈顿距离
 * 恰好等于当前半径"的那一圈, 其余格子放行. 多点几下就是通道里叠了好几个动画:
 * 后点的盖在先点的上面, 而它在自己那一圈之外全部放行, 于是先点的波纹从下面透上来 ——
 * <strong>叠加是动画通道自带的, 不需要这个示例做任何合成</strong>. 54 个中心各有一份常量描述,
 * 点击时直接取用, 不在点击现场拼描述.
 */
public final class CustomFramesMenu {
    private static final int WIDTH = 9;
    private static final int HEIGHT = 6;
    private static final int AREA = WIDTH * HEIGHT;
    private static final int[] ALL_SLOTS = allSlots();
    private static final int MODE_IDLE = -1;
    private static final int MODE_SNAKE = 0;
    private static final int MODE_RIPPLE = 1;

    // 贪吃蛇
    private static final long SNAKE_PERIOD = 2;
    private static final int SNAKE_START_LENGTH = 3;
    private static final int SNAKE_FOOD_COUNT = 5;
    private static final ImmediateItemProvider SNAKE_HEAD = frame(Material.LIME_CONCRETE, "蛇头", NamedTextColor.GREEN);
    private static final ImmediateItemProvider SNAKE_BODY = frame(Material.GREEN_CONCRETE, "蛇身", NamedTextColor.DARK_GREEN);
    private static final ImmediateItemProvider SNAKE_FOOD = frame(Material.APPLE, "食物", NamedTextColor.RED);

    // 水波
    private static final long RIPPLE_PERIOD = 2;
    private static final int RIPPLE_RINGS = WIDTH + HEIGHT;  // 够波纹走出舞台最远的那个角
    private static final ImmediateItemProvider RIPPLE_FRAME = frame(Material.BLUE_STAINED_GLASS_PANE, "波纹", NamedTextColor.BLUE);
    private static final ItemStack WATER_SURFACE = named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text("水面", NamedTextColor.AQUA));
    private static final ItemStack BLANK = ItemStack.empty();
    // 中心是固定的 54 个格子, 描述因此可以全部预建; 点击时直接取用
    private static final List<AnimationDefinition> RIPPLES = buildRipples();

    private final Player viewer;
    private final MutableSignal<Integer> mode;                         // 当前选中的展示
    private final NormalPane stage;                                    // 动画宿主, 也是上方 6 行
    private final NormalWindow window;
    private final CopyOnWriteArrayList<AnimationHandle> playing = new CopyOnWriteArrayList<>(); // 在播的全部动画, 水波会同时有好几个

    /**
     * 为指定玩家异步构建并打开一次展示.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return Scheduling.async(SparrowExample.INSTANCE, () -> new CustomFramesMenu(viewer).window.open())
                .thenCompose(opening -> opening);
    }

    /**
     * 创建舞台与控制栏, 并把关窗收尾接上.
     *
     * @param viewer 要查看菜单的玩家
     */
    private CustomFramesMenu(@NotNull Player viewer) {
        this.viewer = viewer;
        this.mode = Signal.of(MODE_IDLE);
        this.stage = Pane.empty(WIDTH, HEIGHT);
        // 每格一个自己知道坐标的 Item: 水波要靠它接住点击, 底色也由它按当前展示切换
        for (int slot = 0; slot < AREA; slot++) {
            this.stage.setElement(slot, new Element.Item(this.buildStageItem(slot)));
        }
        this.window = NormalWindow.builder()
                .setTitle(Component.text("自定义帧动画"))
                .setUpperPane(this.stage)
                .setLowerPane(this.buildControlPane())
                // 动画播在 Pane 上, 共享宿主不随窗口关闭而终结, 收尾得自己做
                .addCloseHandler((ignoredWindow, ignoredReason) -> this.stopAll())
                .build(viewer);
    }

    /**
     * 创建舞台上的一格.
     *
     * @param slot 这一格在舞台上的槽位
     * @return 会跟着展示切换底色, 并把点击交给水波的 Item
     */
    @NotNull
    private Item buildStageItem(int slot) {
        return Item.builder()
                .dependsOn(this.mode)
                // 贪吃蛇要空白背景, 水波要一片水面; 动画盖上来时这一层看不见
                .setItemProvider(ignoredContext -> this.mode.get() == MODE_RIPPLE ? WATER_SURFACE : BLANK)
                .addClickHandler(ignoredClick -> this.onStageClick(slot))
                .build();
    }

    /**
     * 创建下方控制栏. 除按钮外一律留空.
     *
     * @return 窗口的下部 Pane
     */
    @NotNull
    private NormalPane buildControlPane() {
        // '#' 没有绑定任何配料, 因此是空槽位
        Pane.Builder<NormalPane, ?> builder = Pane.builder(
                "#########",
                "####I####",
                "#########",
                "AB######X"
        );
        builder.addIngredient('I', this.buildGuideItem());
        builder.addIngredient('A', this.buildSnakeButton());
        builder.addIngredient('B', this.buildRippleButton());
        builder.addIngredient('X', this.buildStopButton());
        return builder.build();
    }

    /**
     * 创建说明物品.
     *
     * @return 静态的说明 Item
     */
    @NotNull
    private Item buildGuideItem() {
        ItemStack itemStack = named(Material.BOOK, Component.text("高级动画展示", NamedTextColor.AQUA));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                gray("这两个效果都不是预设糖能表达的,"),
                gray("它们直接写帧函数: 每一格显示什么由"),
                gray("它自己的坐标算出来。"),
                Component.empty(),
                gray("贪吃蛇的走位在开播前一次算完, 因为"),
                gray("帧函数必须是纯的, 不能自己往前走。"),
                Component.empty(),
                gray("水波的叠加是动画通道自带的: 后点的"),
                gray("盖住先点的, 而它在自己那圈之外放行,"),
                gray("先点的波纹就从下面透上来。")
        )));
        return Item.simple(itemStack);
    }

    /**
     * 创建贪吃蛇按钮.
     *
     * @return 随展示状态自动更新的按钮
     */
    @NotNull
    private Item buildSnakeButton() {
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    ItemStack itemStack = named(Material.SLIME_BALL, Component.text("贪吃蛇", NamedTextColor.GREEN));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            gray("随机撒 5 份食物, 一条长度 3 的蛇"),
                            gray("用 BFS 逐个规划出绕开自己的路,"),
                            gray("吃完后从最近的一侧离场。"),
                            Component.empty(),
                            this.mode.get() == MODE_SNAKE
                                    ? Component.text("点击重新跑一遍", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                                    : Component.text("点击播放", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.playSnake())
                .build();
    }

    /**
     * 创建水波按钮.
     *
     * @return 随展示状态自动更新的按钮
     */
    @NotNull
    private Item buildRippleButton() {
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    boolean active = this.mode.get() == MODE_RIPPLE;
                    ItemStack itemStack = named(Material.WATER_BUCKET, Component.text("水波", NamedTextColor.AQUA));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            gray("舞台变成一片水面, 点哪里哪里起波纹。"),
                            gray("连点几下就是几个动画叠在一起,"),
                            gray("互相透着显示, 不做干涉计算。"),
                            Component.empty(),
                            active
                                    ? Component.text("▶ 点击上方水面试试", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                                    : Component.text("点击进入", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.enterRipple())
                .build();
    }

    /**
     * 创建停止按钮.
     *
     * @return 随展示状态自动更新的按钮
     */
    @NotNull
    private Item buildStopButton() {
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    ItemStack itemStack = named(Material.BARRIER, Component.text("停止", NamedTextColor.RED));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            gray("取消全部动画并清空舞台。"),
                            Component.empty(),
                            this.mode.get() == MODE_IDLE
                                    ? gray("舞台已经是空的。")
                                    : Component.text("点击停止", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.stopAll())
                .build();
    }

    /**
     * 跑一遍贪吃蛇. 走位每次重新排, 因此描述也只能这一次用.
     */
    private void playSnake() {
        this.stopAll();
        this.mode.set(MODE_SNAKE);
        SnakePlan plan = SnakePlan.roll(WIDTH, HEIGHT, SNAKE_START_LENGTH, SNAKE_FOOD_COUNT);
        AnimationHandle handle = this.play(AnimationDefinition.of(ALL_SLOTS, SNAKE_PERIOD, plan.stepCount() * SNAKE_PERIOD,
                (ignoredOrderIndex, slot, elapsedTicks, ignoredActual) -> switch (plan.cellAt((int) (elapsedTicks / SNAKE_PERIOD), slot)) {
                    case SnakePlan.HEAD -> SNAKE_HEAD;
                    case SnakePlan.BODY -> SNAKE_BODY;
                    case SnakePlan.FOOD -> SNAKE_FOOD;
                    default -> null; // 放行: 露出舞台本身的空白
                }));
        this.playSound(org.bukkit.Sound.ENTITY_SLIME_SQUISH, 1.0f);
        // 音效是副作用, 帧函数必须保持纯, 所以按走位表另排一条时间轴, 每吃一份升半音
        int[] eatSteps = plan.eatSteps();
        for (int index = 0; index < eatSteps.length; index++) {
            float pitch = 1.0f + index * 0.15f;
            this.playLater(handle, eatSteps[index] * SNAKE_PERIOD, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, pitch);
        }
        // 只有自己跑完才响离场音, 中途被停掉不响
        handle.whenFinished(reason -> {
            if (reason == AnimationHandle.FinishReason.COMPLETED) {
                this.playSound(org.bukkit.Sound.ENTITY_SLIME_JUMP, 0.8f);
            }
        });
    }

    /**
     * 切到水波: 铺一片水面, 等玩家点。
     */
    private void enterRipple() {
        this.stopAll();
        this.mode.set(MODE_RIPPLE);
    }

    /**
     * 玩家点了舞台上的一格.
     *
     * @param slot 被点的舞台槽位
     */
    private void onStageClick(int slot) {
        if (this.mode.get() != MODE_RIPPLE) {
            return;
        }
        this.play(RIPPLES.get(slot));
        // 点得越靠上音越高, 连点一串就像在水面上敲出音阶
        this.playSound(org.bukkit.Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.4f - slot / WIDTH * 0.12f);
    }

    /**
     * 起一个动画并记下句柄, 播完自己从在播清单里退出去.
     *
     * @param definition 要播放的描述
     * @return 这次播放的句柄
     */
    @NotNull
    private AnimationHandle play(@NotNull AnimationDefinition definition) {
        AnimationHandle handle = this.stage.visual().play(definition);
        this.playing.add(handle);
        handle.whenFinished(ignoredReason -> this.playing.remove(handle));
        return handle;
    }

    /**
     * 给玩家放一个音效.
     *
     * <p>放音只是往连接上写一个包, 不碰实体状态, 因此哪个线程都可以就地放.
     *
     * @param type 音效
     * @param pitch 音高
     */
    private void playSound(@NotNull org.bukkit.Sound type, float pitch) {
        this.viewer.playSound(Sound.sound(type, Sound.Source.MASTER, 0.7f, pitch));
    }

    /**
     * 排一个到点才响的音效.
     *
     * <p>动画本身不产生副作用, 帧函数更不能放音(它可能被求值零次或多次),
     * 所以按走位表另排一条时间轴; 到点时先确认这次播放还在, 被提前停掉就不响了.
     *
     * @param handle 这个音效属于哪一次播放
     * @param delayTicks 延迟多少 tick
     * @param type 音效
     * @param pitch 音高
     */
    private void playLater(@NotNull AnimationHandle handle, long delayTicks, @NotNull org.bukkit.Sound type, float pitch) {
        this.viewer.getScheduler().runDelayed(
                SparrowExample.INSTANCE,
                ignoredTask -> {
                    if (this.playing.contains(handle)) {
                        this.playSound(type, pitch);
                    }
                },
                null,
                Math.max(1, delayTicks)
        );
    }

    /**
     * 取消全部在播动画并把舞台清空.
     */
    private void stopAll() {
        List<AnimationHandle> snapshot = new ArrayList<>(this.playing);
        this.playing.clear();
        for (int index = 0; index < snapshot.size(); index++) {
            snapshot.get(index).cancel();
        }
        this.mode.set(MODE_IDLE);
    }

    /**
     * 预建 54 份水波描述, 一个中心一份.
     *
     * @return 按中心槽位排列的描述
     */
    @NotNull
    private static List<AnimationDefinition> buildRipples() {
        List<AnimationDefinition> ripples = new ArrayList<>(AREA);
        for (int center = 0; center < AREA; center++) {
            ripples.add(ripple(center));
        }
        return List.copyOf(ripples);
    }

    /**
     * 组一份以某一格为中心的水波.
     *
     * <p>波纹是"到中心的曼哈顿距离恰好等于当前半径"的那一圈, 别的格子放行.
     * 放行正是叠加得以成立的原因: 一圈之外什么都不画, 先点的波纹于是从下面透上来.
     *
     * @param center 中心槽位
     * @return 常量描述
     */
    @NotNull
    private static AnimationDefinition ripple(int center) {
        int centerX = center % WIDTH;
        int centerY = center / WIDTH;
        return AnimationDefinition.of(ALL_SLOTS, RIPPLE_PERIOD, RIPPLE_RINGS * RIPPLE_PERIOD,
                (ignoredOrderIndex, slot, elapsedTicks, ignoredActual) -> {
                    long radius = elapsedTicks / RIPPLE_PERIOD;
                    int distance = Math.abs(slot % WIDTH - centerX) + Math.abs(slot / WIDTH - centerY);
                    return distance == radius ? RIPPLE_FRAME : null;
                });
    }

    /**
     * 舞台的全部槽位.
     *
     * @return 0 到 53
     */
    private static int @NotNull [] allSlots() {
        int[] slots = new int[AREA];
        for (int slot = 0; slot < AREA; slot++) {
            slots[slot] = slot;
        }
        return slots;
    }

    /**
     * 创建一个带名称的常量帧.
     *
     * @param material 帧材质
     * @param title 帧名称
     * @param color 名称颜色
     * @return 常量帧
     */
    @NotNull
    private static ImmediateItemProvider frame(@NotNull Material material, @NotNull String title, @NotNull NamedTextColor color) {
        return ItemProvider.constant(named(material, Component.text(title, color)));
    }

    /**
     * 创建一个带名称的物品.
     *
     * @param material 物品材质
     * @param name 物品名称
     * @return 设置好名称的物品
     */
    @NotNull
    private static ItemStack named(@NotNull Material material, @NotNull Component name) {
        ItemStack itemStack = new ItemStack(material);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, name.decoration(TextDecoration.ITALIC, false));
        return itemStack;
    }

    /**
     * 创建一行不倾斜的灰色说明文本.
     *
     * @param text 文本内容
     * @return 说明用的组件
     */
    @NotNull
    private static Component gray(@NotNull String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
