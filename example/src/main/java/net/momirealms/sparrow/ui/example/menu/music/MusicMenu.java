package net.momirealms.sparrow.ui.example.menu.music;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.util.Components;
import net.momirealms.sparrow.ui.example.util.ItemComponents;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.scheduler.task.SchedulerTask;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.state.Signals;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * 多轨分页音符编辑器, 玩家物品栏显示控制台, 编排和播放状态属于本次打开的窗口.
 */
public final class MusicMenu {
    private static final int VISIBLE_TRACKS = 4;
    private static final int[] TEMPOS = {240, 300, 375, 400, 480, 600, 720}; // BPM 的四倍, 对应每分钟的十六分音符数
    private static final Material[] NOTE_COLORS = {Material.YELLOW_CONCRETE, Material.LIME_CONCRETE, Material.PINK_CONCRETE, Material.LIGHT_BLUE_CONCRETE};

    private final NotePattern demo;
    private final MutableSignal<View> view = Signal.of(new View(NotePattern.empty(), 0, 0));
    private final MutableSignal<Integer> position = Signal.of(-1);
    private final MutableSignal<Integer> tempo = Signal.of(2);
    private final MutableSignal<NoteInstrument> instrument = Signal.of(NoteInstrument.HARP);
    private final Signal<Integer> page = this.view.mapDistinct(View::page);
    private final Signal<Integer> playhead = Signals.combine(this.position, this.page,
            (step, page) -> step >= 0 && step / NotePattern.STEPS == page ? step % NotePattern.STEPS : -1).mapDistinct(value -> value);
    private final NormalPane scorePane = this.buildScorePane();
    private final NormalPane consolePane = this.buildConsolePane();
    @Nullable private SchedulerTask task;
    private int generation;
    private int elapsed;

    MusicMenu(@NotNull NotePattern demo) {
        this.demo = demo;
        this.view.set(new View(demo, 0, 0));
    }

