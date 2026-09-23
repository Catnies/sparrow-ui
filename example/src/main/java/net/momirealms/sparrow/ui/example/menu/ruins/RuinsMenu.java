package net.momirealms.sparrow.ui.example.menu.ruins;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.util.Components;
import net.momirealms.sparrow.ui.example.util.ItemComponents;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.visual.animation.AnimationDefinition;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 点亮全部符文的两关机关谜题, 每次打开拥有独立进度.
 */
public final class RuinsMenu {
    private static final ItemStack HINT_BRIGHT = item(Material.YELLOW_CONCRETE, "点击这枚符文", NamedTextColor.YELLOW);
    private static final ItemStack HINT_DARK = item(Material.BLACK_CONCRETE, "点击这枚符文", NamedTextColor.YELLOW);
    private static final List<ItemStack> VICTORY_FRAMES = List.of(
            item(Material.WHITE_CONCRETE, "封印解除", NamedTextColor.WHITE),
            item(Material.GLOWSTONE, "封印解除", NamedTextColor.GOLD),
            item(Material.SEA_LANTERN, "封印解除", NamedTextColor.AQUA));

    private final RuinsPuzzle[] progress = {RuinsPuzzle.start(0), RuinsPuzzle.start(1)};
    private final MutableSignal<RuinsPuzzle> puzzle = Signal.of(this.progress[0]);
    private final NormalPane pane = this.buildPane();
    @Nullable private AnimationHandle hintAnimation;
    @Nullable private AnimationHandle victory;

