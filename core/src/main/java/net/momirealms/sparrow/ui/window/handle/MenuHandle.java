package net.momirealms.sparrow.ui.window.handle;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.window.WindowCloseReason;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.List;

/**
 * 一扇 Window 底下的协议菜单和客户端已知状态.
 * <p>服务端渲染结果从这个边界出去: 开菜单, 纠正客户端预测, 以及给 Bukkit 事件提供视图.
 */
@ApiStatus.Internal
public interface MenuHandle extends AutoCloseable {

    /**
     * 本轮同步要用的两个光标: 菜单实际持有的那个, 和客户端看到的那份副本.
     *
     * @param actual 菜单实际光标的一份副本
     * @param visual 只发给客户端看的光标副本
     */
    record CursorSnapshot(@NotNull ItemStack actual, @NotNull ItemStack visual) {
    }

    /**
     * 准备把玩家当前菜单的光标接管过来.
     * <p>替换 Window 的时候从旧代理菜单接管; 别的情况先把当前原版菜单关掉.
     * <p><strong>打开失败或者提前关闭时, 必须把已经接管的光标还给来源菜单.</strong>
     *
     * @param replacingWindow 是否正在替换同一玩家的 Window
     */
    void prepareOpen(boolean replacingWindow);

    /**
     * 打开菜单, 并把完整的初始状态发出去.
     * <p><strong>slots 和里面的物品只在这次调用期间有效, 实现不许改也不许留着.</strong>
     * 数据包要异步发的话, 得先把里面的物品复制一份.
     *
     * @param title 初始标题
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     */
    void open(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 把本轮的服务端渲染结果和菜单状态同步给客户端.
     * <p>增量同步要看 dirty 槽位和客户端预测; {@code forceFull} 为真就发完整状态.
     * <p><strong>slots, dirtySlots 和里面的物品只在这次调用期间有效, 实现不许改也不许留着.</strong>
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
     * 换标题: 重新打开界面, 顺带把完整状态再发一遍.
     *
     * @param title 新标题
     * @param slots 按协议槽位(raw slot)排列的服务端槽位渲染结果
     * @param cursor 本次同步要用的菜单实际光标与客户端显示光标
     */
    void reopenWithTitle(@NotNull Component title, ItemStack @NotNull [] slots, @NotNull CursorSnapshot cursor);

    /**
     * 发一个协议 Ping, 用来确认客户端处理过了某项 Window 状态.
     *
     * @param id Ping 的标识
     */
    void sendPing(int id);

    /**
     * 按给定原因关掉菜单, 把会话资源放掉.
     *
     * @param reason 关闭原因
     */
    void close(@NotNull WindowCloseReason reason);

    // 默认按"插件主动关闭"来关
    @Override
    default void close() {
        this.close(WindowCloseReason.PLUGIN);
    }

    /**
     * 玩家实体调度器注销之后, 放掉那些不依赖玩家状态的资源.
     * <p><strong>实现不许再读或者改玩家状态.</strong>
     */
    void retire();

    /**
     * 看这条交互是不是属于当前会话, 顺便把里面的客户端预测收下.
     *
     * @param interaction 待检查的交互
     * @return 属于当前容器时为 {@code true}
     */
    boolean accepts(@NotNull MenuInput.Common.Interaction interaction);

    /**
     * 按收到的顺序从缓冲区里取最多 limit 条入站消息.
     *
     * @param limit 本次最多取出的输入数量
     * @return 取出来的输入, 不可变列表
     */
    @NotNull
    List<MenuInput> drainInputs(int limit);

    /**
     * 入站缓冲区溢出过没有; 溢出过 Window 就把这条没法再安全同步的会话关掉.
     *
     * @return 超过过容量阈值时为 {@code true}
     */
    boolean hasInputOverflowed();

    /**
     * 给 Bukkit 事件用的 InventoryView, 跟服务端渲染结果隔着.
     * <p>{@link InventoryView#getItem(int)} 和 {@link InventoryView#getCursor()} 给的都是独立副本.
     *
     * @return InventoryView
     */
    @NotNull
    InventoryView view();

    /**
     * 拿当前服务端状态, 把下一次 Bukkit 事件要读的副本重置一遍.
     * <p>本 tick 已经攒下的触碰标记留着, 最后同步时还要靠它纠正客户端.
     *
     * @param slots 按协议槽位排列的当前服务端渲染结果
     * @param renderedSlots 本次事件前刚重新渲染的槽位
     * @param cursor 当前菜单实际光标
     */
    void resetBukkitEventView(ItemStack @NotNull [] slots, @NotNull BitSet renderedSlots, @NotNull ItemStack cursor);

    /**
     * 取走最近一次 Bukkit 事件写过的光标, 同时把这条事件记录清掉.
     * <p>必须紧跟在事件返回之后调; 下一次 {@link #resetBukkitEventView} 会把事件副本盖掉.
     *
     * @return 最近一次事件写过光标就给那个值; 没写过给 {@code null}
     */
    @Nullable
    ItemStack takeBukkitEventCursor();

    /**
     * 把最近一次 Bukkit 事件写过的槽位搬进目标位图, 同时清掉这条事件记录.
     * <p>写入的内容仍旧从 {@link #view()} 读; 本 tick 累积的触碰位图不动.
     *
     * @param destination 接收本次事件写入槽位的可变位图
     */
    void drainBukkitEventSlots(@NotNull BitSet destination);

    int containerId();

    int stateId();

    /**
     * 菜单实际光标的一份副本, 调用方随便改.
     *
     * @return 菜单实际光标的副本
     */
    @NotNull
    ItemStack cursor();

    /**
     * 菜单实际光标的 NMS 句柄.
     * <p><strong>这个句柄可能直接借用了菜单底层的状态, 调用方不得修改.</strong>
     *
     * @return 菜单实际光标的 NMS 句柄
     */
    @NotNull
    default Object unsafeCursor() {
        return ItemUtils.getItemStackHandle(this.cursor());
    }

    /**
     * 拿这份副本覆盖菜单实际光标.
     * <p><strong>标脏并调 synchronize 把客户端同步掉, 是调用方的事.</strong>
     *
     * @param cursor 新的菜单实际光标, 空物品表示清空
     */
    void cursor(@NotNull ItemStack cursor);
}
