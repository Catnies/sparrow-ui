package net.momirealms.sparrow.ui.example.menu.skilltree;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.page.Scroll;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条技能线的技能树页, 由 {@link SkillTreeMenu} 作为下一扇打开.
 *
 * <p>整棵树铺在一个 {@link Scroll} 上: {@link SkillCategory#layout()} 的字符画按行喂进去,
 * 菜单一次显示 {@value #VISIBLE_ROWS} 行, 剩下的行靠右下角两个按钮滚动. 节点只是展示,
 * 掌握与否由 {@link SkillProfile} 的等级决定, 点了不会有事发生.
 *
 * <p>底部左边三个标签可以直接切到另一条线. 目标那条线已经进过时这是一次跨枝步入:
 * TREE 会话把当前位置移过去并重新打开原实例, 树的父子关系保持原样. 换句话说,
 * 一条线的上一层是第一次进入它时所在的那一页, 之后怎么跨线跳都不会再改.
 */
final class SkillCategoryMenu {
    private static final int VISIBLE_ROWS = 5; // 一屏显示的树行数
    private static final Item CONNECTOR = Item.simple(connector());
    private static final Item FILLER = Item.simple(filler());

    private final SkillTreeMenu root;    // 总览页, 跨枝切换时靠它复用同一批 Window
    private final SkillCategory category;
    private final SkillProfile profile;
    private final Scroll<Item> scroll;   // 整棵树的滚动状态
    private final NormalWindow window;

    /**
     * 构建一条技能线的技能树页.
     *
     * @param root 总览页
     * @param viewer 要查看菜单的玩家
     * @param profile 本条技能线的进度
     */
    SkillCategoryMenu(@NotNull SkillTreeMenu root, @NotNull Player viewer, @NotNull SkillProfile profile) {
        this.root = root;
        this.category = profile.category();
        this.profile = profile;
        this.scroll = Scroll.vertical(buildCells(this.category, profile), SkillCategory.WIDTH, VISIBLE_ROWS);
        this.window = NormalWindow.builder()
                .setTitle(Component.text("技能树 · " + this.category.title(), this.category.color()))
                .setUpperPane(this.buildTreePane())
                .build(viewer);
    }

    /**
     * 本页的 Window.
     *
     * @return 本页的 Window
     */
    @NotNull
    NormalWindow window() {
        return this.window;
    }

    /**
     * 创建技能树页的容器区域: 上面五行是树, 最后一行是控件.
     *
     * @return 本页的上部 Pane
     */
    @NotNull
    private NormalPane buildTreePane() {
        Pane.Builder<NormalPane, ?> builder = Pane.builder(
                "TTTTTTTTT",
                "TTTTTTTTT",
                "TTTTTTTTT",
                "TTTTTTTTT",
                "TTTTTTTTT",
                "MWH#I#UDB"
        );
        builder.addIngredient('T', this.scroll);
        builder.addIngredient('I', this.buildStatusItem());
        builder.addIngredient('U', this.buildScrollButton(-1));
        builder.addIngredient('D', this.buildScrollButton(1));
        builder.addIngredient('B', this.buildBackButton());
        builder.addIngredient('#', FILLER);
        // 模板里的 M, W, H 就是各类别自己声明的那个字符
        SkillCategory[] categories = SkillCategory.values();
        for (int index = 0; index < categories.length; index++) {
            SkillCategory target = categories[index];
            builder.addIngredient(target.tab(), this.buildTabButton(target));
        }
        return builder.build();
    }

    /**
     * 创建集中展示本条技能线进度和滚动位置的状态物品.
     *
     * @return 随滚动位置更新的状态 Item
     */
    @NotNull
    private Item buildStatusItem() {
        return Item.builder()
                .dependsOn(this.scroll.line(), this.scroll.maxLine())
                .setItemProvider(ignoredContext -> {
                    int line = this.scroll.line().get();
                    int maxLine = this.scroll.maxLine().get();
                    ItemStack itemStack = new ItemStack(Material.EXPERIENCE_BOTTLE);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(this.category.title() + " 进度", this.category.color()).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("节点只展示，不需要点亮。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("等级: ", NamedTextColor.GRAY).append(Component.text("Lv." + this.profile.level(), this.category.color())).decoration(TextDecoration.ITALIC, false),
                            Component.text("经验: ", NamedTextColor.GRAY).append(Component.text(this.profile.experience() + " / " + SkillProfile.LEVEL_EXPERIENCE, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("已掌握: ", NamedTextColor.GRAY).append(Component.text(this.profile.masteredCount() + " / " + this.category.nodes().size(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("显示位置: ", NamedTextColor.GRAY).append(Component.text((line + 1) + " / " + (maxLine + 1), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                            Component.text("离开再回来时这个位置还在。", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .build();
    }

    /**
     * 创建一个会随滚动位置自动更新的滚动按钮.
     *
     * @param step 滚动方向, 本示例使用 {@code -1} 表示向上, {@code 1} 表示向下
     * @return 带动态外观和点击行为的按钮 Item
     */
    @NotNull
    private Item buildScrollButton(int step) {
        return Item.builder()
                .dependsOn(this.scroll.line(), this.scroll.maxLine())
                .setItemProvider(ignoredContext -> {
                    int line = this.scroll.line().get();
                    int maxLine = this.scroll.maxLine().get();
                    boolean enabled;
                    String title;
                    String summary;
                    if (step < 0) {
                        enabled = line > 0;
                        title = "向上";
                        summary = enabled ? "查看树的上一段。" : "已经在树顶。";
                    } else {
                        enabled = line < maxLine;
                        title = "向下";
                        summary = enabled ? "查看树的下一段。" : "已经在树底。";
                    }

                    ItemStack itemStack = new ItemStack(enabled ? Material.ARROW : Material.GRAY_DYE);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(title, enabled ? NamedTextColor.YELLOW : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text(summary, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("显示位置: ", NamedTextColor.GRAY).append(Component.text((line + 1) + " / " + (maxLine + 1), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                // 禁用状态负责视觉提示, Scroll.advance 会把越界行号夹在有效范围内
                .addClickHandler(ignoredClick -> this.scroll.advance(step))
                .build();
    }

    /**
     * 创建切换到另一条技能线的标签按钮, 当前这条只作为高亮指示.
     *
     * @param target 标签对应的技能线
     * @return 标签按钮 Item
     */
    @NotNull
    private Item buildTabButton(@NotNull SkillCategory target) {
        boolean current = target == this.category;
        SkillProfile targetProfile = this.root.profile(target);
        ItemStack itemStack = new ItemStack(target.icon());
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(target.title(), target.color()).decoration(TextDecoration.ITALIC, false));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Component.text(current ? "正在查看这条技能线。" : "直接跳到这条技能线。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("等级: ", NamedTextColor.GRAY).append(Component.text("Lv." + targetProfile.level(), target.color())).decoration(TextDecoration.ITALIC, false),
                Component.text("已掌握: ", NamedTextColor.GRAY).append(Component.text(targetProfile.masteredCount() + " / " + target.nodes().size(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text(current ? "当前所在" : "点击切换", current ? NamedTextColor.DARK_GRAY : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
        )));
        if (current) {
            itemStack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        ItemBuilder builder = Item.builder().setItemProvider(ignoredContext -> itemStack);
        if (!current) {
            builder.addClickHandler(click -> this.root.openCategory(click.window(), target));
        }
        return builder.build();
    }

    /**
     * 创建回到上一层的按钮.
     * <p>用的是 {@link Window#backOrClose()}: 有上一扇就返回, 没有就关闭.
     *
     * @return 返回按钮 Item
     */
    @NotNull
    private Item buildBackButton() {
        return Item.builder()
                .setItemProvider(ignoredContext -> {
                    ItemStack itemStack = new ItemStack(Material.SPECTRAL_ARROW);
                    itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text("返回", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                            Component.text("回到树上的上一层。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("第一次从哪一页进来，", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.text("上一层就一直是那一页；", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.text("用下面的标签跨线跳转不会改变它。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    )));
                    return itemStack;
                })
                .addClickHandler(click -> click.window().backOrClose())
                .build();
    }

    /**
     * 把一条技能线的字符画铺成滚动要用的连续序列.
     *
     * @param category 技能线
     * @param profile 该技能线的进度
     * @return 按行铺开的格子, 长度是行数乘以 {@link SkillCategory#WIDTH}
     */
    @NotNull
    private static List<Item> buildCells(@NotNull SkillCategory category, @NotNull SkillProfile profile) {
        List<String> layout = category.layout();
        List<Item> cells = new ArrayList<>(layout.size() * SkillCategory.WIDTH);
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            for (int column = 0; column < line.length(); column++) {
                cells.add(cell(category, profile, line.charAt(column)));
            }
        }
        return cells;
    }

    /**
     * 把字符画里的一个字符翻译成一个格子.
     *
     * @param category 技能线
     * @param profile 该技能线的进度
     * @param symbol 字符画里的字符
     * @return 该位置要显示的 Item
     */
    @NotNull
    private static Item cell(@NotNull SkillCategory category, @NotNull SkillProfile profile, char symbol) {
        return switch (symbol) {
            case ' ' -> Item.empty();
            case '|', '-', '+' -> CONNECTOR;
            default -> nodeItem(category, profile, category.node(symbol));
        };
    }

    /**
     * 创建一个技能节点的展示物品.
     *
     * @param category 技能线
     * @param profile 该技能线的进度
     * @param node 技能节点
     * @return 该节点的展示 Item
     */
    @NotNull
    private static Item nodeItem(@NotNull SkillCategory category, @NotNull SkillProfile profile, @NotNull SkillCategory.Node node) {
        boolean mastered = profile.mastered(node);
        ItemStack itemStack = new ItemStack(node.icon());
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(node.name(), mastered ? category.color() : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Component.text(node.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("需要等级: ", NamedTextColor.GRAY).append(Component.text("Lv." + node.requiredLevel(), mastered ? NamedTextColor.AQUA : NamedTextColor.RED)).decoration(TextDecoration.ITALIC, false),
                Component.text("状态: ", NamedTextColor.GRAY).append(Component.text(mastered ? "已掌握" : "未解锁", mastered ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)).decoration(TextDecoration.ITALIC, false)
        )));
        if (mastered) {
            itemStack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return Item.simple(itemStack);
    }

    /**
     * 创建技能之间连接线用的物品.
     *
     * @return 无名称的浅灰色玻璃板
     */
    @NotNull
    private static ItemStack connector() {
        ItemStack itemStack = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        return itemStack;
    }

    /**
     * 创建填充空位用的背景物品.
     *
     * @return 无名称的灰色玻璃板
     */
    @NotNull
    private static ItemStack filler() {
        ItemStack itemStack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.empty());
        return itemStack;
    }
}
