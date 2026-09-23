package net.momirealms.sparrow.ui.example.menu.expedition;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Member;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Phase;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Role;
import net.momirealms.sparrow.ui.example.menu.expedition.ExpeditionState.Route;
import net.momirealms.sparrow.ui.example.util.Components;
import net.momirealms.sparrow.ui.example.util.ItemComponents;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 多名玩家共用准备室状态, 各自的窗口按查看者显示投票、职责和可用操作.
 */
public final class ExpeditionMenu {
    private static final Role[] ROLES = {Role.GUARD, Role.RANGER, Role.HEALER, Role.MAGE};
    private static final Route[] ROUTES = Route.values();

    private final ExpeditionRoom room;
    private final Signal<ExpeditionState> state;
    private final NormalPane pane;

    ExpeditionMenu(@NotNull ExpeditionRoom room) {
        this.room = room;
        this.state = room.state();
        this.pane = this.buildPane();
    }

    /**
     * 打开公共准备室, 关闭窗口保留席位, 主动退队或离线时才移除成员.
     *
     * @param viewer 查看菜单的玩家
     * @return 窗口打开结果
     */
    @NotNull
    public CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return Window.builder(this.pane)
                .setTitle(Component.text("多人远征准备室", NamedTextColor.DARK_GRAY))
                .open(viewer);
    }

    private NormalPane buildPane() {
        return Pane.builder(
                        ".I..S..H.",
                        ".M.M.M.M.",
                        ".V.V.V.V.",
                        "..D.D.D..",
                        ".R.R.R.R.",
                        "J..PB..LX"
                )
                .addIngredient('I', this.buildRoomItem())
                .addIngredient('S', this.buildStatusItem())
                .addIngredient('H', Item.simple(item(Material.BOOK, "远征指南", NamedTextColor.YELLOW,
                        "① 加入小队, 第一名加入者成为队长。", "② 投票选择目的地, 再选择一项职责。",
                        "③ 全员准备后, 队长启动五秒倒计时。", "至少两人即可出征, 职责可以重复。",
                        "红灯未准备, 绿灯已准备, 金灯已出发。", "关闭菜单保留席位, 离线自动退队。",
                        "这是一次集结演练, 不会传送或消耗物品。")))
                .addIngredient('M', (slots, occurrence) -> new Element.Item(this.buildMemberButton(occurrence)))
                .addIngredient('V', (slots, occurrence) -> new Element.Item(this.buildReadyLight(occurrence)))
                .addIngredient('D', (slots, occurrence) -> new Element.Item(this.buildRouteButton(ROUTES[occurrence])))
                .addIngredient('R', (slots, occurrence) -> new Element.Item(this.buildRoleButton(ROLES[occurrence])))
                .addIngredient('J', this.buildJoinButton())
                .addIngredient('P', this.buildReadyButton())
                .addIngredient('B', this.buildLaunchButton())
                .addIngredient('L', Item.builder()
                        .setItemProviderConstant(item(Material.OAK_DOOR, "离开小队", NamedTextColor.GOLD,
                                "释放席位, 全员需要重新准备。", "队长离开后由下一名成员接任。"))
                        .addClickHandler(click -> {
                            if (click.clickType() != ClickType.LEFT) return;
                            feedback(click.player(), this.room.leave(click.player().getUniqueId()));
                        }).build())
                .addIngredient('X', Item.builder()
                        .setItemProviderConstant(item(Material.BARRIER, "收起菜单", NamedTextColor.RED,
                                "保留席位与准备状态, 稍后可以重新打开。"))
                        .addClickHandler(click -> click.window().close()).build())
                .build();
    }

    private Item buildRoomItem() {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    return item(Material.COMPASS, "公共营地 · " + current.members().size() + "/4", NamedTextColor.AQUA,
                            "准备就绪 · " + current.readyCount() + "/" + current.members().size(),
                            "队长 · " + (current.members().isEmpty() ? "等待加入" : current.members().getFirst().name()),
                            current.member(context.player().getUniqueId()) == null ? "你正在旁观, 点击空位即可加入。" : "你已加入小队, 其他成员会实时看到你的操作。",
                            "最近动态 · " + current.event());
                }).build();
    }

    private Item buildStatusItem() {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    if (current.phase() == Phase.COUNTDOWN) {
                        ItemStack countdown = item(Material.CLOCK, "距离出发还有 " + current.seconds() + " 秒", NamedTextColor.GOLD,
                                "目的地 · " + current.destination().title, "人员、职责、投票或准备状态变化都会中止倒计时。");
                        countdown.setAmount(current.seconds());
                        return countdown;
                    }
                    if (current.phase() == Phase.DEPARTED) {
                        ItemStack departed = item(Material.NETHER_STAR, "出征成功！", NamedTextColor.GREEN,
                                "目的地 · " + current.destination().title, "小队成员 · " + current.members().size() + " 人",
                                "本次集结演练完成, 队长可点击下方按钮返回营地。");
                        ItemComponents.glint(departed, true);
                        return departed;
                    }
                    return item(Material.CAMPFIRE, "营地集结中", NamedTextColor.GOLD,
                            "加入 → 投票 → 选择职责 → 准备 → 出征", "至少两人、全员准备后由队长发起。",
                            "人员或投票变化会撤销全员准备。", "更换职责只撤销自己的准备。");
                }).build();
    }

    private Item buildMemberButton(int seat) {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    Member member = seat < current.members().size() ? current.members().get(seat) : null;
                    context.remember(member == null ? null : member.id());
                    if (member == null) return item(Material.WHITE_STAINED_GLASS_PANE, "空席位 " + (seat + 1), NamedTextColor.WHITE, "左键加入远征小队。");
                    boolean captain = current.captain(member.id());
                    ItemStack card = item(member.role().icon, member.name() + (captain ? " · 队长" : ""), captain ? NamedTextColor.GOLD : NamedTextColor.WHITE,
                            "职责 · " + member.role().title, "投票 · " + (member.vote() == null ? "尚未选择" : member.vote().title),
                            member.ready() ? "状态 · 已准备" : "状态 · 未准备", "队长可 Shift + 左键点击成员移交队长。");
                    ItemComponents.glint(card, captain);
                    return card;
                })
                .addClickHandler(click -> {
                    UUID target = click.remembered();
                    if (click.clickType() == ClickType.LEFT && target == null) {
                        feedback(click.player(), this.room.join(click.player().getUniqueId(), click.player().getName()));
                    } else if (click.clickType() == ClickType.SHIFT_LEFT && target != null) {
                        feedback(click.player(), this.room.transferCaptain(click.player().getUniqueId(), target));
                    }
                }).build();
    }

    private Item buildReadyLight(int seat) {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    Member member = seat < current.members().size() ? current.members().get(seat) : null;
                    context.remember(member == null ? null : member.id());
                    if (member == null) return item(Material.WHITE_STAINED_GLASS_PANE, "等待成员", NamedTextColor.WHITE);
                    if (current.phase() == Phase.DEPARTED) return item(Material.GOLD_BLOCK, member.name() + " · 已出发", NamedTextColor.GOLD);
                    return item(member.ready() ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                            member.name() + (member.ready() ? " · 已准备" : " · 未准备"), member.ready() ? NamedTextColor.GREEN : NamedTextColor.RED,
                            "点击自己的准备灯切换状态。");
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    if (click.player().getUniqueId().equals(click.remembered())) {
                        feedback(click.player(), this.room.toggleReady(click.player().getUniqueId()));
                    }
                }).build();
    }

    private Item buildRouteButton(@NotNull Route route) {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    Member member = current.member(context.player().getUniqueId());
                    boolean selected = member != null && member.vote() == route;
                    int votes = current.votes(route);
                    ItemStack ballot = item(route.icon, route.title + " · " + votes + " 票" + (selected ? " · 你的选择" : ""), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                            route.description, votes > 0 && current.destination() == route ? "当前领先的目的地。" : "左键为这条路线投票。",
                            "平票优先队长所投路线, 仍平票按从左到右决定。", "改变投票后全员需要重新准备。");
                    ItemComponents.glint(ballot, selected);
                    return ballot;
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    feedback(click.player(), this.room.vote(click.player().getUniqueId(), route));
                }).build();
    }

    private Item buildRoleButton(@NotNull Role role) {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    Member member = this.state.get().member(context.player().getUniqueId());
                    boolean selected = member != null && member.role() == role;
                    ItemStack choice = item(role.icon, role.title + (selected ? " · 已选择" : ""), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                            role.description, "左键选择职责, 允许多名成员选择同一项。", "更换职责后需要重新确认准备。");
                    ItemComponents.glint(choice, selected);
                    return choice;
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    feedback(click.player(), this.room.chooseRole(click.player().getUniqueId(), role));
                }).build();
    }

    private Item buildJoinButton() {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    if (current.member(context.player().getUniqueId()) != null) return item(Material.LIME_DYE, "已加入小队", NamedTextColor.GREEN, "选择路线与职责, 然后点击准备。");
                    boolean available = current.members().size() < ExpeditionState.CAPACITY && current.phase() != Phase.DEPARTED;
                    return item(available ? Material.EMERALD : Material.GRAY_DYE, available ? "加入小队" : "暂时无法加入", available ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                            available ? "成为队员, 与其他玩家共同准备出征。" : "小队已满或已出征, 可以先旁观。");
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    feedback(click.player(), this.room.join(click.player().getUniqueId(), click.player().getName()));
                }).build();
    }

    private Item buildReadyButton() {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    Member member = current.member(context.player().getUniqueId());
                    if (member == null) return item(Material.GRAY_DYE, "请先加入小队", NamedTextColor.GRAY);
                    if (current.phase() == Phase.DEPARTED) return item(Material.LIME_DYE, "小队已出发", NamedTextColor.GREEN);
                    return item(member.ready() ? Material.LIME_DYE : Material.RED_DYE, member.ready() ? "取消准备" : "我已准备", member.ready() ? NamedTextColor.GREEN : NamedTextColor.RED,
                            "职责 · " + member.role().title, "投票 · " + (member.vote() == null ? "尚未选择" : member.vote().title),
                            "确认职责和路线后, 点击切换准备状态。");
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    feedback(click.player(), this.room.toggleReady(click.player().getUniqueId()));
                }).build();
    }

    private Item buildLaunchButton() {
        return Item.builder().dependsOn(this.state)
                .setItemProvider(context -> {
                    ExpeditionState current = this.state.get();
                    UUID viewer = context.player().getUniqueId();
                    if (current.phase() == Phase.COUNTDOWN) return item(Material.REDSTONE_TORCH, "取消出征 · " + current.seconds() + " 秒", NamedTextColor.GOLD, "队长点击取消, 全员重新准备。");
                    if (current.phase() == Phase.DEPARTED) return item(Material.RECOVERY_COMPASS, "返回营地", NamedTextColor.AQUA, "队长点击开始准备下一次远征。");
                    String problem = current.launchProblem(viewer);
                    return item(problem == null ? Material.ENDER_EYE : Material.GRAY_DYE, "队长发起出征", problem == null ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                            problem == null ? "条件满足, 点击开始五秒倒计时。" : problem, "票选目的地 · " + current.destination().title);
                })
                .addClickHandler(click -> {
                    if (click.clickType() != ClickType.LEFT) return;
                    feedback(click.player(), this.room.launch(click.player().getUniqueId()));
                }).build();
    }

    private static void feedback(@NotNull Player player, @Nullable String problem) {
        if (problem != null) {
            Components.sendMessage(player, Component.text(problem, NamedTextColor.RED));
        }
        Components.playSound(player, problem == null ? Sound.UI_BUTTON_CLICK : Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f);
    }

    @NotNull
    private static ItemStack item(@NotNull Material material, @NotNull String title, @NotNull NamedTextColor color, String @NotNull ... description) {
        ItemStack stack = ItemComponents.create(material);
        ItemComponents.name(stack, Component.text(title, color).decoration(TextDecoration.ITALIC, false));
        ItemComponents.lore(stack, Arrays.stream(description)
                .<Component>map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        return stack;
    }
}
