package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.TransactionPostEvent;
import net.momirealms.sparrow.ui.inventory.event.TransactionPreEvent;
import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.inventory.operation.AddResult;
import net.momirealms.sparrow.ui.inventory.operation.CollectResult;
import net.momirealms.sparrow.ui.inventory.operation.OperationCategory;
import net.momirealms.sparrow.ui.inventory.operation.RemoveResult;
import net.momirealms.sparrow.ui.inventory.operation.SlotOrder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 一组受事务保护的物品槽.
 * <p>三条硬契约:
 * <ul>
 *   <li>空槽唯一表示是 {@code null}, 永不出现 AIR 或数量不大于 0 的实例;</li>
 *   <li>所有读取返回克隆, 修改读出的物品不影响库存;</li>
 *   <li>所有写入经事务管线(plan, pre, commit, post), 事件单位是事务而不是物品槽.</li>
 * </ul>
 * 读路径无锁, 直接读取当前不可变快照, 任意线程可安全调用.
 * 写操作遇并发冲突统一返回 {@link TransactionResult.Conflicted} 且零变更.
 * ReferencingInventory 当前执行线程无法访问外部目标时返回{@link TransactionResult.Unavailable}.
 */
public interface Inventory {
    int DEFAULT_MAX_STACK_SIZE = 99; // 槽位的默认堆叠上限.

    /**
     * 槽位数量, 构造后固定.
     */
    int size();

    /**
     * 读取指定槽位的物品克隆;
     * 空槽返回 {@code null}.
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
     * 指定槽位自身的堆叠上限, 不含物品自身上限.
     * 放入类算法使用的有效上限是 {@code min(槽上限, 物品自身上限)}.
     *
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    int slotMaxStackSize(int slot);

    /**
     * 指定操作类别使用的迭代顺序.
     */
    @NotNull
    SlotOrder iterationOrder(@NotNull OperationCategory category);

    /**
     * 指定操作类别下选择目标库存的排序键, 值大者优先.
     * 快速转移消费 ADD 类别, 收集消费 COLLECT 类别, 各类别独立配置.
     */
    int guiPriority(@NotNull OperationCategory category);

    /**
     * 权威写入单个槽位: 覆盖为给定物品({@code null} 清空), 不受堆叠上限约束.
     * 权威写恒定产生事务与事件, 即使新值与当前值相等也不做无变更抑制.
     *
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item);

    /**
     * 与 {@link #setItem} 相同, 但跳过全部 pre 观察者, 不可被取消; post 仍照常派发.
     * 用于程序化强制写入(初始化, 重置, 管理命令).
     */
    @NotNull
    TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item);

    /**
     * 向单个槽位放入物品: 空槽直接放入, 与槽内物品相似则合并, 均受有效上限约束;
     * 不相似时不发生任何变更, 全部数量计入 remaining.
     *
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item);

    /**
     * 读-改-写单个槽位: modifier 收到当前物品的克隆(空槽为 {@code null}),
     * 返回值作为新的槽内容(可返回 {@code null} 清空). modifier 在锁外执行,
     * 其抛出的异常原样传播给调用方, 传播时零变更.
     * 与 {@link #setItem} 同为权威写: 返回值恒定提交, 即使与当前值相等.
     *
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier);

    /**
     * 调整槽内物品数量. 减量只受下限 0 约束(减到 0 清空槽位), 权威写入的超上限堆
     * 减量精确生效; 增量收敛到有效上限, 已满或超满的槽增量是无变更操作;
     * 空槽没有物品模板, 调整同样无变更.
     *
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change);

    /**
     * 批量放入: 按 ADD 顺序先合并相似非满堆, 再占用空槽, 放不完的数量计入 remaining.
     * 整个放入是一个事务.
     */
    @NotNull
    AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item);

    /**
     * 批量收集与 template 相似的物品, 至多 {@code upTo} 个: 按 COLLECT 顺序
     * 先收取非满堆(保持满堆完整), 不足再收取满堆. 整个收集是一个事务.
     * template 只参与相似性判定, 其数量字段被忽略; {@code upTo} 不大于 0 时是无变更操作.
     */
    @NotNull
    CollectResult collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo);

    /**
     * 批量移除 matcher 命中的物品, 至多 {@code upTo} 个, 按 OTHER 顺序逐槽扣减.
     * matcher 收到槽内物品的克隆, 在锁外执行, 其抛出的异常原样传播给调用方且传播时
     * 零变更. 整个移除是一个事务; {@code upTo} 不大于 0 时是无变更操作.
     */
    @NotNull
    RemoveResult remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo);

    /**
     * 零副作用地推演 {@link #add}: 返回将无法放入的数量, 不加锁, 不派发事件.
     */
    int simulateAdd(@NotNull ItemStack item);

    /**
     * 零副作用地推演 {@link #collect}: 返回将收集到的数量.
     */
    int simulateCollect(@NotNull ItemStack template, int upTo);

    /**
     * 库存能否完整容纳给定物品, 等价于 {@code simulateAdd(item) == 0}.
     */
    boolean canHold(@NotNull ItemStack item);

    /**
     * 驱动镜像型根库存与其外部真相对账; 快照型库存无操作.
     * 集成层(如 Window 渲染循环)可以每 tick 调用一次；引用目标当前不可访问时
     * 实现会静默跳过, 可访问时把外部变更以 External 原因送入事件流.
     */
    void refresh();

    /**
     * 把本库存尽力适配为 Bukkit 库存接口, 同一库存恒返回同一适配器实例(Bukkit 侧
     * 可以引用身份关联). 适配器的写路径走 Sparrow 事务(原因为
     * {@link UpdateReason.Program}); 线程契约随被适配库存，引用库存不可访问时
     * void 写退化为 no-op，add/remove 通过 leftovers 表达失败. 与真实容器相关的
     * 能力(观看者, 持有者, 位置)按"无"回答, 类型恒为 CHEST.
     */
    @NotNull
    @ApiStatus.Experimental
    org.bukkit.inventory.Inventory asBukkitInventory();

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
