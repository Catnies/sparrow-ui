package net.momirealms.sparrow.ui.example.menu.searchcapture;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Pane;
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
 * <p>它由 {@link SearchCaptureMenu} 的搜索按钮打开, 因此列表那扇窗成了它的上一扇.
 * 离开的方式有两种, 结果一样:
 * <ul>
 *     <li>点结果槽的确认按钮, 也就是 {@link Window#back()};</li>
 *     <li>直接按 ESC. 本窗在 Builder 上开了 {@code setBackOnPlayerClose(true)},
 *         因此玩家主动关闭被理解成返回上一扇, 而不是结束整段会话.</li>
 * </ul>
 *
 * <p>会话是 {@link net.momirealms.sparrow.ui.window.WindowSession.Kind#STACK} 型, 返回时这扇窗被弹出并丢引用,
 * 所以下次点搜索按钮进来的是一扇全新构建的铁砧: {@link #enteredWith} 每次都重新捕捉一遍,
 * 玩家可以直接从这一行看出自己进的不是上一次那扇窗.
 */
final class SearchCaptureAnvil {
    private final SearchCaptureMenu browser; // 上一扇携带的浏览菜单本体
    private final String enteredWith;        // 进入本窗时的筛选词
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
        this.enteredWith = browser.input().get();
        this.window = AnvilWindow.builder()
                .setTitle(Component.text("输入搜索词"))
                .setUpperPane(Pane.builder("##R").addIngredient('R', this.buildConfirmButton()).build())
                // 输入槽留空, 由 Window 提供内部占位物保持文本框可编辑
                .setTextFieldAlwaysEnabled(true)
                // 结果槽放的是按钮而不是产物, 因此不能让客户端按合成规则判定它无效
                .setResultAlwaysValid(true)
                // ESC 回到列表, 而不是结束整段会话
                .setBackOnPlayerClose(true)
                // 客户端每提交一次文本就直接写进列表那边的搜索状态
                .addRenameHandler(browser::query)
                .build(viewer);
        // 两个输入槽只借客户端的文本框, 不接收玩家放入的物品
        this.window.frozenAt(0, true);
        this.window.frozenAt(1, true);
    }

    /**
     * 创建结果槽上的确认按钮.
     * <p>输入是即时生效的, 因此这个按钮只负责回到列表, 顺便把当前的筛选结果摆出来.
     * 显示直接挂在上一扇的两个 Signal 上, 玩家每敲一个字它就重新渲染一次.
     *
     * @return 随输入实时更新的按钮 Item
     */
    @NotNull
    private Item buildConfirmButton() {
        return Item.builder()
                .dependsOn(this.browser.input(), this.browser.resultCount())
                .setItemProvider(ignoredContext -> {
                    String query = this.browser.input().get();
                    ItemStack itemStack = new ItemStack(Material.ARROW);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("回到列表", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("输入即时生效，回去就能看到结果。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("生效筛选词: ", NamedTextColor.GRAY).append(Component.text(query.isEmpty() ? "未筛选" : query, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("命中数: ", NamedTextColor.GRAY).append(Component.text(Integer.toString(this.browser.resultCount().get()), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("进入本窗时: ", NamedTextColor.GRAY).append(Component.text(this.enteredWith.isEmpty() ? "未筛选" : this.enteredWith, NamedTextColor.DARK_AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("点击返回列表", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                            Component.text("按 ESC 同样返回列表", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(click -> click.window().back())
                .build();
    }
}