    /**
     * 打开编曲工作台, 每次打开应创建新的菜单实例.
     *
     * @param viewer 查看菜单的玩家
     * @return 窗口打开结果
     */
    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return NormalWindow.builder()
                .setUpperPane(this.scorePane)
                .setLowerPane(this.consolePane)
                .setTitle(Component.text("音符工作台 · 多轨编曲", NamedTextColor.DARK_GRAY))
                .addCloseHandler((window, reason) -> this.stop())
                .open(viewer);
    }

    private NormalPane buildScorePane() {
        return Pane.builder(
                        "U..IA...D",
                        ".BBBBBBBB",
                        "TNNNNNNNN",
                        "TNNNNNNNN",
                        "TNNNNNNNN",
                        "TNNNNNNNN"
                )
                .addIngredient('U', this.buildTrackScrollButton(-1))
                .addIngredient('D', this.buildTrackScrollButton(1))
                .addIngredient('I', this.buildInstrumentButton())
                .addIngredient('A', Item.builder().dependsOn(this.instrument)
                        .setItemProvider(context -> item(Material.LIME_DYE, "添加音轨 · " + this.instrument.get().label(), NamedTextColor.GREEN,
                                "添加一条覆盖全曲的空白轨道。", "同种音色可添加多轨, 用来编写和弦。"))
                        .addClickHandler(click -> {
                            if (click.clickType() != ClickType.LEFT) return;
                            this.stop();
                            View current = this.view.get();
                            NotePattern next = current.pattern().addTrack(this.instrument.get());
                            this.view.set(new View(next, current.page(), Math.max(0, next.tracks() - VISIBLE_TRACKS)));
                        }).build())
                .addIngredient('B', (slots, occurrence) -> new Element.Item(this.buildBeatItem(occurrence)))
                .addIngredient('T', (slots, occurrence) -> new Element.Item(this.buildTrackButton(occurrence)))
                .addIngredient('N', (slots, occurrence) -> new Element.Item(this.buildNoteButton(occurrence)))
                .build();
    }

    private NormalPane buildConsolePane() {
        return Pane.builder(
                        "H...Q...L",
                        ".<.C.>.N.",
                        ".K...E.V.",
                        "P..-S+..X"
                )
                .addIngredient('H', Item.simple(item(Material.WRITABLE_BOOK, "编曲指南", NamedTextColor.AQUA,
                        "每行是一条音轨, 每列是一个十六分音符位置。", "一页 8 格 = 2 拍, 两页组成一个 4/4 小节。",
                        "彩色方块发声, 玻璃片休止, 海晶灯指示播放位置。",
                        "左键开关音符; 右键升半音; Shift + 右键降半音。",
                        "使用上方音色选择与添加按钮编写多声部。", "本次编排在关闭菜单后丢弃。")))
                .addIngredient('Q', this.buildProgressItem())
                .addIngredient('L', Item.builder()
                        .setItemProviderConstant(item(Material.MUSIC_DISC_CAT, "曲谱 · Flower Dance", NamedTextColor.AQUA,
                                "左键 · 重新载入五轨完整示例", "Shift + 左键 · 新建一份空白乐谱",
                                "会替换当前整份编排。", "DJ OKAWARI · 社区 NBS 音符盒版本"))
                        .addClickHandler(click -> {
                            if (click.clickType() != ClickType.LEFT && click.clickType() != ClickType.SHIFT_LEFT) return;
                            this.stop();
                            this.view.set(new View(click.clickType() == ClickType.LEFT ? this.demo : NotePattern.empty(), 0, 0));
                            this.tempo.set(2);
                        }).build())
                .addIngredient('<', this.buildPageButton(-1))
                .addIngredient('>', this.buildPageButton(1))
                .addIngredient('C', Item.builder().dependsOn(this.view)
                        .setItemProvider(context -> item(Material.BOOK, "第 " + (this.view.get().page() + 1) + " / " + this.view.get().pattern().pages() + " 页",
                                NamedTextColor.WHITE, "左键 · 跳到首页", "右键 · 跳到末页", "新增与复制的页面追加到曲尾。"))
                        .addClickHandler(click -> {
                            if (click.clickType() == ClickType.LEFT) {
                                this.changePage(0);
                            } else if (click.clickType() == ClickType.RIGHT) {
                                this.changePage(this.view.get().pattern().pages() - 1);
                            }
                        }).build())
                .addIngredient('N', Item.builder()
                        .setItemProviderConstant(item(Material.PAPER, "新增空白页", NamedTextColor.GREEN, "在曲尾添加一页并跳转过去。"))
                        .addClickHandler(click -> {
                            if (click.clickType() == ClickType.LEFT) {
                                this.appendPage(false);
                            }
                        }).build())
                .addIngredient('K', Item.builder()
                        .setItemProviderConstant(item(Material.MAP, "复制当前页", NamedTextColor.AQUA, "将本页所有音轨复制到曲尾。"))
                        .addClickHandler(click -> {
                            if (click.clickType() == ClickType.LEFT) {
                                this.appendPage(true);
                            }
                        }).build())
                .addIngredient('E', Item.builder()
                        .setItemProviderConstant(item(Material.BUCKET, "清空当前页", NamedTextColor.GOLD, "Shift + 左键 · 清空本页所有音轨"))
                        .addClickHandler(click -> {
                            if (click.clickType() != ClickType.SHIFT_LEFT) return;
                            this.stop();
                            View current = this.view.get();
                            this.view.set(current.withPattern(current.pattern().clearPage(current.page())));
                        }).build())
                .addIngredient('V', Item.builder()
                        .setItemProviderConstant(item(Material.SHEARS, "删除当前页", NamedTextColor.RED,
                                "Shift + 左键 · 删除本页并前移后续页面", "仅剩一页时清空音符并保留该页。"))
                        .addClickHandler(click -> {
                            if (click.clickType() != ClickType.SHIFT_LEFT) return;
                            this.stop();
                            View current = this.view.get();
                            this.view.set(current.withPattern(current.pattern().removePage(current.page())));
                        }).build())
                .addIngredient('P', this.buildPlayButton())
                .addIngredient('-', this.buildTempoButton(-1))
                .addIngredient('S', this.buildTempoItem())
                .addIngredient('+', this.buildTempoButton(1))
                .addIngredient('X', Item.builder()
                        .setItemProviderConstant(item(Material.BARRIER, "关闭工作台", NamedTextColor.RED, "停止播放, 本次编排不保存。"))
                        .addClickHandler(click -> click.window().close()).build())
                .build();
    }

    private Item buildInstrumentButton() {
        return Item.builder().dependsOn(this.instrument)
                .setItemProvider(context -> item(this.instrument.get().icon(), "待用音色 · " + this.instrument.get().label(), NamedTextColor.YELLOW,
                        "左键 · 下一种音色", "右键 · 上一种音色", "Shift + 左键 · 试听",
                        "选好后点击右侧绿色按钮添加音轨。", "也可左键点击现有轨道的图标来替换音色。"))
                .addClickHandler(click -> {
                    if (click.clickType() == ClickType.LEFT) {
                        this.instrument.set(this.instrument.get().advance(1));
                    } else if (click.clickType() == ClickType.RIGHT) {
                        this.instrument.set(this.instrument.get().advance(-1));
                    } else if (click.clickType() == ClickType.SHIFT_LEFT) {
                        Components.playSound(click.player(), this.instrument.get().sound(), 0.6f, 1.0f);
                    }
                }).build();
    }

    private Item buildTrackScrollButton(int direction) {
        return Item.builder().dependsOn(this.view)
                .setItemProvider(context -> {
                    View current = this.view.get();
                    boolean available = direction < 0 ? current.firstTrack() > 0 : current.firstTrack() + VISIBLE_TRACKS < current.pattern().tracks();
                    return item(available ? Material.ARROW : Material.GRAY_DYE, direction < 0 ? "查看上方音轨" : "查看下方音轨",
                            available ? NamedTextColor.WHITE : NamedTextColor.GRAY,
                            "当前显示 " + (current.firstTrack() + 1) + "–" + Math.min(current.firstTrack() + VISIBLE_TRACKS, current.pattern().tracks())
                                    + " / " + current.pattern().tracks() + " 条。",
                            "隐藏在视野外的音轨也会参与播放。");
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    View current = this.view.get();
                    int first = Math.clamp(current.firstTrack() + direction, 0, Math.max(0, current.pattern().tracks() - VISIBLE_TRACKS));
                    this.view.set(new View(current.pattern(), current.page(), first));
                }).build();
    }

    private Item buildTrackButton(int row) {
        Signal<TrackLabel> label = this.view.mapDistinct(current -> {
            int index = current.firstTrack() + row;
            if (index >= current.pattern().tracks()) return null;
            NotePattern.Track track = current.pattern().track(index);
            return new TrackLabel(index, track.instrument(), track.muted());
        });
        return Item.builder().dependsOn(label).dependsOn(this.instrument)
                .setItemProvider(context -> {
                    TrackLabel track = label.get();
                    if (track == null) return ItemComponents.create(Material.AIR);
                    return item(track.muted() ? Material.GRAY_DYE : track.instrument().icon(),
                            "音轨 " + (track.index() + 1) + " · " + track.instrument().label() + (track.muted() ? " · 已静音" : ""),
                            track.muted() ? NamedTextColor.GRAY : NamedTextColor.YELLOW,
                            "左键 · 替换为 " + this.instrument.get().label(), "右键 · 开关静音",
                            "Shift + 右键 · 删除此轨道 (至少保留一轨)");
                })
                .addClickHandler(click -> {
                    View current = this.view.get();
                    int index = current.firstTrack() + row;
                    if (index >= current.pattern().tracks()) return;
                    NotePattern next;
                    if (click.clickType() == ClickType.LEFT) {
                        next = current.pattern().instrument(index, this.instrument.get());
                    } else if (click.clickType() == ClickType.RIGHT) {
                        next = current.pattern().mute(index);
                    } else if (click.clickType() == ClickType.SHIFT_RIGHT && current.pattern().tracks() > 1) {
                        this.stop();
                        next = current.pattern().removeTrack(index);
                    } else {
                        return;
                    }
                    this.view.set(current.withPattern(next));
                }).build();
    }

    private Item buildBeatItem(int step) {
        Signal<Boolean> active = this.playhead.mapDistinct(value -> value == step);
        return Item.builder().dependsOn(active)
                .setItemProvider(context -> {
                    ItemStack indicator = item(active.get() ? Material.SEA_LANTERN : Material.WHITE_STAINED_GLASS_PANE,
                            "第 " + (step / 4 + 1) + " 拍 · 第 " + (step % 4 + 1) + "/4 格", NamedTextColor.WHITE,
                            active.get() ? "正在播放这一列。" : "每四列是一拍, 同列各轨同时发声。");
                    indicator.setAmount(step + 1);
                    return indicator;
                }).build();
    }

    private Item buildNoteButton(int index) {
        int row = index / NotePattern.STEPS;
        int column = index % NotePattern.STEPS;
        Signal<Cell> cell = this.view.mapDistinct(current -> {
            int trackIndex = current.firstTrack() + row;
            if (trackIndex >= current.pattern().tracks()) return null;
            NotePattern.Track track = current.pattern().track(trackIndex);
            return new Cell(trackIndex, track.instrument(), track.muted(), track.note(current.page() * NotePattern.STEPS + column));
        });
        return Item.builder().dependsOn(cell)
                .setItemProvider(context -> {
                    Cell value = cell.get();
                    if (value == null) return ItemComponents.create(Material.AIR);
                    boolean enabled = (value.note() & 1) != 0;
                    Material material = !enabled ? Material.WHITE_STAINED_GLASS_PANE : value.muted() ? Material.GRAY_CONCRETE : NOTE_COLORS[row];
                    ItemStack note = item(material, "轨 " + (value.track() + 1) + " · " + value.instrument().label() + " · " + (enabled ? "发声" : "休止"),
                            enabled && !value.muted() ? NamedTextColor.YELLOW : NamedTextColor.GRAY,
                            NoteInstrument.pitchName(value.note() >>> 1), "左键 · 开关音符", "右键 · 升半音并启用",
                            "Shift + 右键 · 降半音并启用", value.muted() ? "此轨已静音, 右键轨道图标恢复。" : "停播时编辑音符可试听。");
                    if (enabled) {
                        note.setAmount((value.note() >>> 1) + 1);
                    }
                    return note;
                })
                .addClickHandler(click -> {
                    View current = this.view.get();
                    int track = current.firstTrack() + row;
                    if (track >= current.pattern().tracks()) return;
                    int step = current.page() * NotePattern.STEPS + column;
                    NotePattern next;
                    if (click.clickType() == ClickType.LEFT) {
                        next = current.pattern().toggle(track, step);
                    } else if (click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT) {
                        next = current.pattern().shiftPitch(track, step, click.clickType() == ClickType.RIGHT ? 1 : -1);
                    } else {
                        return;
                    }
                    this.view.set(current.withPattern(next));
                    if (this.position.get() < 0) {
                        this.playNote(click.player(), next.track(track), step);
                    }
                }).build();
    }

    private Item buildPageButton(int direction) {
        return Item.builder().dependsOn(this.view)
                .setItemProvider(context -> {
                    View current = this.view.get();
                    int target = current.page() + direction;
                    boolean available = target >= 0 && target < current.pattern().pages();
                    return item(available ? Material.ARROW : Material.GRAY_DYE, direction < 0 ? "上一页" : "下一页",
                            available ? NamedTextColor.WHITE : NamedTextColor.GRAY,
                            "左键 · 翻一页", "Shift + 左键 · 翻八页", "翻页会停止播放。");
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT && click.clickType() != ClickType.SHIFT_LEFT) return;
                    this.changePage(this.view.get().page() + direction * (click.clickType() == ClickType.SHIFT_LEFT ? 8 : 1));
                }).build();
    }

    private void changePage(int target) {
        View current = this.view.get();
        int page = Math.clamp(target, 0, current.pattern().pages() - 1);
        if (page == current.page()) return;
        this.stop();
        this.view.set(new View(current.pattern(), page, current.firstTrack()));
    }

    private void appendPage(boolean copy) {
        this.stop();
        View current = this.view.get();
        NotePattern next = current.pattern().appendPage(copy ? current.page() : -1);
        this.view.set(new View(next, next.pages() - 1, current.firstTrack()));
    }

    private Item buildProgressItem() {
        return Item.builder().dependsOn(this.position).dependsOn(this.view)
                .setItemProvider(context -> {
                    int step = this.position.get();
                    NotePattern pattern = this.view.get().pattern();
                    return item(Material.NOTE_BLOCK, step < 0 ? "编排就绪" : "播放 · 第 " + (step / NotePattern.STEPS + 1) + " 页 · " + (step % NotePattern.STEPS + 1) + "/8",
                            NamedTextColor.AQUA, pattern.tracks() + " 条音轨 · " + pattern.pages() + " 页 · " + pattern.pages() * 2 + " 拍",
                            "从第一页播放到最后一页后自动停止。", "播放中可修改音符、音色和静音, 也可调速。");
                }).build();
    }

    private Item buildPlayButton() {
        Signal<Boolean> playing = this.position.mapDistinct(value -> value >= 0);
        return Item.builder().dependsOn(playing)
                .setItemProvider(context -> item(playing.get() ? Material.REDSTONE_TORCH : Material.EMERALD,
                        playing.get() ? "停止播放" : "从头播放整曲", playing.get() ? NamedTextColor.RED : NamedTextColor.GREEN,
                        "所有音轨同时播放, 页面自动跟随。", "每次从第一格开始, 播完自动停止。"))
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    if (this.position.get() >= 0) {
                        this.stop();
                    } else {
                        this.start(click.player());
                    }
                }).build();
    }

    private Item buildTempoItem() {
        return Item.builder().dependsOn(this.tempo)
                .setItemProvider(ignoredContext -> item(Material.CLOCK, "速度 · " + tempoText(this.tempo.get()) + " BPM", NamedTextColor.GOLD, "左侧羽毛减速, 右侧糖加速。", "每四格为一拍, 按服务器 20 TPS 计算。")).build();
    }

    private Item buildTempoButton(int direction) {
        return Item.builder().dependsOn(this.tempo)
                .setItemProvider(context -> {
                    int target = this.tempo.get() + direction;
                    boolean available = target >= 0 && target < TEMPOS.length;
                    return item(available ? direction < 0 ? Material.FEATHER : Material.SUGAR : Material.GRAY_DYE,
                            direction < 0 ? "慢一点" : "快一点", available ? NamedTextColor.YELLOW : NamedTextColor.GRAY,
                            available ? "调整为 " + tempoText(target) + " BPM。" : "已经到达速度边界。");
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    int target = this.tempo.get() + direction;
                    if (target < 0 || target >= TEMPOS.length) return;
                    this.tempo.set(target);
                }).build();
    }

    private void start(@NotNull Player viewer) {
        this.stop();
        this.advance(viewer);
        this.schedule(viewer);
    }

    private void schedule(@NotNull Player viewer) {
        int playback = ++this.generation;
        this.elapsed = 0;
        // 每 tick 累计进度, 小数节拍的误差保留到下次; 声音与指针始终使用同一个时钟.
        this.task = SparrowUI.getInstance().scheduler().platform().runRepeating(() -> {
            if (this.generation != playback) return;
            this.elapsed += TEMPOS[this.tempo.get()];
            if (this.elapsed >= 1200) {
                this.elapsed -= 1200;
                this.advance(viewer);
            }
        }, () -> {
            if (this.generation == playback) {
                this.stop();
            }
        }, 1, 1, viewer);
        if (this.task == null) {
            this.stop();
        }
    }

    private void advance(@NotNull Player viewer) {
        int step = this.position.get() + 1;
        View current = this.view.get();
        NotePattern pattern = current.pattern();
        if (step >= pattern.steps()) {
            this.stop();
            return;
        }
        int page = step / NotePattern.STEPS;
        if (page != current.page()) {
            this.view.set(new View(pattern, page, current.firstTrack()));
        }
        this.position.set(step);
        for (int track = 0; track < pattern.tracks(); track++) {
            this.playNote(viewer, pattern.track(track), step);
        }
    }

    private void playNote(@NotNull Player viewer, @NotNull NotePattern.Track track, int step) {
        int note = track.note(step);
        if (track.muted() || (note & 1) == 0) return;
        Components.playSound(viewer, track.instrument().sound(), 0.6f, NoteInstrument.pitch(note >>> 1));
    }

    private void stop() {
        this.generation++;
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        this.position.set(-1);
    }

    private static ItemStack item(Material material, String title, NamedTextColor color, String... description) {
        ItemStack stack = ItemComponents.create(material);
        ItemComponents.name(stack, Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        ItemComponents.lore(stack, Arrays.stream(description)
                .<Component>map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        return stack;
    }

    private static String tempoText(int index) {
        int value = TEMPOS[index];
        return value % 4 == 0 ? Integer.toString(value / 4) : Double.toString(value / 4.0);
    }

    private record View(NotePattern pattern, int page, int firstTrack) {
        private View withPattern(NotePattern next) {
            return new View(next, Math.min(this.page, next.pages() - 1), Math.min(this.firstTrack, Math.max(0, next.tracks() - VISIBLE_TRACKS)));
        }
    }

    private record TrackLabel(int index, NoteInstrument instrument, boolean muted) {
    }

    private record Cell(int track, NoteInstrument instrument, boolean muted, int note) {
    }
}
