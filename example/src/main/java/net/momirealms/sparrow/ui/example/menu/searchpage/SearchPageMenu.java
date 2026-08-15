package net.momirealms.sparrow.ui.example.menu.searchpage;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.util.Scheduling;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.page.Page;
import net.momirealms.sparrow.ui.state.MutableSignal;
import net.momirealms.sparrow.ui.state.Signal;
import net.momirealms.sparrow.ui.window.AnvilWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 演示如何把铁砧输入框、响应式状态和分页器组合成一个可即时搜索的 Material 浏览菜单.
 *
 * <p>这个示例的数据流可以按下面的顺序阅读:
 * <ol>
 *     <li>玩家修改铁砧输入框, {@link #updateInput(String)} 将文本整理成统一的搜索词.</li>
 *     <li>{@link #input} 变化后重新筛选 Material, {@link #resultCount} 与 {@link #pages} 随之更新.</li>
 *     <li>下栏中的 Material、页码和翻页按钮依赖这些状态, 因此不需要手动刷新整个菜单.</li>
 * </ol>
 *
 * <p>每次打开都会创建一组互不共享的搜索与分页状态. Material 清单则由类级字段复用,
 * 避免为每位玩家重复创建同一批原始 {@link ItemStack}. 该清单在本类首次初始化时构建,
 * 早于 {@link #open(Player)} 方法体中的异步任务.
 */
public final class SearchPageMenu {
    private static final int PAGE_SIZE = 27; // 玩家背包上三行的可展示槽位数

    /**
     * 所有可以作为物品显示的非旧版 Material, 同时保存搜索用 ID 和可直接展示的原始物品.
     * <p>例如 {@code Material.STONE} 会保存为 ID {@code minecraft:stone} 和一个原始石头物品.
     */
    private static final List<MaterialEntry> ALL_MATERIAL = Arrays.stream(Material.values())
            .filter(it -> !it.isLegacy() && it.isItem() && !it.isAir())
            .map(it -> new MaterialEntry(it.getKey().asString(), Item.simple(new ItemStack(it))))
            .toList();

    private final MutableSignal<String> input; // 已去除首尾空白并转成小写的当前搜索词
    private final Signal<Integer> resultCount; // 当前搜索词命中的 Material 数量
    private final Page<MaterialEntry> pages;   // 把筛选结果按每页 27 个切分后的分页状态

    /**
     * 创建一次菜单打开所需的独立搜索状态, 让不同玩家的输入和页码互不影响.
     */
    private SearchPageMenu() {
        // input 是搜索数据流的起点, 变化后会重新得到整份筛选结果
        this.input = Signal.of("");
        Signal<List<MaterialEntry>> results = this.input.mapDistinct(SearchPageMenu::filterMaterials);
        // 结果数量和分页器共同消费同一份筛选结果, 避免维护两套容易失配的状态
        this.resultCount = results.map(List::size);
        this.pages = Page.of(results, PAGE_SIZE);
    }

    /**
     * 为指定玩家异步构建并打开一个新的搜索翻页菜单.
     *
     * <p>菜单对象、Pane 和 Window 在 Paper 全局异步调度器中构建.
     * {@link Window#open()} 再把实际打开操作交给该玩家所属的实体线程.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        // Window.open 返回另一层 Future, thenCompose 会把异步构建和实际打开合并成一次等待
        return Scheduling.async(SparrowExample.INSTANCE, () -> {
            SearchPageMenu menu = new SearchPageMenu();
            AnvilWindow anvilWindow = AnvilWindow.builder()
                    .setTitle(Component.text("搜索 Material"))
                    .setUpperPane(menu.buildUpperPane())
                    .setLowerPane(menu.buildLowerPane())
                    .addRenameHandler(menu::updateInput)
                    .setTextFieldAlwaysEnabled(true)
                    .build(viewer);
            return anvilWindow.open();
        }).thenCompose(opening -> opening);
    }

    /**
     * 创建铁砧自身的三格上栏.
     * <p>槽位保持为空并冻结交互. {@code setTextFieldAlwaysEnabled(true)} 会由 Window
     * 为第一个空槽提供内部占位物, 使玩家不放入真实物品也能持续输入搜索词.
     *
     * @return 冻结的铁砧上栏 Pane
     */
    @NotNull
    private NormalPane buildUpperPane() {
        return Pane.builder("###")
                .setFrozen(true)
                .build();
    }

    /**
     * 创建显示搜索结果和导航控件的玩家背包区域.
     * <p>前三行的 {@code M} 显示当前页 Material. 快捷栏中的 {@code P}、{@code I}、
     * {@code N} 分别表示上一页、搜索状态和下一页, 其余位置保持空白.
     *
     * @return 四行玩家背包布局 Pane
     */
    @NotNull
    private NormalPane buildLowerPane() {
        // 字符模板把内容区和固定导航位置直接展示给示例读者
        return Pane.builder(
                        "MMMMMMMMM",
                        "MMMMMMMMM",
                        "MMMMMMMMM",
                        "P###I###N"
                )
                // Page 提供当前页内容; 条目只需同步拆出预构建 Item, 因此直接使用当前线程执行
                .addIngredient('M', this.pages, entry -> Element.item(entry.item()), Runnable::run)
                .addIngredient('P', this.buildPageButton(-1))
                .addIngredient('I', this.buildStatusItem())
                .addIngredient('N', this.buildPageButton(1))
                .build();
    }

    /**
     * 创建一个会随当前页和总页数自动更新的翻页按钮.
     * @param step 翻页方向, 本示例使用 {@code -1} 表示上一页, {@code 1} 表示下一页
     * @return 带动态外观和点击行为的按钮 Item
     */
    @NotNull
    private Item buildPageButton(int step) {
        // 页码或总页数变化时重新执行 Provider, 按钮会立即切换可用状态和文字
        return Item.builder()
                .dependsOn(this.pages.page(), this.pages.count())
                .setItemProvider(ignoredContext -> {
                    int pageIndex = this.pages.page().get();
                    int pageCount = this.pages.count().get();
                    boolean enabled;
                    String title;
                    String summary;
                    // 同一个构建过程服务两个方向, 仅边界条件和显示文字不同
                    if (step < 0) {
                        enabled = pageIndex > 0;
                        title = "上一页";
                        summary = enabled ? "浏览前一页的搜索结果。" : "已经是第一页。";
                    } else {
                        enabled = pageIndex + 1 < pageCount;
                        title = "下一页";
                        summary = enabled ? "浏览后一页的搜索结果。" : "已经是最后一页。";
                    }

                    // 使用 Paper 数据组件 API 设置名称和 Lore, 不经过旧式 ItemMeta
                    ItemStack itemStack = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(title, enabled ? NamedTextColor.YELLOW : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                    Component page = Component.text("当前页: ", NamedTextColor.GRAY)
                            .append(Component.text((pageIndex + 1) + " / " + pageCount, NamedTextColor.AQUA))
                            .decoration(TextDecoration.ITALIC, false);
                    List<Component> lore = enabled
                            ? List.of(
                                    Component.text(summary, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                                    Component.empty(),
                                    page,
                                    Component.empty(),
                                    Component.text("点击翻页", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                            )
                            : List.of(
                                    Component.text(summary, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                                    Component.empty(),
                                    page
                            );
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
                    return itemStack;
                })
                // 禁用状态负责视觉提示, Page.advance 会把越界页码夹在有效范围内
                .addClickHandler(ignoredClick -> this.pages.advance(step))
                .build();
    }

    /**
     * 创建集中展示当前搜索词、结果数和页码的状态物品.
     * @return 随搜索和分页状态自动更新的指南针 Item
     */
    @NotNull
    private Item buildStatusItem() {
        // 任一展示值变化都使 Item 失效, 下一次渲染会读取同一时刻的最新状态
        return Item.builder()
                .dependsOn(this.input, this.resultCount, this.pages.page(), this.pages.count())
                .setItemProvider(ignore -> {
                    String currentQuery = this.input.get();
                    String queryText = currentQuery.isEmpty() ? "未筛选" : currentQuery;
                    int pageIndex = this.pages.page().get();
                    int pageCount = this.pages.count().get();

                    // 状态物品同样直接使用组件 API, 并保持名称、数据和操作提示的视觉层级
                    ItemStack itemStack = new ItemStack(Material.COMPASS);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("搜索结果", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("输入内容变化时立即筛选，无需确认。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("筛选词: ", NamedTextColor.GRAY).append(Component.text(queryText, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("结果数: ", NamedTextColor.GRAY).append(Component.text(Integer.toString(this.resultCount.get()), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("当前页: ", NamedTextColor.GRAY).append(Component.text((pageIndex + 1) + " / " + pageCount, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("在铁砧输入框中键入 Material ID", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .build();
    }

    /**
     * 接收铁砧输入框的最新文本并更新搜索词.
     * <p>首尾空白会被忽略, 大小写也不会影响匹配.
     * 当有效搜索词改变时回到第一页, 避免旧页码在更短的新结果中指向页尾之外.
     * 其实就算不设置也没问题, 因为越界会回到最近的页数, 取消搜索也会回到未搜索时的页数.
     *
     * @param input 铁砧客户端提交的完整输入文本
     */
    private void updateInput(@NotNull String input) {
        // 空字符串和纯空白都会整理成空搜索词, 对应显示全部 Material
        String normalized = input.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals(this.input.get())) {
            return;
        }
        // 先发布新结果再回到第一页, 所有依赖项会沿同一条状态链刷新
        this.input.set(normalized);
        this.pages.page(0);
    }

    /**
     * 按 namespaced Material ID 对预构建清单做包含匹配.
     * <p>例如搜索 {@code stone} 会同时命中 {@code minecraft:stone} 和
     * {@code minecraft:stone_sword}. 空搜索词直接复用完整清单.
     *
     * @param query 已转成小写且去除首尾空白的搜索词
     * @return ID 中包含搜索词的 Material 条目
     */
    @NotNull
    private static List<MaterialEntry> filterMaterials(@NotNull String query) {
        // 无筛选时复用不可变清单, 避免每次清空输入都复制全部条目
        if (query.isEmpty()) {
            return ALL_MATERIAL;
        }
        // ALL_MATERIAL 按固定顺序扫描, 筛选后的菜单顺序与完整清单保持一致
        List<MaterialEntry> matches = new ArrayList<>();
        for (int index = 0; index < ALL_MATERIAL.size(); index++) {
            MaterialEntry entry = ALL_MATERIAL.get(index);
            if (entry.id().contains(query)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /**
     * 保存一项 Material 的搜索文本和展示物品, 让筛选阶段不需要重复创建 ItemStack.
     *
     * @param id namespaced Material ID, 例如 {@code minecraft:stone}
     * @param item 直接展示原始 Material 的 SparrowUI Item
     */
    private record MaterialEntry(@NotNull String id, @NotNull Item item) {
    }
}
