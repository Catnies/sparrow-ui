package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.inventory.event.InventoryPostUpdateEvent;
import net.momirealms.sparrow.ui.inventory.event.InventoryPreUpdateEvent;
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
 * 一组受事务保护的物品槽位, 可以把它当成一个"会自动通知变更的箱子"来理解.
 * <p>无论底层数据放在哪里, 所有实现都遵守三条硬约定:
 * <ul>
 *   <li>空槽只用 {@code null} 表示, Inventory里永远不会出现 AIR 物品或数量不大于 0 的物品;</li>
 *   <li>读出来的物品都是克隆, 随意改动拿到的副本也不会影响Inventory;</li>
 *   <li>每一次修改都走完整的事务流程(规划, 询问, 提交, 通知), 事件以"一整次修改"
 *       为单位派发, 而不是一个槽位一条.</li>
 * </ul>
 * 读操作没有锁, 直接读取当前那份不可变快照, 任何线程都可以安全调用.
 * 写操作遇到并发冲突时统一返回 {@link TransactionResult.Conflicted}, 并且不产生任何修改;
 * ReferencingInventory在当前线程访问不了目标容器时返回 {@link TransactionResult.Unavailable}.
 */
public interface Inventory {
    int DEFAULT_MAX_STACK_SIZE = 99; // 槽位默认的堆叠上限

    /**
     * 返回槽位数量, 创建后固定不变.
     *
     * @return 槽位数量
     */
    int size();

    /**
     * 读取指定槽位的物品, 空槽返回 {@code null}.
     * 返回的是克隆, 改动它不会影响Inventory.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 槽内物品的克隆, 空槽为 {@code null}
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @Nullable
    ItemStack itemAt(int slot);

    /**
     * 一次性读出全部槽位, 得到这一刻的独立副本:
     * 之后Inventory再被修改, 或调用方改动返回的数组与物品, 都互不影响.
     *
     * @return 按槽号排列的物品克隆数组, 空槽位置为 {@code null}
     */
    @Nullable
    ItemStack @NotNull [] snapshot();

    /**
     * 返回指定槽位自身的堆叠上限, 不含物品自带的堆叠上限.
     * 放入物品时真正生效的上限是两者的较小值, 即 {@code min(槽位上限, 物品自身上限)}.
     *
     * @param slot 槽位序号, 从 0 开始
     * @return 该槽位的堆叠上限
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    int slotMaxStackSize(int slot);

    /**
     * 返回指定类别的批量操作按什么顺序遍历槽位.
     *
     * @param category 操作类别
     * @return 该类别使用的遍历顺序
     */
    @NotNull
    SlotOrder iterationOrder(@NotNull OperationCategory category);

    /**
     * 返回指定类别的操作挑选目标Inventory时使用的优先级, 数值越大越优先.
     *
     * @param category 操作类别
     * @return 该类别的优先级, 越大越优先
     */
    int guiPriority(@NotNull OperationCategory category);

    /**
     * 权威写入单个槽位: 覆盖为给定物品({@code null} 清空), 不受堆叠上限约束.
     * 权威写恒定产生事务与事件, 即使新值与当前值相等也不做无变更抑制.
     *
     * @param reason 本次修改的原因, 会随事件一起派发给观察者
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult setItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item);

    /**
     * 与 {@link #setItem} 相同, 但跳过全部 pre 观察者, 无法被取消;
     * 提交后的 post 事件照常派发, 适合程序化的强制写入(初始化, 重置, 管理命令).
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要覆盖进去的物品, {@code null} 表示清空
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult forceSetItem(@NotNull UpdateReason reason, int slot, @Nullable ItemStack item);

    /**
     * 往指定槽位放物品, 能放多少放多少: 槽位空就直接放入, 槽内物品相似就合并进去,
     * 两种情况都遵守有效堆叠上限. 物品不相似时一个也放不进, 全部数量计入 remaining.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    AddResult putItem(@NotNull UpdateReason reason, int slot, @NotNull ItemStack item);

    /**
     * 读-改-写指定槽位: modifier 收到当前物品的克隆(空槽为 {@code null}),
     * 它返回什么槽里就存什么(返回 {@code null} 表示清空).
     * 与 {@link #setItem} 同为权威写入: 返回值恒定提交, 即使与当前值相等.
     * modifier 在不持锁的状态下执行, 它抛出的异常会原样传给调用方, 此时 Inventory 零变更.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param modifier 接收旧物品克隆并返回新物品的函数
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult modifyItem(@NotNull UpdateReason reason, int slot, @NotNull UnaryOperator<@Nullable ItemStack> modifier);

    /**
     * 增减槽内物品的数量. 减少时只受下限 0 约束(减到 0 清空槽位), 即使是权威写入堆出的
     * 超上限物品堆, 减少也精确生效, 不会被压回上限; 增加时最多堆到有效上限, 已满或超满
     * 的槽加不进去, 属于无变更操作. 空槽没有物品可当模板, 增减同样无变更.
     *
     * @param reason 本次修改的原因
     * @param slot 槽位序号, 从 0 开始
     * @param change 数量变化, 正数为增加, 负数为减少
     * @return 事务结果
     * @throws IndexOutOfBoundsException 当槽号越界时
     */
    @NotNull
    TransactionResult changeAmount(@NotNull UpdateReason reason, int slot, int change);

