package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.List;

/**
 * Window 引擎与 Paper/NMS 菜单实现之间的窄边界.
 */
@ApiStatus.Internal
public interface MenuHandle extends AutoCloseable {

    /**
     * 在一次同步中的 真实光标物品 和 可视光标物品 的快照.
     *
     * @param actual 菜单真实持有的光标快照
     * @param visual 仅发送给客户端的可视光标快照
     */
    record CursorSnapshot(@NotNull ItemStack actual, @NotNull ItemStack visual) {
    }

    /**
     * 在打开菜单前准备真实光标转移.
     *
     * <p>替换 Window 时从旧代理菜单转移；否则先完成当前原版菜单的关闭生命周期，
     * 再从玩家库存菜单转移。实现应在新代理接管活动菜单时才清空来源；打开失败时
     * {@link #close(InventoryCloseEvent.Reason)} 必须把已经取得的光标归还来源菜单。</p>
     *
     * @param replacingWindow 是否正在替换同一玩家的 Window
     */
    void prepareOpen(boolean replacingWindow);

    /**
     * 打开菜单并发送初始完整状态.
     *
     * <p>槽位数组只在调用期间有效, 实现不得修改或保留数组引用. 每个物品已经是 Window 独占的
     * 稳定快照, 实现可以在同步比较期间直接读取或解包；任何需要跨越本次调用继续存活的状态
     * （包括异步编码的数据包）都必须在返回前取得独立快照.</p>
     *
     * @param title 初始标题
     * @param slots 按客户端协议 raw slot 排列的物理槽位权威物品
     * @param cursor 同步使用的真实光标与可视投影
     */
    void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 将远端容器镜像同步到当前服务端权威状态.
     *
     * <p>实现检查 dirty 槽位和此前收到的客户端预测. {@code forceFull} 为真时忽略增量候选并
     * 发送完整状态. 数组和位图只在调用期间有效, 实现不得修改或保留其引用. 槽位与光标物品
     * 已经是 Window 独占的稳定快照, 可以直接用于同步比较；异步数据包必须持有自己的快照.</p>
     *
     * @param slots 按客户端协议 raw slot 排列的物理槽位权威物品
     * @param dirtySlots 本轮可能变化的槽位
     * @param cursor 同步使用的真实光标与可视投影
     * @param cursorDirty 是否需要核对光标
     * @param forceFull 是否强制发送完整状态
     */
    void synchronize(
            ItemStack @NotNull [] slots,
            @NotNull BitSet dirtySlots,
            @NotNull CursorSnapshot cursor,
            boolean cursorDirty,
            boolean forceFull
    );

    /**
     * 用重新打开界面和完整状态更新客户端标题.
     *
     * <p>参数所有权与 {@link #open(Component, ItemStack[], CursorSnapshot)} 相同.</p>
     *
     * @param title 新标题
     * @param slots 按客户端协议 raw slot 排列的物理槽位权威物品
     * @param cursor 同步使用的真实光标与可视投影
     */
    void updateTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 向客户端发送用于 Window 状态确认的协议 Ping.
     *
     * @param id Ping 标识
     */
    void sendPing(int id);

    /**
     * 清理菜单资源.
     *
     * @param reason 关闭原因
     */
    void close(@NotNull InventoryCloseEvent.Reason reason);

    /**
     * 以插件主动关闭方式释放菜单.
     */
    @Override
    default void close() {
        this.close(InventoryCloseEvent.Reason.PLUGIN);
    }

    /**
     * 玩家实体调度器已 retired 时只释放不需要访问玩家状态的资源.
     */
    void retire();

    /**
     * 校验交互所属会话和 state id, 并吸收其中非权威的客户端预测.
     *
     * @param interaction 待校验的交互
     * @return 交互属于当前协议状态时返回 {@code true}
     */
    boolean accepts(@NotNull MenuInput.Common.Interaction interaction);

    /**
     * 按接收顺序处理至多指定数量的当前会话输入.
     *
     * @param limit 本次最多移除的输入数量
     * @return 不可变的领域输入列表
     */
    @NotNull
    List<MenuInput> drainInputs(int limit);

    /**
     * 返回入站消息缓冲区是否已经溢出.
     *
     * <p>缓冲、代际筛选和 Netty 线程交接均由菜单 Adapter 管理, Window 只消费当前会话的领域输入.</p>
     *
     * @return 入站消息是否曾超过 Adapter 容量
     */
    boolean hasInputOverflowed();

    /**
     * 此会话的 Minecraft 容器编号.
     */
    int containerId();

    /**
     * 返回供 Bukkit 事件读取的协议视图.
     * <p>视图的 {@link InventoryView#getItem(int)} 和 {@link InventoryView#getCursor()} 必须返回可由
     * 事件调用方独立修改的快照, 不得暴露 Window 或菜单持有的权威物品.
     */
    @NotNull
    InventoryView view();

    /**
     * 客户端当前应回传的容器 state id.
     *
     * @return 当前协议状态编号
     */
    int stateId();

    /**
     * 返回 Paper 玩家物品栏的变更版本.
     * <p>Window 使用此版本门控底部物品栏扫描. Adapter 无法提供精确版本时可以返回一个
     * 持续变化的值, 以退化为每 tick 扫描.
     *
     * @return 当前玩家物品栏版本
     */
    int playerInventoryVersion();

    /**
     * 返回菜单真实持有的光标快照.
     *
     * @return 可由调用方独立修改的真实光标快照
     */
    @NotNull
    ItemStack cursor();

    /**
     * 权威覆盖菜单真实持有的光标.
     * <p>实现取得传入物品的独立快照, 不保留参数引用. 调用方负责随后的光标同步
     * (脏标记与 synchronize).
     *
     * @param cursor 新的真实光标, 空物品表示清空
     */
    void cursor(@NotNull ItemStack cursor);
}
