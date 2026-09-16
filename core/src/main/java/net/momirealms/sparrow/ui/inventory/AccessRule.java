package net.momirealms.sparrow.ui.inventory;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface AccessRule {
    /**
     * 判断本次槽位访问是否允许, 全局规则与槽级规则必须同时通过.
     * <p><strong>规则只读, 不得修改库存或产生业务副作用</strong>. 规划和事件后的复核可能重复调用.
     *
     * @param context 本次候选变化与实际物品流动
     * @return 是否允许本次候选
     */
    boolean test(@NotNull AccessContext context);
}