    /**
     * 打开两关遗迹机关盘, 每次打开应创建新的菜单实例.
     *
     * @param viewer 查看菜单的玩家
     * @return 窗口打开结果
     */
    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return Window.builder(this.pane)
                .setTitle(Component.text("遗迹机关盘", NamedTextColor.DARK_GRAY))
                .addCloseHandler((window, reason) -> this.stopAnimations())
                .open(viewer);
    }

    private NormalPane buildPane() {
        return Pane.builder(
                        ".BBBBB.S.",
                        ".BBBBB...",
                        ".BBBBB.T.",
                        ".BBBBB...",
                        ".BBBBB.R.",
                        ".P...N.X."
                )
                .addIngredient('B', (slots, occurrence) -> new Element.Item(this.buildRuneButton(occurrence % 5, occurrence / 5)))
                .addIngredient('S', this.buildStatusItem())
                .addIngredient('T', this.buildHintButton())
                .addIngredient('R', Item.builder()
                        .setItemProviderConstant(item(Material.CLOCK, "重置本题", NamedTextColor.YELLOW,
                                "恢复当前题面, 清零本题步数和提示次数。"))
                        .addClickHandler(click -> {
                            this.stopAnimations();
                            this.puzzle.set(this.puzzle.get().reset());
                        }).build())
                .addIngredient('P', this.buildNavigationButton(-1))
                .addIngredient('N', this.buildNavigationButton(1))
                .addIngredient('X', Item.builder()
                        .setItemProviderConstant(item(Material.BARRIER, "离开遗迹", NamedTextColor.RED,
                                "关闭菜单后, 两关进度都不会保存。"))
                        .addClickHandler(click -> click.window().close()).build())
                .build();
    }

    private Item buildRuneButton(int x, int y) {
        // 每格只依赖尺寸、亮暗和通关状态, 未变化的格子截断失效通知.
        Signal<Integer> appearance = this.puzzle.mapDistinct(state -> {
            int cell = state.cellAt(x, y);
            return state.width() << 3 | (cell < 0 ? 0 : 4 | ((state.lights() >>> cell) & 1) | (state.solved() ? 2 : 0));
        });
        return Item.builder()
                .dependsOn(appearance)
                .setItemProvider(context -> {
                    int value = appearance.get();
                    if ((value & 4) == 0) return ItemUtils.empty();
                    boolean lit = (value & 1) != 0;
                    boolean solved = (value & 2) != 0;
                    int offset = (5 - (value >>> 3)) / 2;
                    return item(lit ? Material.SEA_LANTERN : Material.BLACK_CONCRETE,
                            "符文 " + (y - offset + 1) + " · " + (x - offset + 1) + (lit ? " · 已点亮" : " · 未点亮"),
                            lit ? NamedTextColor.WHITE : NamedTextColor.GRAY,
                            solved ? "封印已经解除！可以重置或切换关卡。" : "点击翻转自身和上下左右相邻的符文。");
                })
                .addClickHandler(click -> {
                    int cell = this.puzzle.get().cellAt(x, y);
                    if (cell >= 0) {
                        this.press(cell, click.player());
                    }
                })
                .build();
    }

    private Item buildStatusItem() {
        return Item.builder()
                .dependsOn(this.puzzle)
                .setItemProvider(context -> {
                    RuinsPuzzle state = this.puzzle.get();
                    return item(state.solved() ? Material.NETHER_STAR : Material.BOOK,
                            "第 " + (state.level() + 1) + " / 2 题 · " + state.difficulty(), NamedTextColor.GOLD,
                            "白色海晶灯为点亮, 黑色方块为熄灭。",
                            "点击会翻转自身及上下左右的符文。",
                            "已点亮 " + Integer.bitCount(state.lights()) + " / " + state.cells() + " · 已走 " + state.moves() + " 步",
                            "本题最少 " + Integer.bitCount(RuinsPuzzle.start(state.level()).solution()) + " 步 · 已用提示 " + state.hints() + " 次",
                            state.solved() ? "封印解除！可以重置或切换关卡。" : "目标是让所有符文同时亮起。");
                }).build();
    }

    private Item buildHintButton() {
        return Item.builder()
                .dependsOn(this.puzzle)
                .setItemProvider(context -> item(Material.GLOWSTONE_DUST, "提示一步", NamedTextColor.YELLOW,
                        this.puzzle.get().solved() ? "封印已经解除, 无需提示。" : "让最短解中的一枚符文黄黑交替闪烁。",
                        "点击棋盘后停止闪烁, 需要时可再次提示。"))
                .addClickHandler(click -> this.showHint())
                .build();
    }

    private Item buildNavigationButton(int direction) {
        return Item.builder()
                .dependsOn(this.puzzle.mapDistinct(RuinsPuzzle::level))
                .setItemProvider(context -> {
                    int target = this.puzzle.get().level() + direction;
                    boolean available = target >= 0 && target < RuinsPuzzle.LEVELS;
                    return item(available ? Material.ARROW : Material.GRAY_DYE,
                            direction < 0 ? "上一题" : "下一题", available ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY,
                            available ? RuinsPuzzle.start(target).difficulty() + " · 切换时保留当前进度。"
                                    : direction < 0 ? "已经是第一题。" : "已经是最后一题。");
                })
                .addClickHandler(click -> this.changeLevel(direction))
                .build();
    }

    private void changeLevel(int direction) {
        RuinsPuzzle current = this.puzzle.get();
        int target = current.level() + direction;
        if (target < 0 || target >= RuinsPuzzle.LEVELS) return;
        this.stopAnimations();
        this.progress[current.level()] = current;
        this.puzzle.set(this.progress[target]);
    }

    private void showHint() {
        RuinsPuzzle state = this.puzzle.get();
        if (state.solved() || this.hintAnimation != null) return;
        int cell = Integer.numberOfTrailingZeros(state.solution());
        this.puzzle.set(state.hinted());
        this.hintAnimation = this.pane.visual().play(AnimationDefinition.loop(
                new int[]{slot(state.width(), cell)}, 6, List.of(HINT_BRIGHT, HINT_DARK)));
    }

    private void press(int cell, Player viewer) {
        RuinsPuzzle current = this.puzzle.get();
        if (current.solved()) return;
        this.stopAnimations();
        RuinsPuzzle next = current.press(cell);
        this.puzzle.set(next);
        if (next.solved()) {
            int[] slots = new int[next.cells()];
            for (int index = 0; index < slots.length; index++) {
                slots[index] = slot(next.width(), index);
            }
            this.victory = this.pane.visual().play(AnimationDefinition.staggeredFrames(slots, 2, 2, VICTORY_FRAMES, null));
            Components.playSound(viewer, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        } else {
            Components.playSound(viewer, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 0.7f + cell % current.width() * 0.15f);
        }
    }

    private void stopAnimations() {
        if (this.hintAnimation != null) {
            this.hintAnimation.cancel();
            this.hintAnimation = null;
        }
        if (this.victory != null) {
            this.victory.cancel();
            this.victory = null;
        }
    }

    // 字符布局中的棋盘区从第 0 行、第 1 列开始, 小棋盘居中放置.
    private static int slot(int width, int cell) {
        int offset = (5 - width) / 2;
        return (cell / width + offset) * 9 + cell % width + offset + 1;
    }

    private static ItemStack item(Material material, String title, NamedTextColor color, String... description) {
        ItemStack stack = ItemComponents.create(material);
        ItemComponents.name(stack, Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        ItemComponents.lore(stack, Arrays.stream(description)
                .<Component>map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        return stack;
    }
}
