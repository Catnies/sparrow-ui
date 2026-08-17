package net.momirealms.sparrow.ui.example.menu.searchcapture;

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
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.window.WindowSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 演示一段由两扇 Window 组成的会话: 浏览列表在这里, 输入搜索词去铁砧那边捕捉.
 *
 * <p>这是 {@link net.momirealms.sparrow.ui.example.menu.searchpage.SearchPageMenu} 的另一种拆法.
 * 那个示例把铁砧本身当作唯一的菜单, 输入框和结果同处一屏; 这个示例把两件事分成两扇窗:
 * <ol>
 *     <li>本菜单是根窗: 上三行显示当前页的 Material, 第四行放翻页, 搜索, 状态和关闭.</li>
 *     <li>点搜索按钮时 {@link SearchCaptureAnvil} 作为下一扇打开, 玩家在铁砧文本框里输入.</li>
 *     <li>铁砧上点确认才把输入交回来筛一次, 按 ESC 则原样返回; 两条路都回到本菜单原实例,
 *         页码和搜索词都还在.</li>
 * </ol>
 *
 * <p>会话相关的三处声明都写在下面的构造器里:
 * <ul>
 *     <li>{@link WindowSession.Kind#STACK}: 默认型, 铁砧被返回时会话直接丢掉它的引用,
 *         因此下次点搜索进入的是一扇全新的铁砧, 上一次的临时状态不会串过来.</li>
 *     <li>{@link Window.Builder#addSessionEndHandler}: 整段交互结束时恰好触发一次,
 *         无论玩家是在列表还是在铁砧里离开的, 都只有这一句提示.</li>
 *     <li>{@link Window.Builder#setData}: 把本对象挂在根窗上, 铁砧那边靠它找回搜索状态.</li>
 * </ul>
 */
public final class SearchCaptureMenu {
    private static final int PAGE_SIZE = 27; // 上三行的可展示槽位数

    /**
     * 所有可以作为物品显示的非旧版 Material, 同时保存搜索用 ID 和可直接展示的原始物品.
     */
    private static final List<MaterialEntry> ALL_MATERIAL = Arrays.stream(Material.values())
            .filter(it -> !it.isLegacy() && it.isItem() && !it.isAir())
            .map(it -> new MaterialEntry(it.getKey().asString(), Item.simple(new ItemStack(it))))
            .toList();
    private static final Item FILLER = Item.simple(filler()); // 第四行的空位背景

    private final MutableSignal<String> input; // 已去除首尾空白并转成小写的当前搜索词
    private final Signal<Integer> resultCount; // 当前搜索词命中的 Material 数量
    private final Page<MaterialEntry> pages;   // 把筛选结果按每页 27 个切分后的分页状态
    private final NormalWindow window;         // 会话的根窗

    /**
     * 为指定玩家异步构建并打开一次全新的浏览会话.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        // 菜单对象, Pane 和 Window 都在异步线程构建, open 再把打开送进玩家的实体线程
        return Scheduling.async(SparrowExample.INSTANCE, () -> new SearchCaptureMenu(viewer).window.open())
                .thenCompose(opening -> opening);
    }

    /**
     * 创建一次浏览所需的独立搜索状态与根窗.
     *
     * @param viewer 要查看菜单的玩家
     */
    private SearchCaptureMenu(@NotNull Player viewer) {
        // input 是搜索数据流的起点, 变化后会重新得到整份筛选结果
        this.input = Signal.of("");
        Signal<List<MaterialEntry>> results = this.input.mapDistinct(SearchCaptureMenu::filterMaterials);
        // 结果数量和分页器共同消费同一份筛选结果, 避免维护两套容易失配的状态
        this.resultCount = results.map(List::size);
        this.pages = Page.of(results, PAGE_SIZE);
        this.window = NormalWindow.builder()
                .setTitle(Component.text("Material 浏览"))
                .setUpperPane(this.buildBrowserPane())
                // 本窗经 open() 直接打开, 因此下面三项根声明都会生效
                .setSessionKind(WindowSession.Kind.STACK)
                .addSessionEndHandler(reason -> viewer.sendMessage(endMessage(reason)))
                .setData(this)
                .build(viewer);
    }

    /**
     * 创建四行的容器区域: 上三行是当前页的 Material, 第四行是控件.
     *
     * @return 根窗的上部 Pane
     */
    @NotNull
    private NormalPane buildBrowserPane() {
        return Pane.builder(
                        "MMMMMMMMM",
                        "MMMMMMMMM",
                        "MMMMMMMMM",
                        "P##SIX##N"
                )
                // Page 提供当前页内容; 条目只需同步拆出预构建 Item, 因此直接使用当前线程执行
                .addIngredient('M', this.pages, entry -> Element.item(entry.item()), Runnable::run)
                .addIngredient('P', this.buildPageButton(-1))
                .addIngredient('S', this.buildSearchButton())
                .addIngredient('I', this.buildStatusItem())
                .addIngredient('X', this.buildCloseButton())
                .addIngredient('N', this.buildPageButton(1))
                .addIngredient('#', FILLER)
                .build();
    }

    /**
     * 创建一个会随当前页和总页数自动更新的翻页按钮.
     *
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
     * 创建打开铁砧输入界面的搜索按钮.
     * <p>左键把铁砧作为下一扇打开, 右键就地清空筛选, 不必再走一次输入界面.
     *
     * @return 随搜索状态自动更新的按钮 Item
     */
    @NotNull
    private Item buildSearchButton() {
        return Item.builder()
                .dependsOn(this.input, this.resultCount)
                .setItemProvider(ignoredContext -> {
                    String query = this.input.get();
                    ItemStack itemStack = new ItemStack(Material.NAME_TAG);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("搜索 Material", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("到铁砧界面输入 Material ID。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("筛选词: ", NamedTextColor.GRAY).append(Component.text(query.isEmpty() ? "未筛选" : query, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("结果数: ", NamedTextColor.GRAY).append(Component.text(Integer.toString(this.resultCount.get()), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("左键打开输入界面", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                            Component.text("右键清空筛选", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(click -> {
                    if (click.clickType().isRightClick()) {
                        this.query("");
                        return;
                    }
                    // 本窗成为上一扇, 铁砧成为当前窗, 两者同属一段会话
                    SearchCaptureAnvil.openFrom(click.window());
                })
                .build();
    }

    /**
     * 创建集中展示当前搜索词, 结果数和页码的状态物品.
     *
     * @return 随搜索和分页状态自动更新的指南针 Item
     */
    @NotNull
    private Item buildStatusItem() {
        // 任一展示值变化都使 Item 失效, 下一次渲染会读取同一时刻的最新状态
        return Item.builder()
                .dependsOn(this.input, this.resultCount, this.pages.page(), this.pages.count())
                .setItemProvider(ignoredContext -> {
                    String query = this.input.get();
                    int pageIndex = this.pages.page().get();
                    int pageCount = this.pages.count().get();

                    ItemStack itemStack = new ItemStack(Material.COMPASS);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("搜索结果", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("在铁砧界面确认之后筛选词才会变。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("筛选词: ", NamedTextColor.GRAY).append(Component.text(query.isEmpty() ? "未筛选" : query, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("结果数: ", NamedTextColor.GRAY).append(Component.text(Integer.toString(this.resultCount.get()), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("当前页: ", NamedTextColor.GRAY).append(Component.text((pageIndex + 1) + " / " + pageCount, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .build();
    }

    /**
     * 创建通用的返回或关闭按钮.
     * <p>{@link Window#backOrClose()} 有上一扇时返回, 没有就关闭当前窗;
     * 本窗是根窗, 因此这里点下去等同关闭整段会话, 结束提示随之发出.
     *
     * @return 关闭按钮 Item
     */
    @NotNull
    private Item buildCloseButton() {
        return Item.builder()
                .setItemProvider(ignoredContext -> {
                    ItemStack itemStack = new ItemStack(Material.BARRIER);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("关闭菜单", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("结束本次浏览。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("本菜单是这段会话的根窗，", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.text("没有上一扇可回，因此这里等同关闭。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(click -> click.window().backOrClose())
                .build();
    }

    /**
     * 当前生效的搜索词.
     *
     * @return 已整理过的搜索词, 未筛选时为空字符串
     */
    @NotNull
    String query() {
        return this.input.get();
    }

    /**
     * 应用一次新的搜索词.
     * <p>首尾空白会被忽略, 大小写也不会影响匹配.
     * 当有效搜索词改变时回到第一页, 避免旧页码在更短的新结果中指向页尾之外.
     * <p>这一步要重扫整份 Material 清单并重算分页, 因此只在玩家确认时调用一次,
     * 而不是跟着铁砧的每一次击键跑.
     *
     * @param query 要应用的搜索文本
     */
    void query(@NotNull String query) {
        // 空字符串和纯空白都会整理成空搜索词, 对应显示全部 Material
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals(this.input.get())) {
            return;
        }
        // 先发布新结果再回到第一页, 所有依赖项会沿同一条状态链刷新
        this.input.set(normalized);
        this.pages.page(0);
    }

    /**
     * 按 namespaced Material ID 对预构建清单做包含匹配.
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
     * 组装整段会话结束时的唯一一句提示.
     *
     * @param reason 结束原因
     * @return 发给玩家的提示
     */
    @NotNull
    private static Component endMessage(@NotNull InventoryCloseEvent.Reason reason) {
        return Component.text("已结束本次 Material 搜索。", NamedTextColor.GRAY)
                .append(Component.text(" (" + describe(reason) + ")", NamedTextColor.DARK_GRAY));
    }

    /**
     * 把会话结束原因翻译成面向玩家的说法.
     *
     * @param reason 结束原因
     * @return 面向玩家的说明
     */
    @NotNull
    private static String describe(@NotNull InventoryCloseEvent.Reason reason) {
        return switch (reason) {
            case PLAYER -> "玩家关闭";
            case DISCONNECT -> "玩家断线";
            case PLUGIN -> "插件结束";
            case OPEN_NEW -> "被其他菜单顶替";
            default -> reason.name();
        };
    }

    /**
     * 创建第四行填充空位用的背景物品.
     *
     * @return 无名称的灰色玻璃板
     */
    @NotNull
    private static ItemStack filler() {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        return itemStack;
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
