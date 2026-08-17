package net.momirealms.sparrow.ui.example.menu.searchcapture;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.AnvilWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 会话里负责捕捉搜索词的那一扇: 只借铁砧的文本框, 不做任何合成.
 *
 * <p>它由 {@link SearchCaptureMenu} 的搜索按钮打开, 因此列表那扇窗成了它的上一扇. 离开有两条路:
 * <ul>
 *     <li>点结果槽的确认按钮: 把攒下的草稿交给列表筛一次, 再 {@link Window#back()} 回去;</li>
 *     <li>直接按 ESC: 原样返回, 列表那边的筛选词一个字都不动. 本窗在 Builder 上开了
 *         {@code setBackOnPlayerClose(true)}, 因此玩家主动关闭被理解成返回上一扇, 而不是结束整段会话.</li>
 * </ul>
 *
 * <p>输入阶段只往 {@link #draft} 里攒文本, 一次筛选都不做. 这是这个示例特意要演示的分工:
 * 列表那边的一次 {@link SearchCaptureMenu#query(String)} 要重扫整份 Material 清单, 重算分页,
 * 再刷新整整三行投影, 跟着击键跑就是每敲一个字白做一遍; 攒草稿只是写一个字段, 顺带让本窗
 * 单独一格重画. 昂贵的活留到玩家确认时做一次.
 *
 * <p>会话是 {@link net.momirealms.sparrow.ui.window.WindowSession.Kind#STACK} 型, 返回时这扇窗被弹出并丢引用,
 * 所以下次点搜索按钮进来的是一扇全新构建的铁砧, 草稿从当时生效的筛选词重新起头:
 * {@link #appliedOnEntry} 每次都重新捕捉一遍, 玩家可以直接从这一行看出自己进的不是上一次那扇窗.
 */
final class SearchCaptureAnvil {
    private final SearchCaptureMenu browser;   // 上一扇携带的浏览菜单本体
    private final String appliedOnEntry;       // 进入本窗时生效的筛选词
    private final MutableSignal<String> draft; // 客户端最近一次提交的文本, 只驱动结果槽那一格
    private final AnvilWindow window;

    /**
     * 从列表那扇窗打开一次输入界面.
     *
     * @param source 发起本次跳转的 Window, 也就是浏览菜单的根窗
     * @return 打开后的铁砧 Window; 打不开时以 null 完成
     */
    @NotNull
    static CompletableFuture<Window> openFrom(@NotNull Window source) {
        // 手上就有那扇窗, 直接从它的 data 槽取出菜单本体, 不需要另外传引用
        SearchCaptureMenu browser = source.data(SearchCaptureMenu.class);
        return source.openNext(new SearchCaptureAnvil(source.viewer(), browser).window);
    }

    /**
     * 创建一次输入界面.
     *
     * @param viewer 要查看菜单的玩家
     * @param browser 上一扇携带的浏览菜单本体
     */
    private SearchCaptureAnvil(@NotNull Player viewer, @NotNull SearchCaptureMenu browser) {
        this.browser = browser;
        this.appliedOnEntry = browser.query();
        // 草稿从当前生效的筛选词起头: 一个字都没输就确认, 等于什么都没改
        this.draft = Signal.of(this.appliedOnEntry);
        this.window = AnvilWindow.builder()
                .setTitle(Component.text("输入搜索词", NamedTextColor.DARK_AQUA))
                .setUpperPane(Pane.builder("##R").addIngredient('R', this.buildConfirmButton()).build())
                // 输入槽留空, 由 Window 提供内部占位物保持文本框可编辑
                .setTextFieldAlwaysEnabled(true)
                // 结果槽放的是按钮而不是产物, 因此不能让客户端按合成规则判定它无效
                .setResultAlwaysValid(true)
                // ESC 回到列表, 而不是结束整段会话
                .setBackOnPlayerClose(true)
                // 客户端每提交一次文本只更新草稿, 不碰列表那边的搜索状态
                .addRenameHandler(this.draft::set)
                .build(viewer);
        // 两个输入槽只借客户端的文本框, 不接收玩家放入的物品
        this.window.frozenAt(0, true);
        this.window.frozenAt(1, true);
    }

    /**
     * 创建结果槽上的确认按钮.
     * <p>它是本窗唯一跟着击键重画的东西: 只挂在本窗自己的草稿上, 一次筛选都不触发.
     *
     * @return 随草稿更新的按钮 Item
     */
    @NotNull
    private Item buildConfirmButton() {
        return Item.builder()
                .dependsOn(this.draft)
                .setItemProvider(ignoredContext -> {
                    String draft = this.draft.get().strip();
                    ItemStack itemStack = new ItemStack(Material.LIME_DYE);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("确认搜索", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("确认之后才真正筛选一次。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("将要搜索: ", NamedTextColor.GRAY).append(Component.text(draft.isEmpty() ? "全部" : draft, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("进入本窗时: ", NamedTextColor.GRAY).append(Component.text(this.appliedOnEntry.isEmpty() ? "未筛选" : this.appliedOnEntry, NamedTextColor.DARK_AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("点击应用并返回列表", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                            Component.text("按 ESC 放弃本次输入", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(click -> {
                    // 整段交互里唯一一次筛选就在这里
                    this.browser.query(this.draft.get());
                    click.window().back();
                })
                .build();
    }
}
