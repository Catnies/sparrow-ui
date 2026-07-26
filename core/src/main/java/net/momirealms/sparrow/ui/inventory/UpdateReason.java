package net.momirealms.sparrow.ui.inventory;

/**
 * 一次库存事务的变更原因.
 * <p>这是开放的标记接口: 插件可以实现自己的原因类型, 事务处理器用 {@code instanceof}
 * 判断来源并决定放行或取消. 事务的 reason 一律非空; 程序化操作缺省使用
 * {@link Program#INSTANCE}, 不存在 "null 表示无原因" 的写法.
 */
public interface UpdateReason {

    /**
     * 程序化操作的缺省原因, 例如初始化, 重置或管理命令触发的写入.
     */
    enum Program implements UpdateReason {
        INSTANCE
    }
}
