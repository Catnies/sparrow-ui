package net.momirealms.sparrow.ui.example.menu.animation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.util.Components;
import net.momirealms.sparrow.ui.example.util.ItemComponents;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.pane.SlotPatterns;
import net.momirealms.sparrow.ui.pane.SlotSequence;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class AnimationMenu {
    private static final PaneSize STAGE_SIZE = new PaneSize(9, 6); // 舞台就是上方那个 6 行大箱子
    private static final List<ItemStack> RAINBOW = List.of(
            plain(Material.RED_STAINED_GLASS_PANE),
            plain(Material.ORANGE_STAINED_GLASS_PANE),
            plain(Material.YELLOW_STAINED_GLASS_PANE),
            plain(Material.LIME_STAINED_GLASS_PANE),
            plain(Material.LIGHT_BLUE_STAINED_GLASS_PANE),
            plain(Material.BLUE_STAINED_GLASS_PANE),
            plain(Material.PURPLE_STAINED_GLASS_PANE)
    ); // 彩虹七色, 同步与循环两种预设都用它

    // 描述不可变且可以反复播放, 因此全部提成常量: 帧物品只在这里拷贝一次
    private static final AnimationDefinition RAINBOW_SWEEP =
            AnimationDefinition.frames(SlotSequence.all(STAGE_SIZE), 4, RAINBOW);
    private static final AnimationDefinition CHECKERBOARD_REVEAL =
            AnimationDefinition.reveal(checkerboardOrder(), 1, plain(Material.GRAY_STAINED_GLASS_PANE));
    private static final AnimationDefinition COLUMN_LIGHT_UP = AnimationDefinition.staggeredFrames(
            SlotSequence.all(STAGE_SIZE).transform(SlotPatterns.COLUMN_MAJOR),
            1,
            1,
            List.of(
                    plain(Material.WHITE_STAINED_GLASS_PANE),
                    plain(Material.YELLOW_STAINED_GLASS_PANE),
                    plain(Material.ORANGE_STAINED_GLASS_PANE),
                    plain(Material.RED_STAINED_GLASS_PANE)
            ),
            plain(Material.BLACK_STAINED_GLASS_PANE)
    );
    private static final AnimationDefinition BORDER_LOOP =
            AnimationDefinition.loop(SlotSequence.borders(STAGE_SIZE), 6, RAINBOW);
    private static final AnimationDefinition STAGE_BLINK = AnimationDefinition.loop(
            SlotSequence.all(STAGE_SIZE),
            5,
            List.of(plain(Material.LIME_STAINED_GLASS_PANE), plain(Material.GREEN_STAINED_GLASS_PANE))
    );

    // 控制栏选项
    private static final List<Show> SHOWS = List.of(
            new Show(
                    "同步帧序列",
                    Material.FIREWORK_ROCKET,
                    NamedTextColor.YELLOW,
                    List.of("全部 54 格同时换色, 走完七色自动结束。", "这一族适合单格的爆炸、开箱一类过场。"),
                    List.of(RAINBOW_SWEEP)
            ),
            new Show(
                    "逐格出现",
                    Material.SPYGLASS,
                    NamedTextColor.WHITE,
                    List.of("先用灰玻璃盖满舞台, 再一格格放行。", "顺序是先偶数棋盘格再奇数棋盘格。"),
                    List.of(CHECKERBOARD_REVEAL)
            ),
            new Show(
                    "逐列点亮",
                    Material.TORCH,
                    NamedTextColor.GOLD,
                    List.of("每格轮到之前是暗的, 轮到后闪一小段再放行。", "闪光从左到右经过舞台, 然后露出底色。"),
                    List.of(COLUMN_LIGHT_UP)
            ),
            new Show(
                    "边框循环",
                    Material.CLOCK,
                    NamedTextColor.LIGHT_PURPLE,
                    List.of("只盖住边框, 中间的舞台原样露着。", "它不会自然结束, 只能点停止或换一种。"),
                    List.of(BORDER_LOOP)
            ),
            new Show(
                    "双层叠加",
                    Material.BEACON,
                    NamedTextColor.AQUA,
                    List.of(
                            "同一块舞台上同时播两个动画。",
                            "先起的全窗闪烁在下, 后起的逐列点亮在上;",
                            "上层走完的格子放行, 就露出下面还在闪的那层。",
                            "逐列走完后整块舞台都在闪, 要点停止才停。"
                    ),
                    List.of(STAGE_BLINK, COLUMN_LIGHT_UP)
            )
    );

    // 舞台与展示模式
    private static final int WIDTH = 9;
    private static final int HEIGHT = 6;
    private static final int AREA = WIDTH * HEIGHT;
    private static final int[] ALL_SLOTS = allSlots();
    private static final int MODE_IDLE = -1;
    private static final int MODE_SNAKE = SHOWS.size();
    private static final int MODE_RIPPLE = MODE_SNAKE + 1;

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
    private static final ItemStack STAGE_BACKGROUND = named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text("舞台", NamedTextColor.AQUA));
    private static final ItemStack BLANK = ItemUtils.empty();
    // 中心是固定的 54 个格子, 描述因此可以全部预建; 点击时直接取用
    private static final List<AnimationDefinition> RIPPLES = buildRipples();

    // 当前菜单状态
    private Player viewer;
    private final MutableSignal<Integer> mode;                         // 当前选中的展示
    private final NormalPane stage;                                    // 动画宿主, 也是上方 6 行
    @Nullable private volatile List<AnimationHandle> currentPreset;
    private final CopyOnWriteArrayList<AnimationHandle> playing = new CopyOnWriteArrayList<>(); // 在播的全部动画, 水波会同时有好几个

    public AnimationMenu() {
        this.mode = Signal.of(MODE_IDLE);
        this.stage = Pane.empty(WIDTH, HEIGHT);
        // 每格一个自己知道坐标的 Item: 水波要靠它接住点击, 底色也由它按当前展示切换
        for (int slot = 0; slot < AREA; slot++) {
            this.stage.setElement(slot, new Element.Item(this.buildStageItem(slot)));
        }
    }

    /**
     * 为本次菜单实例打开动画舞台, 每次打开应创建新的实例.
     *
     * @param viewer 查看菜单的玩家
     * @return 窗口打开结果
     */
    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        this.viewer = viewer;
        return NormalWindow.builder()
                .setTitle(Component.text("动画展示"))
                .setUpperPane(this.stage)
                .setLowerPane(this.buildControlPane())
                // 动画播在 Pane 上, 共享宿主不随窗口关闭而终结, 收尾得自己做
                .addCloseHandler((ignoredWindow, ignoredReason) -> this.stopAll())
                .build(viewer).open();
    }

    @NotNull
    private Item buildStageItem(int slot) {
        return Item.builder()
                .dependsOn(this.mode)
                // 贪吃蛇要空白背景, 水波要一片水面; 动画盖上来时这一层看不见
                .setItemProvider(ignoredContext -> this.mode.get() == MODE_SNAKE ? BLANK : this.mode.get() == MODE_RIPPLE ? WATER_SURFACE : STAGE_BACKGROUND)
                .addClickHandler(ignoredClick -> this.onStageClick(slot))
                .build();
    }

    @NotNull
    private NormalPane buildControlPane() {
        // '#' 没有绑定任何配料, 因此是空槽位
        Pane.Builder<NormalPane, ?> builder = Pane.builder(
                "#########",
                "####I####",
                "#########",
                "ABCDE#FGX"
        );
        builder.addIngredient('I', this.buildGuideItem());
        for (int index = 0; index < SHOWS.size(); index++) {
            builder.addIngredient((char) ('A' + index), this.buildShowButton(index));
        }
        builder.addIngredient('F', this.buildSnakeButton());
        builder.addIngredient('G', this.buildRippleButton());
        builder.addIngredient('X', this.buildStopButton());
        return builder.build();
    }

    @NotNull
    private Item buildGuideItem() {
        ItemStack itemStack = named(Material.BOOK, Component.text("动画展示", NamedTextColor.AQUA));
        ItemComponents.lore(itemStack, List.of(
                gray("左侧五个按钮展示预设动画, 右侧两个展示自定义帧。"),
                gray("选择贪吃蛇可以观看自动寻路, 选择水波后点击舞台。"),
                gray("切换效果会停止之前的播放, 红色按钮恢复舞台。")
        ));
        return Item.simple(itemStack);
    }

    @NotNull
    private Item buildShowButton(int index) {
        Show show = SHOWS.get(index);
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    boolean playing = this.mode.get() == index;
                    List<Component> lore = new ArrayList<>();
                    List<String> description = show.description();
                    for (int line = 0; line < description.size(); line++) {
                        lore.add(gray(description.get(line)));
                    }
                    lore.add(Component.empty());
                    lore.add(playing
                            ? Component.text("▶ 正在播放", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                            : Component.text("点击播放", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

                    ItemStack itemStack = named(show.icon(), Component.text("预设 · " + show.title(), show.color()));
                    ItemComponents.lore(itemStack, lore);
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.playPreset(index))
                .build();
    }

    private void playPreset(int index) {
        this.stopAll();
        List<AnimationDefinition> definitions = SHOWS.get(index).definitions();
        List<AnimationHandle> handles = new ArrayList<>(definitions.size());
        for (int layer = 0; layer < definitions.size(); layer++) {
            handles.add(this.play(definitions.get(layer)));
        }
        List<AnimationHandle> batch = List.copyOf(handles);
        this.currentPreset = batch;
        this.mode.set(index);

        // 整批都结束了才把按钮状态复位; 已经换成别的效果时旧批次不再作数.
        // 回调可能落在时钟线程, 信号写入本身是线程安全的
        AtomicInteger remaining = new AtomicInteger(batch.size());
        for (int layer = 0; layer < batch.size(); layer++) {
            batch.get(layer).whenFinished(ignoredReason -> {
                if (remaining.decrementAndGet() == 0 && this.currentPreset == batch) {
                    this.mode.set(MODE_IDLE);
                }
            });
        }
    }

    @NotNull
    private Item buildSnakeButton() {
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    ItemStack itemStack = named(Material.SLIME_BALL, Component.text("自定义帧 · 贪吃蛇", NamedTextColor.GREEN));
                    ItemComponents.lore(itemStack, List.of(
                            gray("随机撒 5 份食物, 一条长度 3 的蛇"),
                            gray("它会绕开自己的身体, 逐个吃掉食物,"),
                            gray("吃完后从最近的一侧离场。"),
                            Component.empty(),
                            this.mode.get() == MODE_SNAKE
                                    ? Component.text("点击重新跑一遍", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                                    : Component.text("点击播放", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    ));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.playSnake())
                .build();
    }

    @NotNull
    private Item buildRippleButton() {
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    boolean active = this.mode.get() == MODE_RIPPLE;
                    ItemStack itemStack = named(Material.WATER_BUCKET, Component.text("自定义帧 · 水波", NamedTextColor.AQUA));
                    ItemComponents.lore(itemStack, List.of(
                            gray("舞台变成一片水面, 点哪里哪里起波纹。"),
                            gray("连点几下就是几个动画叠在一起,"),
                            gray("互相透着显示, 不做干涉计算。"),
                            Component.empty(),
                            active
                                    ? Component.text("▶ 点击上方水面试试", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                                    : Component.text("点击进入", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    ));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.enterRipple())
                .build();
    }

    @NotNull
    private Item buildStopButton() {
        return Item.builder()
                .dependsOn(this.mode)
                .setItemProvider(ignoredContext -> {
                    ItemStack itemStack = named(Material.BARRIER, Component.text("停止", NamedTextColor.RED));
                    ItemComponents.lore(itemStack, List.of(
                            gray("取消全部动画并恢复舞台。"),
                            Component.empty(),
                            this.mode.get() == MODE_IDLE
                                    ? gray("当前没有动画在播放。")
                                    : Component.text("点击停止", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    ));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.stopAll())
                .build();
    }

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

    private void enterRipple() {
        this.stopAll();
        this.mode.set(MODE_RIPPLE);
    }

    private void onStageClick(int slot) {
        if (this.mode.get() != MODE_RIPPLE) {
            return;
        }
        this.play(RIPPLES.get(slot));
        // 点得越靠上音越高, 连点一串就像在水面上敲出音阶
        this.playSound(org.bukkit.Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.4f - slot / WIDTH * 0.12f);
    }

    @NotNull
    private AnimationHandle play(@NotNull AnimationDefinition definition) {
        AnimationHandle handle = this.stage.visual().play(definition);
        this.playing.add(handle);
        handle.whenFinished(ignoredReason -> this.playing.remove(handle));
        return handle;
    }

    private void playSound(@NotNull org.bukkit.Sound type, float pitch) {
        Components.playSound(this.viewer, type, 0.7f, pitch);
    }

    private void playLater(@NotNull AnimationHandle handle, long delayTicks, @NotNull org.bukkit.Sound type, float pitch) {
        SparrowUI.getInstance().scheduler().platform().runLater(() -> {
                    if (this.playing.contains(handle)) {
                        this.playSound(type, pitch);
                    }
                },
                () -> { }, Math.max(1, delayTicks), this.viewer
        );
    }

    private void stopAll() {
        this.currentPreset = null;
        List<AnimationHandle> snapshot = new ArrayList<>(this.playing);
        this.playing.clear();
        for (int index = 0; index < snapshot.size(); index++) {
            snapshot.get(index).cancel();
        }
        this.mode.set(MODE_IDLE);
    }

    @NotNull
    private static List<AnimationDefinition> buildRipples() {
        List<AnimationDefinition> ripples = new ArrayList<>(AREA);
        for (int center = 0; center < AREA; center++) {
            ripples.add(ripple(center));
        }
        return List.copyOf(ripples);
    }

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

    private static int @NotNull [] allSlots() {
        int[] slots = new int[AREA];
        for (int slot = 0; slot < AREA; slot++) {
            slots[slot] = slot;
        }
        return slots;
    }

    @NotNull
    private static ImmediateItemProvider frame(@NotNull Material material, @NotNull String title, @NotNull NamedTextColor color) {
        return ItemProvider.constant(named(material, Component.text(title, color)));
    }

    @NotNull
    private static ItemStack named(@NotNull Material material, @NotNull Component name) {
        ItemStack itemStack = ItemComponents.create(material);
        ItemComponents.name(itemStack, name.decoration(TextDecoration.ITALIC, false));
        return itemStack;
    }

    @NotNull
    private static Component gray(@NotNull String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    @NotNull
    private static SlotSequence checkerboardOrder() {
        SlotSequence all = SlotSequence.all(STAGE_SIZE);
        return SlotSequence.concat(
                all.transform(SlotPatterns.CHECKERBOARD_EVEN),
                all.transform(SlotPatterns.CHECKERBOARD_ODD)
        );
    }

    @NotNull
    private static ItemStack plain(@NotNull Material material) {
        return named(material, Component.empty());
    }

    private record Show(
            @NotNull String title,
            @NotNull Material icon,
            @NotNull NamedTextColor color,
            @NotNull List<String> description,
            @NotNull List<AnimationDefinition> definitions
    ) {
    }
}
