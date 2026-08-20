package net.momirealms.sparrow.ui.example.menu.animationcarousel;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.util.Scheduling;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.pane.SlotPatterns;
import net.momirealms.sparrow.ui.pane.SlotSequence;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用一块 6 行的舞台轮播动画效果, 快捷栏一格一种.
 *
 * <p>前四格各演示一种预设:
 * <ul>
 *     <li>{@link AnimationDefinition#frames} 全窗同步走一遍彩虹, 走完自动结束;</li>
 *     <li>{@link AnimationDefinition#reveal} 先用灰玻璃盖满, 再按棋盘两轮逐格放行;</li>
 *     <li>{@link AnimationDefinition#staggeredFrames} 逐列点亮, 每格闪一小段再放行;</li>
 *     <li>{@link AnimationDefinition#loop} 只盖边框且不会自然结束, 用来看"动画只管自己声明的槽位".</li>
 * </ul>
 *
 * <p>第五格是<strong>两个动画叠在同一块舞台上</strong>: 先起一层全窗闪烁, 再叠一层逐列点亮.
 * 上面那层走完的格子放行, 于是露出下面还在闪的那一层 —— 这就是"后开始的盖住先开始的, 帧放行处逐层下落".
 *
 * <p>三处值得照抄的用法:
 * <ol>
 *     <li><strong>动画描述是不可变的, 提成常量复用</strong>. 帧物品在描述构造时就拷贝好了,
 *         在点击处理器里现建描述等于每次点击都重新拷贝一遍全部帧. 逐列点亮那一份描述
 *         同时被第三格和第五格用着, 各自播各自的, 互不干扰.</li>
 *     <li><strong>动画只是盖在上面的幕布, 从不改数据</strong>. 舞台格子的内容全程不动,
 *         动画结束后原样露出来, 中途关掉菜单也不会留下痕迹.</li>
 *     <li><strong>共享宿主上的播放要自己负责收尾</strong>. 动画播在 Pane 上, 它不随窗口关闭而终结,
 *         所以关窗时显式取消; 无限循环的那种尤其需要.</li>
 * </ol>
 */
public final class AnimationCarouselMenu {
    private static final PaneSize STAGE_SIZE = new PaneSize(9, 6); // 舞台就是上方那个 6 行大箱子
    private static final int NOTHING_PLAYING = -1;                 // playingIndex 的空值
    private static final Item STAGE_FILLER = Item.simple(named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text("舞台", NamedTextColor.AQUA)));
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

    private static final List<Show> SHOWS = List.of(
            new Show(
                    "同步帧序列",
                    Material.FIREWORK_ROCKET,
                    NamedTextColor.YELLOW,
                    List.of("全部 54 格同时换色, 走完七色自动结束。", "这一族适合单格的爆炸、开箱一类过场。"),
                    List.of("frames(全部槽位, 周期 4, 七色)"),
                    List.of(RAINBOW_SWEEP)
            ),
            new Show(
                    "逐格出现",
                    Material.SPYGLASS,
                    NamedTextColor.WHITE,
                    List.of("先用灰玻璃盖满舞台, 再一格格放行。", "顺序是先偶数棋盘格再奇数棋盘格。"),
                    List.of("reveal(棋盘两轮, 间隔 1, 灰玻璃)"),
                    List.of(CHECKERBOARD_REVEAL)
            ),
            new Show(
                    "逐列点亮",
                    Material.TORCH,
                    NamedTextColor.GOLD,
                    List.of("每格轮到之前是暗的, 轮到后闪一小段再放行。", "错峰必须是周期的整数倍, 否则阶段切换会迟到。"),
                    List.of("staggeredFrames(逐列, 错峰 1, 周期 1, 四帧)"),
                    List.of(COLUMN_LIGHT_UP)
            ),
            new Show(
                    "边框循环",
                    Material.CLOCK,
                    NamedTextColor.LIGHT_PURPLE,
                    List.of("只盖住边框, 中间的舞台原样露着。", "它不会自然结束, 只能点停止或换一种。"),
                    List.of("loop(边框, 周期 6, 七色)"),
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
                    List.of("loop(全部槽位, 周期 5, 两色)", "staggeredFrames(同第三格那一份描述)"),
                    List.of(STAGE_BLINK, COLUMN_LIGHT_UP)
            )
    );

    private final MutableSignal<Integer> playingIndex;          // 当前在播的那一种在 SHOWS 中的下标
    private final NormalPane stage;                             // 动画的宿主, 也是上方 6 行
    private final NormalWindow window;
    private volatile @Nullable List<AnimationHandle> current;   // 当前这一批播放句柄, 结束回调在时钟线程读它

    /**
     * 为指定玩家异步构建并打开一次轮播展示.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return Scheduling.async(SparrowExample.INSTANCE, () -> new AnimationCarouselMenu(viewer).window.open())
                .thenCompose(opening -> opening);
    }

    /**
     * 创建舞台与控制栏, 并把关窗收尾接上.
     *
     * @param viewer 要查看菜单的玩家
     */
    private AnimationCarouselMenu(@NotNull Player viewer) {
        this.playingIndex = Signal.of(NOTHING_PLAYING);
        this.stage = Pane.filled(STAGE_SIZE.width(), STAGE_SIZE.height(), STAGE_FILLER);
        this.window = NormalWindow.builder()
                .setTitle(Component.text("动画轮播展示"))
                .setUpperPane(this.stage)
                .setLowerPane(this.buildControlPane())
                // 动画播在 Pane 上, 共享宿主不随窗口关闭而终结, 收尾得自己做
                .addCloseHandler(ignoredReason -> this.stopCurrent())
                .build(viewer);
    }

    /**
     * 创建下方控制栏. 除按钮外一律留空, 背景交给客户端原本的空槽位.
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
                "ABCDE###X"
        );
        builder.addIngredient('I', this.buildGuideItem());
        // A 到 E 与 SHOWS 一一对应, 顺序就是快捷栏从左到右
        char[] keys = {'A', 'B', 'C', 'D', 'E'};
        for (int index = 0; index < SHOWS.size(); index++) {
            builder.addIngredient(keys[index], this.buildShowButton(index));
        }
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
        ItemStack itemStack = named(Material.BOOK, Component.text("动画轮播展示", NamedTextColor.AQUA));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                gray("点击快捷栏上的物品切换动画效果。"),
                Component.empty(),
                gray("上方 6 行是动画的舞台, 每一格的内容"),
                gray("全程不会被动画改动: 动画只是盖在上面的"),
                gray("一层幕布, 结束后原样露出来。"),
                Component.empty(),
                gray("动画播在共享的 Pane 上, 因此同时看着"),
                gray("这块舞台的每个人看到的都是同一份。")
        )));
        return Item.simple(itemStack);
    }

    /**
     * 创建一种效果的播放按钮.
     *
     * @param index 该效果在 {@link #SHOWS} 中的下标
     * @return 随播放状态自动更新的按钮
     */
    @NotNull
    private Item buildShowButton(int index) {
        Show show = SHOWS.get(index);
        return Item.builder()
                .dependsOn(this.playingIndex)
                .setItemProvider(ignoredContext -> {
                    boolean playing = this.playingIndex.get() == index;
                    List<Component> lore = new ArrayList<>();
                    List<String> description = show.description();
                    for (int line = 0; line < description.size(); line++) {
                        lore.add(gray(description.get(line)));
                    }
                    lore.add(Component.empty());
                    List<String> calls = show.calls();
                    for (int line = 0; line < calls.size(); line++) {
                        lore.add(Component.text(line == 0 ? "调用: " : "      ", NamedTextColor.DARK_GRAY)
                                .append(Component.text(calls.get(line), NamedTextColor.GRAY))
                                .decoration(TextDecoration.ITALIC, false));
                    }
                    lore.add(Component.empty());
                    lore.add(playing
                            ? Component.text("▶ 正在播放", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                            : Component.text("点击播放", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

                    ItemStack itemStack = named(show.icon(), Component.text(show.title(), show.color()));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.play(index))
                .build();
    }

    /**
     * 创建停止按钮.
     *
     * @return 随播放状态自动更新的按钮
     */
    @NotNull
    private Item buildStopButton() {
        return Item.builder()
                .dependsOn(this.playingIndex)
                .setItemProvider(ignoredContext -> {
                    boolean playing = this.playingIndex.get() != NOTHING_PLAYING;
                    ItemStack itemStack = named(Material.BARRIER, Component.text("停止", NamedTextColor.RED));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            gray("取消当前动画, 舞台立刻恢复原样。"),
                            Component.empty(),
                            playing
                                    ? Component.text("点击停止", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                                    : gray("当前没有动画在播放。")
                    )));
                    return itemStack;
                })
                .addClickHandler(ignoredClick -> this.stopCurrent())
                .build();
    }

    /**
     * 换上一种效果: 先取消在播的, 再按列表顺序起播. 列表里靠前的先起, 因此叠在下面.
     *
     * @param index 要播放的效果在 {@link #SHOWS} 中的下标
     */
    private void play(int index) {
        this.stopCurrent();
        List<AnimationDefinition> definitions = SHOWS.get(index).definitions();
        List<AnimationHandle> handles = new ArrayList<>(definitions.size());
        for (int layer = 0; layer < definitions.size(); layer++) {
            handles.add(this.stage.visual().play(definitions.get(layer)));
        }
        List<AnimationHandle> batch = List.copyOf(handles);
        this.current = batch;
        this.playingIndex.set(index);

        // 整批都结束了才把按钮状态复位; 已经换成别的效果时旧批次不再作数.
        // 回调可能落在时钟线程, 信号写入本身是线程安全的
        AtomicInteger remaining = new AtomicInteger(batch.size());
        for (int layer = 0; layer < batch.size(); layer++) {
            batch.get(layer).whenFinished(ignoredReason -> {
                if (remaining.decrementAndGet() == 0 && this.current == batch) {
                    this.playingIndex.set(NOTHING_PLAYING);
                }
            });
        }
    }

    /**
     * 取消当前这一批动画. 没有动画在播时什么都不做.
     */
    private void stopCurrent() {
        List<AnimationHandle> batch = this.current;
        if (batch == null) {
            return;
        }
        // 先摘下来: 取消会当场触发结束回调, 它认出这已经不是当前批次就不再改按钮状态
        this.current = null;
        for (int layer = 0; layer < batch.size(); layer++) {
            batch.get(layer).cancel();
        }
        this.playingIndex.set(NOTHING_PLAYING);
    }

    /**
     * 创建棋盘两轮的出现顺序: 先走完偶数格, 再走奇数格.
     *
     * @return 覆盖全部舞台槽位的顺序
     */
    @NotNull
    private static SlotSequence checkerboardOrder() {
        SlotSequence all = SlotSequence.all(STAGE_SIZE);
        return SlotSequence.concat(
                all.transform(SlotPatterns.CHECKERBOARD_EVEN),
                all.transform(SlotPatterns.CHECKERBOARD_ODD)
        );
    }

    /**
     * 创建一个只有材质的帧物品.
     *
     * @param material 玻璃板材质
     * @return 名称为空的物品
     */
    @NotNull
    private static ItemStack plain(@NotNull Material material) {
        return named(material, Component.empty());
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

    /**
     * 把一种效果面向玩家的说明与它用到的动画描述放在同一个阅读位置.
     *
     * @param title 按钮名称
     * @param icon 按钮材质
     * @param color 按钮名称颜色
     * @param description 面向玩家的效果说明
     * @param calls 对应的工厂调用, 直接写在 Lore 里方便对照代码
     * @param definitions 这一种效果要同时播放的描述, 靠前的先起播因此叠在下面
     */
    private record Show(
            @NotNull String title,
            @NotNull Material icon,
            @NotNull NamedTextColor color,
            @NotNull List<String> description,
            @NotNull List<String> calls,
            @NotNull List<AnimationDefinition> definitions
    ) {
    }
}
