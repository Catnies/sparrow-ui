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
 * Window 底层菜单的处理器, 每个打开中的 Window 对应一个实现实例.
 * <p>实现负责把客户端看到的远端镜像维护成服务端的权威状态.
 */
@ApiStatus.Internal
public interface MenuHandle extends AutoCloseable {

    /**
     * 光标物品快照
     *
     * @param actual 服务端菜单真实持有的光标物品快照
     * @param visual 客户端侧显示的光标物品快照
     */
    record CursorSnapshot(@NotNull ItemStack actual, @NotNull ItemStack visual) {
    }

    /**
     * 在打开菜单之前, 把玩家光标上真实拿着的物品接管过来.
     *
     * <p>如果是在替换同一个玩家的 Window, 就从旧菜单手里接; 否则先让当前打开的原版菜单
     * 走完正常的关闭流程, 再从玩家背包菜单接. 在新菜单真正接管之前, 实现不能清空来源菜单;
     * 如果打开失败, {@link #close(InventoryCloseEvent.Reason)} 要负责把已经拿走的光标物品还回去.
     *
     * @param replacingWindow 是否正在替换同一玩家的 Window
     */
    void prepareOpen(boolean replacingWindow);

    /**
     * 打开菜单, 把初始的完整界面状态发给客户端.
     *
     * <p>传进来的槽位数组只在这次调用期间有效, 实现不可改也不可持有.
     * <p>数组里每个物品都是 Window 独占的稳定快照, 异步发出的数据包要拷一份.
     *
     * @param title 初始标题
     * @param slots 按客户端协议 raw slot 排列的物理槽位权威物品
     * @param cursor 本次同步要用的真实光标与可视光标
     */
    void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 将服务端的权威数据和状态同步给客户端.
     *
     * <p>实现只检查 dirty 标记的槽位和之前收到的客户端预测;
     * 若 {@code forceFull} 为 true 时直接发一份完整状态.
     * <p>数组和位图只在本次调用期间有效, 实现不可改也不可持有.
     * 槽位和光标物品都是 Window 独占的稳定快照, 异步发出的数据包要拷一份.
     *
     * @param slots 按客户端协议 raw slot 排列的物理槽位权威物品
     * @param dirtySlots 这一轮可能变过的槽位
     * @param cursor 本次同步要用的真实光标与可视光标
     * @param cursorDirty 这一轮是否需要核对光标
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
     * 更新玩家所打开的 Window 的标题.
     * 实现方式是让客户端重新打开一次界面, 并附上完整状态.
     *
     * @param title 新标题
     * @param slots 按客户端协议 raw slot 排列的物理槽位权威物品
     * @param cursor 本次同步要用的真实光标与可视光标
     */
    void reopenWithTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * todo: 检查是否必须, 能否继续收窄.
     * 给客户端发一个协议 Ping, 用来确认 Window 的某个状态已经被客户端收到并处理.
     *
     * @param id Ping 标识
     */
    void sendPing(int id);

    /**
     * 关闭菜单并释放相关资源.
     *
     * @param reason 关闭原因
     */
    void close(@NotNull InventoryCloseEvent.Reason reason);

    /**
     * 以"插件主动关闭"的原因关闭菜单.
     */
    @Override
    default void close() {
        this.close(InventoryCloseEvent.Reason.PLUGIN);
    }

    /**
     * 玩家的实体调度器已经注销时调用.
     * 这时不能再碰玩家状态, 只释放那些不依赖玩家的资源.
     */
    void retire();

    /**
     * 检查这个交互是不是发给当前会话、当前 state id 的,
     * 顺便把数据包内的客户端预测数据收下来.
     *
     * @param interaction 待检查的交互
     * @return 交互属于当前协议状态时返回 true
     */
    boolean accepts(@NotNull MenuInput.Common.Interaction interaction);

    /**
     * 按收到的先后顺序, 从缓冲区取出最多 limit 条入站消息.
     *
     * @param limit 本次最多取出的输入数量
     * @return 取出的不可变输入列表
     */
    @NotNull
    List<MenuInput> drainInputs(int limit);

    /**
     * 返回入站消息缓冲区有没有溢出, 防止恶意大量数据包攻击,
     * 入站消息在超过一定数量阈值时会主动关闭 Window.
     *
     * @return 入站消息曾经超过容量阈值时返回 true
     */
    boolean hasInputOverflowed();

    /**
     * 返回给 Bukkit 事件用的 InventoryView.
     *
     * <p>事件处理方可能会改从 InventoryView 上读到的物品, 所以 {@link InventoryView#getItem(int)} 和
     * {@link InventoryView#getCursor()} 返回的是独立快照, 不是 Window 持有的权威物品.
     *
     * @return InventoryView
     */
    @NotNull
    InventoryView view();

    /**
     * 返回这一会话的 Minecraft 容器编号.
     *
     * @return 容器编号
     */
    int containerId();

    /**
     * 返回客户端现在应该回传的容器 state id.
     *
     * @return 当前协议状态编号
     */
    int stateId();

    /**
     * 返回菜单真正持有的光标物品快照.
     *
     * @return 调用方可以随意修改的真实光标快照
     */
    @NotNull
    ItemStack cursor();

    /**
     * 用权威数据整体覆盖菜单真正持有的光标物品.
     *
     * <p>实现会拷一份传进来的物品, 不会存参数本身. 覆盖之后的光标同步(打脏标记、
     * 调 synchronize)由调用方负责.
     *
     * @param cursor 新的真实光标, 空物品表示清空
     */
    void cursor(@NotNull ItemStack cursor);
}
