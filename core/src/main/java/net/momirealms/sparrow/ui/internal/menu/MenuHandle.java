package net.momirealms.sparrow.ui.internal.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

/**
 * Window 底层菜单的处理器, 每个打开中的 Window 对应一个实现实例.
 * <p>实现负责维护客户端已知状态, 并用本轮服务端渲染结果纠正客户端显示.
 */
@ApiStatus.Internal
public interface MenuHandle extends AutoCloseable {

    /**
     * 本轮同步使用的菜单实际光标和客户端显示光标.
     *
     * @param actual 服务端菜单实际持有的光标物品副本
     * @param visual 只供客户端显示的光标物品副本
     */
    record CursorSnapshot(@NotNull ItemStack actual, @NotNull ItemStack visual) {
    }

    /**
     * 在打开菜单之前, 接管玩家当前菜单实际持有的光标物品.
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
     * <p>数组里每个物品都是 Window 独占的稳定副本, 异步发出的数据包还要再复制一份.
     *
     * @param title 初始标题
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     */
    void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 将本轮服务端渲染结果和菜单状态同步给客户端.
     *
     * <p>实现只检查 dirty 标记的槽位和之前收到的客户端预测;
     * 若 {@code forceFull} 为 true 时直接发一份完整状态.
     * <p>数组和位图只在本次调用期间有效, 实现不可改也不可持有.
     * 槽位和光标物品都是 Window 独占的稳定副本, 异步发出的数据包还要再复制一份.
     *
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param dirtySlots 这一轮可能变过的槽位
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
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
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     */
    void reopenWithTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
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
     * 检查这个交互是不是发给当前会话, 当前 state id 的,
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
     * {@link InventoryView#getCursor()} 返回的是独立副本, 不是 Window 持有的服务端渲染结果.
     *
     * @return InventoryView
     */
    @NotNull
    InventoryView view();

    /**
     * 用当前服务端渲染结果和菜单实际光标重置下一次 Bukkit 事件读取的 Bukkit 事件状态副本.
     * Bukkit 监听器此前触碰过的标记仍需保留, 供本 tick 最终同步纠正客户端.
     *
     * @param slots 按协议槽位排列的当前服务端渲染结果
     * @param renderedSlots 本次事件前刚重新渲染的槽位
     * @param cursor 当前菜单实际光标
     */
    void resetBukkitEventView(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor);

    /**
     * 取出最近一次 Bukkit 事件写进 Bukkit 事件状态副本的光标, 并清空该事件的写入记录.
     * <p>这份副本的内容会被下一次 {@link #resetBukkitEventView} 覆盖, 所以事件一返回就要取走.
     * 本 tick 最终同步用的累积触碰标记不受影响.
     *
     * @return 最近一次事件写过光标时返回写入值, 没写过时返回 {@code null}
     */
    @Nullable
    ItemStack takeBukkitEventCursor();

    /**
     * 把最近一次 Bukkit 事件写进 Bukkit 事件状态副本的槽位转移到调用方的位图, 并清空该事件的写入记录.
     * <p>写入的内容仍然从 {@link #view()} 上读, 这里只负责告诉调用方哪些槽位被写过.
     * 本 tick 最终同步用的累积位图不受影响.
     *
     * @param destination 接收本次事件写入槽位的可变位图
     */
    void drainBukkitEventSlots(@NotNull BitSet destination);

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
     * 返回菜单实际持有的光标物品副本.
     *
     * @return 调用方可以随意修改的菜单实际光标副本
     */
    @NotNull
    ItemStack cursor();

    /**
     * 整体覆盖菜单实际持有的光标物品.
     * <p>实现会拷一份传进来的物品, 不会存参数本身. 覆盖之后的光标同步(打脏标记,
     * 调 synchronize)由调用方负责.
     *
     * @param cursor 新的菜单实际光标, 空物品表示清空
     */
    void cursor(@NotNull ItemStack cursor);
}
