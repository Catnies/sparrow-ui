package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 一名玩家从进入到离开一整段多 Window 交互的会话.
 * <p>会话内经 {@link #open} 打开的 Window 构成会话链, 链底是最早打开的, 链顶是当前显示的.
 * 链内跳转与返回不触发结束处理器, 只有整段交互结束时结束处理器才恰好触发一次.
 */
public interface WindowSession {

    /**
     * 在会话内打开 Window 并把它压为链顶, 旧链顶保持存活地退到链下, 供 {@link #back()} 返回.
     * <p>window 已在链中时不再新增层级, 而是把链截断回该 Window 并请求重新打开它.
     * <p>会话内的窗口一律经本方法或 {@link Window#openNext} 打开, 直接调用 {@link Window#open()}
     * 会被判为链外打开并结束会话.
     *
     * @param window 要打开的 Window
     * @return 导航请求的执行结果
     * @throws IllegalArgumentException window 的查看者与会话玩家不一致时
     */
    @NotNull
    CompletableFuture<NavigationResult> open(@NotNull Window window);

    /**
     * 返回上一层: 关闭链顶并重新打开它的来源 Window, 来源菜单内状态原样保留.
     * <p>链上没有来源 Window 时不做任何事并以 {@link NavigationResult#AT_ROOT} 完成.
     *
     * @return 导航请求的执行结果
     */
    @NotNull
    CompletableFuture<NavigationResult> back();

    /**
     * 结束会话: 关闭当前链顶, 以 {@link InventoryCloseEvent.Reason#PLUGIN} 触发结束处理器, 清空会话链.
     * 重复调用无事发生.
     *
     * @return 结束请求的执行结果
     */
    @NotNull
    CompletableFuture<EndResult> end();

    /**
     * 绑定到指定的 Signal, Signal 将会持有本会话的弱引用.
     * <p>绑定不补发当前值, 第一次回调发生在下一次标脏.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 可用于提前解绑
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super WindowSession> callback);

    /**
     * 替换会话结束时依次执行的处理器列表.
     *
     * @param endHandlers 新处理器列表
     */
    void setEndHandlers(@NotNull List<? extends Consumer<? super InventoryCloseEvent.Reason>> endHandlers);

    /**
     * 当前结束处理器列表的快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<InventoryCloseEvent.Reason>> getEndHandlers();

    /**
     * 在现有结束处理器末尾追加一个处理器, 它在整段交互结束时恰好触发一次, 链内跳转与返回绝不触发.
     * <p>参数为结束原因, 与 Bukkit 关闭原因同一套枚举: PLAYER 表示玩家主动离开, DISCONNECT 表示断线,
     * PLUGIN 表示插件结束(含 {@link #end()}), OPEN_NEW 表示链外 Window 顶替.
     *
     * @param endHandler 结束处理器
     */
    void addEndHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> endHandler);

    /**
     * 移除一个与给定对象相等的结束处理器.
     *
     * @param endHandler 要移除的结束处理器
     */
    void removeEndHandler(@NotNull Consumer<? super InventoryCloseEvent.Reason> endHandler);

    /**
     * 此会话的所属玩家.
     *
     * @return 所属玩家
     */
    @NotNull
    Player viewer();

    /**
     * 当前链顶 Window.
     *
     * @return 链顶 Window, 尚未打开过任何窗口或会话已结束时为 null
     */
    @Nullable
    Window current();

    /**
     * 会话链快照, 从链底到链顶.
     *
     * @return 不可变的 Window 列表
     */
    @Unmodifiable
    @NotNull List<Window> chain();

    /**
     * 会话是否尚未结束.
     *
     * @return 尚未结束时返回 true
     */
    boolean active();

    /**
     * 导航请求的执行结果.
     */
    enum NavigationResult {
        OPENED,             // 目标 Window 的打开流程已在服务端执行.
        AT_ROOT,            // back 时链上没有来源 Window, 未做任何事.
        SESSION_ENDED,      // 会话已结束, 未做任何事.
        VIEWER_UNAVAILABLE  // 玩家不可用.
    }

    /**
     * 结束请求的执行结果.
     */
    enum EndResult {
        ENDED,
        ALREADY_ENDED
    }

    /**
     * 会话的类型, 由根窗 Builder 的 {@link Window.Builder#setSessionKind} 声明, 决定链的结构与离场处置.
     */
    enum Kind {
        /**
         * 线性栈, 默认型. {@link Window#openNext} 不查重, 同一 Window 实例可以压入多次;
         * {@link Window#back()} 弹出栈顶, 栈中不再出现该实例时会话丢弃对它的引用.
         * 适合每次进入都应当是全新状态的菜单.
         */
        STACK,
        /**
         * 保留栈. 结构与 {@link #STACK} 完全一致, 唯一差异是被弹出的 Window 不丢引用,
         * 而是保留到会话结束才随会话一起释放.
         */
        RETAINED_STACK,
        /**
         * 树. {@link Window#openNext} 查重: 目标已在树中时当前位置直接移过去并以原实例重新打开,
         * 不在时成为当前节点的新孩子; {@link Window#back()} 回到父节点且不丢弃任何成员.
         * 步入过的 Window 全部保留到会话结束, 适合重新加载昂贵的菜单.
         */
        TREE
    }

    /**
     * 可重复使用的会话 Builder.
     */
}
