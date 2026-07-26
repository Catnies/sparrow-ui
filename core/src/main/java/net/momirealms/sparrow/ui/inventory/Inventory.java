package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一组受事务保护的物品槽.
 * <p>三条硬契约:
 * <ul>
 *   <li>空槽唯一表示是 {@code null}, 永不出现 AIR 或数量不大于 0 的实例;</li>
 *   <li>所有读取返回克隆, 修改读出的物品不影响库存;</li>
 *   <li>所有写入经事务管线(plan, pre, commit, post), 事件单位是事务而不是槽.</li>
 * </ul>
 * 读路径无锁: 直接读取当前不可变快照, 任意线程可安全调用.
 */
public interface Inventory {

    /**
     * 槽位数量, 构造后固定.
     */
    int size();

    /**
     * 读取指定槽位的物品克隆; 空槽返回 {@code null}.
     *
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    ItemStack itemAt(int slot);

    /**
     * 返回全部槽位的一致性快照.
     */
    @Nullable
    ItemStack @NotNull [] snapshot();

    /**
     * 订阅事务提交前事件. 处理器可取消整个事务;
     * 运行在提交者线程且不持有任何锁.
     *
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    Subscription subscribePreUpdate(@NotNull Observer<? super TransactionPreEvent> observer);

    /**
     * 订阅事务提交后事件. 对同一库存, 事件顺序与提交顺序一致; 派发线程任意.
     *
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    Subscription subscribePostUpdate(@NotNull Observer<? super TransactionPostEvent> observer);
}
