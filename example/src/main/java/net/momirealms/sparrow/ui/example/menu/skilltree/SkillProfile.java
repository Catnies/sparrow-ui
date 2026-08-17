package net.momirealms.sparrow.ui.example.menu.skilltree;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 一名玩家在某条技能线上的进度.
 *
 * @param category 所属类别
 * @param level 当前等级
 * @param experience 当前等级已积累的经验, 满值为 {@value #LEVEL_EXPERIENCE}
 */
record SkillProfile(@NotNull SkillCategory category, int level, int experience) {
    /**
     * 升一级需要的经验.
     */
    static final int LEVEL_EXPERIENCE = 1000;

    private static final int MAX_LEVEL = 36;

    /**
     * 读取一名玩家某条技能线的进度.
     *
     * <p>示例里没有真实存储, 这里由玩家 UUID 和类别推出一份稳定的假数据, 站的是一次数据库读取的位置:
     * 它就是 {@link SkillTreeMenu} 把类别菜单放到异步线程构建, 并且用 {@code TREE} 会话把构建结果留到会话结束的理由.
     *
     * @param viewer 要读取的玩家
     * @param category 要读取的技能线
     * @return 该玩家在该技能线上的进度
     */
    @NotNull
    static SkillProfile load(@NotNull Player viewer, @NotNull SkillCategory category) {
        int seed = viewer.getUniqueId().hashCode() * 31 + category.ordinal();
        return new SkillProfile(category, 1 + Math.floorMod(seed, MAX_LEVEL), Math.floorMod(seed >> 5, LEVEL_EXPERIENCE));
    }

    /**
     * 判断一个节点是否已经掌握.
     *
     * @param node 要判断的节点
     * @return 当前等级足够时返回 true
     */
    boolean mastered(@NotNull SkillCategory.Node node) {
        return this.level >= node.requiredLevel();
    }

    /**
     * 本条技能线上已经掌握的节点数量.
     *
     * @return 已掌握的节点数量
     */
    int masteredCount() {
        int mastered = 0;
        for (int index = 0; index < this.category.nodes().size(); index++) {
            if (this.mastered(this.category.nodes().get(index))) {
                mastered++;
            }
        }
        return mastered;
    }
}
