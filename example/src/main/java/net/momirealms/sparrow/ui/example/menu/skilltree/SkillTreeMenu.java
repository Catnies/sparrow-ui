package net.momirealms.sparrow.ui.example.menu.skilltree;

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
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.window.WindowSession;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 个人技能菜单的总览页, 也是一段 {@link WindowSession.Kind#TREE} 会话的根窗.
 *
 * <p>结构很简单: 总览页放三条技能线的入口, 点进去是那条线的技能树 ({@link SkillCategoryMenu}).
 * 值得看的是会话怎么记住这些子页:
 * <ul>
 *     <li>每条线的菜单第一次进入时才构建, 构建结果记在 {@link #opened} 里, 因此永远只有一扇实例.</li>
 *     <li>TREE 会话按实例查重: 再次步入同一扇窗时它认出这是老成员, 只把当前位置移过去并重新打开原实例,
 *         滚动位置因此原样留着; 换成默认的 STACK 型, 返回时那扇窗就被丢掉, 下次进入是从头开始的新树.</li>
 *     <li>类别页底部的三个标签可以直接跨枝切换, 例如从伐木跳到挖矿. 跨枝步入同样只是移动当前位置,
 *         树的父子关系保持原样: 一条线的上一层是第一次进入它时所在的那一页, 之后怎么跳都不会再改.</li>
 * </ul>
 *
 * <p>本示例的类别页没有开 {@code backOnPlayerClose}, 所以在类别页按 ESC 是结束整段浏览;
 * 想让 ESC 只回上一扇, 看 {@link net.momirealms.sparrow.ui.example.menu.searchcapture.SearchCaptureAnvil}.
 */
public final class SkillTreeMenu {
    private static final Item FILLER = Item.simple(filler()); // 空位背景

    private final Player viewer;
    private final Map<SkillCategory, SkillProfile> profiles;            // 打开时一次读出的三条线进度
    private final Map<SkillCategory, CompletableFuture<Window>> opened; // 已经构建过的类别菜单
    private final NormalWindow window;                                  // 会话的根窗

    /**
     * 为指定玩家异步构建并打开一次全新的技能浏览会话.
     *
     * @param viewer 要查看菜单的在线玩家
     * @return 菜单实际打开完成后的结果
     */
    @NotNull
    public static CompletableFuture<Window.OpenResult> open(@NotNull Player viewer) {
        return Scheduling.async(SparrowExample.INSTANCE, () -> new SkillTreeMenu(viewer).window.open())
                .thenCompose(opening -> opening);
    }

    /**
     * 读取三条线的进度并创建总览页.
     *
     * @param viewer 要查看菜单的玩家
     */
    private SkillTreeMenu(@NotNull Player viewer) {
        this.viewer = viewer;
        this.profiles = new EnumMap<>(SkillCategory.class);
        this.opened = new EnumMap<>(SkillCategory.class);
        SkillCategory[] categories = SkillCategory.values();
        for (int index = 0; index < categories.length; index++) {
            SkillCategory category = categories[index];
            this.profiles.put(category, SkillProfile.load(viewer, category));
        }
        this.window = NormalWindow.builder()
                .setTitle(Component.text("个人技能", NamedTextColor.DARK_AQUA))
                .setUpperPane(this.buildOverviewPane())
                // 本窗经 open() 直接打开, 因此这条根声明生效: 整段浏览按树来记
                .setSessionKind(WindowSession.Kind.TREE)
                .build(viewer);
    }

    /**
     * 创建总览页的容器区域.
     *
     * @return 根窗的上部 Pane
     */
    @NotNull
    private NormalPane buildOverviewPane() {
        Pane.Builder<NormalPane, ?> builder = Pane.builder(
                "####I####",
                "#M##W##H#",
                "#########"
        );
        builder.addIngredient('I', this.buildOverviewItem());
        builder.addIngredient('#', FILLER);
        // 模板里的 M, W, H 就是各类别自己声明的那个字符
        SkillCategory[] categories = SkillCategory.values();
        for (int index = 0; index < categories.length; index++) {
            SkillCategory category = categories[index];
            builder.addIngredient(category.tab(), this.buildCategoryButton(category));
        }
        return builder.build();
    }

    /**
     * 创建汇总三条线等级的说明物品.
     *
     * @return 静态的总览 Item
     */
    @NotNull
    private Item buildOverviewItem() {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("点击下面的图标查看该技能的技能树。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        SkillCategory[] categories = SkillCategory.values();
        for (int index = 0; index < categories.length; index++) {
            SkillCategory category = categories[index];
            SkillProfile profile = this.profiles.get(category);
            lore.add(Component.text(category.title() + ": ", NamedTextColor.GRAY)
                    .append(Component.text("Lv." + profile.level(), category.color()))
                    .decoration(TextDecoration.ITALIC, false));
        }
        ItemStack itemStack = new ItemStack(Material.KNOWLEDGE_BOOK);
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(this.viewer.getName() + " 的技能", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        return Item.simple(itemStack);
    }

    /**
     * 创建一条技能线的入口按钮.
     *
     * @param category 技能线
     * @return 进入该技能线的按钮 Item
     */
    @NotNull
    private Item buildCategoryButton(@NotNull SkillCategory category) {
        SkillProfile profile = this.profiles.get(category);
        ItemStack itemStack = new ItemStack(category.icon());
        itemStack.setData(DataComponentTypes.CUSTOM_NAME, Component.text(category.title(), category.color()).decoration(TextDecoration.ITALIC, false));
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                Component.text("查看这条技能线的技能树。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("等级: ", NamedTextColor.GRAY).append(Component.text("Lv." + profile.level(), category.color())).decoration(TextDecoration.ITALIC, false),
                Component.text("经验: ", NamedTextColor.GRAY).append(Component.text(profile.experience() + " / " + SkillProfile.LEVEL_EXPERIENCE, NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                Component.text("已掌握: ", NamedTextColor.GRAY).append(Component.text(profile.masteredCount() + " / " + category.nodes().size(), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("点击进入技能树", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
        )));
        return Item.builder()
                .setItemProvider(ignoredContext -> itemStack)
                .addClickHandler(click -> this.openCategory(click.window(), category))
                .build();
    }

    /**
     * 从当前窗步入某条技能线的技能树.
     *
     * @param source 发起本次跳转的 Window
     * @param category 要查看的技能线
     */
    void openCategory(@NotNull Window source, @NotNull SkillCategory category) {
        // 同一类别永远复用同一个 future, 于是也永远是同一扇 Window;
        // TREE 会话认出老成员后只移动当前位置, 那扇窗连滚动位置都还在原处
        CompletableFuture<Window> pending = this.opened.computeIfAbsent(category, this::buildCategoryWindow);
        source.navigate(pending).exceptionally(throwable -> {
            SparrowExample.INSTANCE.getLogger().log(Level.SEVERE, "Failed to open the " + category.title() + " skill tree", throwable);
            return null;
        });
    }

    /**
     * 在异步线程构建一条技能线的菜单.
     *
     * @param category 技能线
     * @return 构建中的类别菜单 Window
     */
    @NotNull
    private CompletableFuture<Window> buildCategoryWindow(@NotNull SkillCategory category) {
        SkillProfile profile = this.profiles.get(category);
        // 整棵树要铺 99 个格子, 每条线只在第一次进入时构建一次, 之后由会话留着
        return Scheduling.async(SparrowExample.INSTANCE, () -> new SkillCategoryMenu(this, this.viewer, profile).window());
    }

    /**
     * 本次浏览已经读出的技能进度.
     *
     * @param category 技能线
     * @return 该技能线的进度
     */
    @NotNull
    SkillProfile profile(@NotNull SkillCategory category) {
        return this.profiles.get(category);
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
