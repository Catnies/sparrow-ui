package net.momirealms.sparrow.ui.example.menu.skilltree;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 三条技能线的静态定义: 树的形状, 节点表和配色.
 *
 * <p>形状写在 {@link #layout()} 的字符画里, 一行 {@value #WIDTH} 个字符对应菜单里的一行:
 * 空格是空槽, {@code |}, {@code -} 和 {@code +} 是连接线, 其余字符各对应 {@link #node(char)} 里的一个节点.
 * 三条线共用同一副骨架, 想让某条线长得不一样, 换掉它的那份字符画就行.
 *
 * <p>这里只有内容, 与玩家有关的等级和掌握情况在 {@link SkillProfile}.
 */
enum SkillCategory {
    MINING('M', "挖矿", Material.DIAMOND_PICKAXE, NamedTextColor.AQUA, List.of(
            new Node('A', "矿工基础", Material.STONE_PICKAXE, 1, "挖掘石头时略微加快。"),
            new Node('B', "精准开采", Material.IRON_PICKAXE, 5, "更容易保留矿物本体。"),
            new Node('C', "幸运矿脉", Material.GOLDEN_PICKAXE, 8, "偶尔多掉落一份矿物。"),
            new Node('D', "深层洞察", Material.DEEPSLATE_DIAMOND_ORE, 12, "深板岩层的挖掘不再吃力。"),
            new Node('E', "双倍掉落", Material.RAW_IRON, 15, "有几率把矿物翻倍。"),
            new Node('F', "矿工直觉", Material.SPYGLASS, 22, "附近有矿脉时会有提示。"),
            new Node('G', "大师矿工", Material.NETHERITE_PICKAXE, 30, "上述效果全部提升一档。")
    )),
    WOODCUTTING('W', "伐木", Material.IRON_AXE, NamedTextColor.GREEN, List.of(
            new Node('A', "伐木基础", Material.STONE_AXE, 1, "砍伐原木时略微加快。"),
            new Node('B', "顺势劈砍", Material.IRON_AXE, 5, "一次砍断相连的两段原木。"),
            new Node('C', "整树采集", Material.OAK_LOG, 8, "偶尔一次放倒整棵树。"),
            new Node('D', "木材加工", Material.OAK_PLANKS, 12, "原木换木板时更划算。"),
            new Node('E', "落叶清理", Material.SHEARS, 15, "树叶掉落更多树苗。"),
            new Node('F', "林间行者", Material.OAK_SAPLING, 22, "树苗成长得更快。"),
            new Node('G', "伐木大师", Material.NETHERITE_AXE, 30, "上述效果全部提升一档。")
    )),
    HARVESTING('H', "采集", Material.WHEAT, NamedTextColor.GOLD, List.of(
            new Node('A', "采集基础", Material.WOODEN_HOE, 1, "收割作物时略微加快。"),
            new Node('B', "双倍收成", Material.WHEAT, 5, "有几率多收一份作物。"),
            new Node('C', "绿色拇指", Material.BONE_MEAL, 8, "骨粉的催熟效果更好。"),
            new Node('D', "药草辨识", Material.SUSPICIOUS_STEW, 12, "采集到的草药品质更高。"),
            new Node('E', "种子回收", Material.WHEAT_SEEDS, 15, "收割时保留更多种子。"),
            new Node('F', "堆肥大师", Material.COMPOSTER, 22, "堆肥箱产出更多骨粉。"),
            new Node('G', "采集大师", Material.GOLDEN_HOE, 30, "上述效果全部提升一档。")
    ));

    /**
     * 字符画一行的宽度, 与菜单一行的槽位数一致.
     */
    static final int WIDTH = 9;

    // 三条线共用的骨架: 一条主干先分成两枝, 再合回来继续往下.
    private static final List<String> TRUNK = List.of(
            "    A    ",
            "    |    ",
            "  B-+-C  ",
            "  |   |  ",
            "  D   E  ",
            "  |   |  ",
            "  +---+  ",
            "    |    ",
            "    F    ",
            "    |    ",
            "    G    "
    );

    private final char tab;                    // 菜单模板里代表本类别按钮的字符
    private final String title;
    private final Material icon;
    private final NamedTextColor color;
    private final List<Node> nodes;
    private final Map<Character, Node> nodesBySymbol;

    SkillCategory(char tab, @NotNull String title, @NotNull Material icon, @NotNull NamedTextColor color, @NotNull List<Node> nodes) {
        this.tab = tab;
        this.title = title;
        this.icon = icon;
        this.color = color;
        this.nodes = nodes;
        Map<Character, Node> bySymbol = new HashMap<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            bySymbol.put(node.symbol(), node);
        }
        this.nodesBySymbol = Map.copyOf(bySymbol);
    }

    /**
     * 菜单模板里代表本类别按钮的字符.
     *
     * @return 模板字符
     */
    char tab() {
        return this.tab;
    }

    /**
     * 面向玩家的类别名称.
     *
     * @return 类别名称
     */
    @NotNull
    String title() {
        return this.title;
    }

    /**
     * 类别按钮使用的图标.
     *
     * @return 图标材质
     */
    @NotNull
    Material icon() {
        return this.icon;
    }

    /**
     * 本类别在菜单中的主色.
     *
     * @return 主色
     */
    @NotNull
    NamedTextColor color() {
        return this.color;
    }

    /**
     * 描述技能树形状的字符画.
     *
     * @return 从上到下排列的模板行, 每行 {@value #WIDTH} 个字符
     */
    @NotNull
    List<String> layout() {
        return TRUNK;
    }

    /**
     * 本类别的全部节点.
     *
     * @return 按解锁等级从低到高排列的节点
     */
    @NotNull
    List<Node> nodes() {
        return this.nodes;
    }

    /**
     * 按字符画中的字符找到对应节点.
     *
     * @param symbol 字符画中的字符
     * @return 对应节点, 字符没有对应节点时为 null
     */
    Node node(char symbol) {
        return this.nodesBySymbol.get(symbol);
    }

    /**
     * 技能树上的一个节点.
     *
     * @param symbol 字符画中代表本节点的字符
     * @param name 面向玩家的技能名称
     * @param icon 展示用的图标材质
     * @param requiredLevel 解锁需要的等级
     * @param description 面向玩家的效果说明
     */
    record Node(char symbol, @NotNull String name, @NotNull Material icon, int requiredLevel, @NotNull String description) {
    }
}