    /**
     * 把物品尽量放进Inventory: 按 ADD 类别的遍历顺序, 先合并进相似的未满物品堆, 再占用空槽;
     * 放不下的数量计入 remaining. 整个放入过程算一次事务.
     *
     * @param reason 本次修改的原因
     * @param item 要放入的物品
     * @return 放入结果, 其中 remaining 是没能放入的数量
     */
    @NotNull
    AddResult add(@NotNull UpdateReason reason, @NotNull ItemStack item);

    /**
     * 收集Inventory里与 template 相似的物品, 至多 {@code upTo} 个: 按 COLLECT 类别的遍历
     * 顺序, 先从未满的物品堆收取(让满堆保持完整), 不够再收满堆. 整个收集算一次事务.
     * template 只用来判断"像不像", 它自己的数量没有影响; {@code upTo} 不大于 0 时什么都不做.
     *
     * @param reason 本次修改的原因
     * @param template 物品样板, 只参与相似判断
     * @param upTo 最多收集的数量
     * @return 收集结果, 包含实际收集到的数量
     */
    @NotNull
    CollectResult collect(@NotNull UpdateReason reason, @NotNull ItemStack template, int upTo);

    /**
     * 移除 matcher 选中的物品, 至多 {@code upTo} 个, 按 OTHER 类别的遍历顺序逐槽扣减.
     * matcher 收到的是槽内物品的克隆, 在不持锁的状态下执行; 它抛出的异常会原样传给调用方,
     * 此时 Inventory 零变更. 整个移除算一次事务; {@code upTo} 不大于 0 时什么都不做.
     *
     * @param reason 本次修改的原因
     * @param matcher 判断某个物品该不该移除的函数
     * @param upTo 最多移除的数量
     * @return 移除结果, 包含实际移除的数量
     */
    @NotNull
    RemoveResult remove(@NotNull UpdateReason reason, @NotNull Predicate<@NotNull ItemStack> matcher, int upTo);

    /**
     * 试算 {@link #add}: 假如现在放入这个物品, 会有多少数量放不下.
     *
     * @param item 要试算的物品
     * @return 预计放不下的数量
     */
    int simulateAdd(@NotNull ItemStack item);

    /**
     * 试算 {@link #collect}: 假如现在收集, 能收集到多少数量.
     *
     * @param template 物品样板, 只参与相似判断
     * @param upTo 最多收集的数量
     * @return 预计能收集到的数量
     */
    int simulateCollect(@NotNull ItemStack template, int upTo);

    /**
     * 判断Inventory能不能完整装下给定物品, 等价于 {@code simulateAdd(item) == 0}.
     *
     * @param item 要检查的物品
     * @return 能完整装下返回 {@code true}
     */
    boolean canHold(@NotNull ItemStack item);

    /**
     * 让ReferencingInventory与外部最新内容同步一次; 自己持有数据的Inventory调用它没有效果.
     * 集成层(比如 Window 的渲染循环)每个 tick 调一次: 外部容器当前访问不了时实现会静默跳过,
     * 允许访问时把外部发生的修改以 {@link UpdateReason.External} 原因送进事件流.
     */
    void refresh();

    /**
     * 把本Inventory包装成 Bukkit Inventory 接口, 方便接入只认 Bukkit API 的插件; 同一个 Inventory 永远
     * 返回同一个包装实例, Bukkit 侧可以用引用相等(==)辨认是不是同一个Inventory.
     * 通过包装实例写入会走 Sparrow 的事务流程(原因记为 {@link UpdateReason.Program});
     * 线程约定和被包装的Inventory一致: ReferencingInventory 访问不了目标时, 无返回值的写操作会被吞掉,
     * add/remove 则通过 leftovers 表达"没放进去". 与真实容器绑定的信息(观看者, 持有者, 位置)一律回答"无", 类型固定为 CHEST.
     *
     * @return Bukkit 视角的 Inventory 包装实例
     */
    @NotNull
    @ApiStatus.Experimental
    org.bukkit.inventory.Inventory asBukkitInventory();

    /**
     * 订阅事务提交前的事件, 处理器可以取消整个事务.
     * 事件在提交者的线程上派发, 派发时不持有任何锁.
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    Subscription subscribePreUpdate(@NotNull Observer<? super InventoryPreUpdateEvent> observer);

    /**
     * 订阅事务提交后的事件. 对同一个Inventory, 事件到达的顺序与事务提交的顺序一致;
     * 派发线程不固定, 默认在Inventory的所在的区域线程(Folia).
     *
     * @param observer 事件处理器
     * @return 订阅凭证, 关闭后不再接收事件
     */
    @NotNull
    Subscription subscribePostUpdate(@NotNull Observer<? super InventoryPostUpdateEvent> observer);
}
