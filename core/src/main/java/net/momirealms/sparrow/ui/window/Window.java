package net.momirealms.sparrow.ui.window;

import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.Subscription;
import net.momirealms.sparrow.ui.visual.CursorVisual;
import net.momirealms.sparrow.ui.visual.VisualLayer;
import net.momirealms.sparrow.ui.visual.WindowVisual;
import net.momirealms.sparrow.ui.visual.animation.AnimationHandle;
import net.momirealms.sparrow.ui.visual.animation.TitleAnimationDefinition;
import net.momirealms.sparrow.ui.window.click.WindowOutsideClick;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.item.provider.ImmediateItemProvider;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.state.Signal;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一名玩家正在看的 Pane 窗口.
 * <p>会碰菜单或协议状态的方法都按玩家串行送到实体线程上执行; 其余方法各自的线程说明写在方法上,
 * 查询方法会写清返回的是配置值还是最近一次已应用的快照.
 * <p>实现由库内的 Window 层级提供, <strong>外部代码不应自行实现此接口</strong>.
 */
public interface Window {

    /**
     * 建一个普通窗口的 Builder: {@code pane} 当上半部分, 下半部分映射玩家原生物品栏.
     *
     * @param pane 上半部分 Pane
     * @return 可重复使用的 Builder
     */
    static @NotNull NormalWindow.Builder builder(@NotNull Pane pane) {
        return NormalWindow.builder().setUpperPane(pane);
    }

    /**
     * 建一个上下分离窗口的 Builder: 两个 Pane 分别管容器和玩家物品栏那一片.
     *
     * @param upperPane 上半部分 Pane
     * @param lowerPane 下半部分 9x4 Pane
     * @return 可重复使用的 Builder
     */
    static @NotNull NormalWindow.Builder splitBuilder(@NotNull Pane upperPane, @NotNull Pane lowerPane) {
        return NormalWindow.builder().setUpperPane(upperPane).setLowerPane(lowerPane);
    }

    /**
     * 建一个合并窗口的 Builder: 一个 Pane 同时盖住容器和玩家物品栏.
     *
     * @param pane 合并后的 Pane
     * @return 可重复使用的 Builder
     */
    static @NotNull NormalWindow.Builder mergedBuilder(@NotNull Pane pane) {
        return NormalWindow.mergedBuilder(pane);
    }

    /**
     * 请求打开这扇窗. Future 完成只说明服务端把打开流程走完了, 不代表客户端已经把它显示出来.
     *
     * @return 打开请求的执行结果
     */
    @NotNull CompletableFuture<OpenResult> open();

    /**
     * 从这扇窗打开下一扇, 本 Window 成为它的上一扇, 等着被返回.
     * <p>本 Window 已经在某个会话里时, 新窗口加入那个会话; 不在时两者凑成一段新会话.
     * 所以不论本 Window 原来属不属于会话, 新窗口都能用 {@link #back()} 回到这里.
     *
     * @param next 要打开的下一扇 Window, 必须与本 Window 属于同一名玩家
     * @return 打开后的 next, 玩家不可用或所在会话已结束等打不开的情况以 null 完成
     */
    @NotNull CompletableFuture<Window> navigate(@NotNull Window next);

    /**
     * 拿本 Window 的查看者把下一扇建出来再打开, 其余语义同 {@link #navigate(Window)}.
     * <p>Builder 是在调用线程上同步建的, 所以这里同样受 {@link Builder#build(Player)} 的线程约束.
     * 想异步建的话先把 CompletionStage 拿到手, 再走 {@link #navigate(CompletionStage)}.
     *
     * @param next 下一扇 Window 的 Builder
     * @return 打开后的 Window, 打不开时以 null 完成
     */
    @NotNull
    default CompletableFuture<Window> navigate(@NotNull Builder<?, ?> next) {
        return this.navigate(next.build(this.viewer()));
    }

    /**
     * 等一扇还在构建中的 Window 建完, 再从本 Window 打开它, 其余语义同 {@link #navigate(Window)}.
     * <p>在哪儿构建由调用方定; 建完之后仍旧由玩家实体线程来跑打开流程.
     * <p>发起这次调用时会把本 Window 当时的位置记下来. 构建结果回来的时候本 Window 已经关了, 被顶替了,
     * 或者已经不是所在会话的当前窗, 这次导航就作罢, 以 null 完成, 原会话和玩家正看着的菜单都不动.
     *
     * @param next 构建中的下一扇 Window
     * @return 打开后的 Window, 打不开时以 null 完成
     */
    @NotNull
    default CompletableFuture<Window> navigate(@NotNull CompletionStage<? extends Window> next) {
        return next.thenCompose(this::navigate).toCompletableFuture();
    }

    /**
     * 退回上一扇, 上一扇拿原实例重新打开.
     * <p>只有本 Window 是某个会话的当前窗, 而且上面真有上一扇时才会返回.
     * 在根窗上或者不属于任何会话时什么都不做, 本 Window 继续开着.
     *
     * @return 返回后的新当前窗, 没有发生返回时以 null 完成
     */
    @NotNull CompletableFuture<Window> back();

    /**
     * 退回上一扇; 没有上一扇可退就把本 Window 关掉.
     * <p>有上一扇时等同 {@link #back()}; 在根窗上或者不属于任何会话时等同 {@link #close()},
     * 根窗一关, 所在会话照常结束. 返回/关闭按钮一般接这个.
     *
     * @return 返回后的新当前窗, 走了关闭路径时以 null 完成
     */
    @NotNull CompletableFuture<Window> backOrClose();

    /**
     * 本 Window 所属的会话.
     * <p>{@code build()} 完还没打开时是 null. 经 {@link #open()} 直接打开就成为新根窗并当场建会话,
     * 经 {@link #navigate} 打开的窗归属上一扇所在的会话. 离开会话之后(被弹出丢弃, 被会话外的 Window 顶替, 会话结束)又回到 null.
     *
     * @return 所属会话, 不属于任何会话时为 null
     */
    @Nullable
    WindowSession session();

    /**
     * 跟着这扇窗走的用户对象, 一般就是建它的那个菜单对象.
     * <p>库只保管这份引用, 不读里面的东西. 这份对象能活多久, 看会话类型让会话持有多久的 Window;
     * 调用方自己还拿着 Window 的话, 它也跟着在.
     *
     * @return 携带的对象, 未设置时为 null
     */
    @Nullable
    Object data();

    /**
     * 按类型读携带的用户对象.
     *
     * @param <T> 期望类型
     * @param type 期望类型
     * @return 携带的对象; 没设时为 null
     * @throws ClassCastException 带着的对象不是这个类型时
     */
    @Nullable
    default <T> T data(@NotNull Class<T> type) {
        return type.cast(this.data());
    }

    /**
     * 请求关闭这扇窗.
     * <p>closeable 拦的只是玩家主动关, 插件发起的关闭不受它管.
     *
     * @return 关闭请求的执行结果
     */
    @NotNull CompletableFuture<CloseResult> close();

    /**
     * 换掉动态标题来源, 并请求刷新一次.
     * <p>Supplier 在玩家实体线程上读; 它返回 null 就显示空标题.
     *
     * @param titleSupplier 标题来源
     */
    void setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier);

    /**
     * 换成固定标题, 并请求刷新一次.
     *
     * @param title 新标题
     */
    void setTitle(@NotNull Component title);

    /**
     * 拿纯文本当固定标题.
     *
     * @param title 新标题
     */
    default void setTitle(@NotNull String title) {
        this.setTitle(Component.text(title));
    }

    /**
     * 请求把当前的标题 Supplier 重新读一遍.
     * <p>连着请求几次会在玩家实体 tick 里合并成一次.
     */
    void updateTitle();

    /**
     * 当前这个标题, 也就是最近一次已经应用上去的那一份.
     *
     * @return 当前标题
     */
    @NotNull Component title();

    /**
     * 播一次标题动画.
     * <p>播放期间标题帧盖住配置标题({@link #setTitle}/{@link #setTitleSupplier}), 帧返回 {@code null} 的地方放行,
     * 露出配置标题; 播完之后配置标题原样回来. 同一扇窗里播多次按开始顺序后来者优先,
     * 后开始的帧放行时逐层下落到更早开始的播放.
     * <p><strong>每一拍真正的标题变化都是一次同容器编号的菜单重开加全量内容重发</strong>,
     * 所以容器里现在摆的东西越复杂, 这个方法越贵.
     *
     * @param animationDefinition 标题动画描述
     * @return 这次播放的句柄
     * @throws IllegalArgumentException 动画周期不是正数时
     */
    @NotNull
    AnimationHandle playTitleAnimation(@NotNull TitleAnimationDefinition animationDefinition);

    /**
     * 设置玩家能不能自己关掉这扇窗.
     * <p>拦不住的还有插件, 断线, 以及 Bukkit 那边的外部关闭.
     *
     * @param closeable 是否可由客户端主动关闭
     */
    void setCloseable(boolean closeable);

    /**
     * 这个协议槽位(raw slot)有没有被本 Window 冻住.
     * <p>只反映 {@link #frozenAt(int, boolean)} 设的窗口侧状态, 路径上 Pane 自己的冻结不算在里面.
     *
     * @param windowSlot 协议槽位(raw slot)
     * @return 被本 Window 冻住时为 true
     * @throws IndexOutOfBoundsException 槽位超出 Window 范围时
     */
    boolean frozenAt(int windowSlot);

    /**
     * 冻住或者解冻一个协议槽位(raw slot).
     * <p>冻住之后这一格和显示路径经过冻结 Pane 是同等待遇: 不参与点击语义, 不派发事件, 也不分派 Item 点击,
     * 客户端预测会被纠正回来, 显示内容不受影响.
     * <p>它和 {@link Pane#setFrozen} 各算各的, 任一生效这一格就是冻的; 在这里解冻只撤掉窗口侧那一份.
     *
     * @param windowSlot 协议槽位(raw slot)
     * @param frozen true 表示冻结
     * @throws IndexOutOfBoundsException 槽位超出 Window 范围时
     */
    void frozenAt(int windowSlot, boolean frozen);

    /**
     * 本 Window 有没有冻住玩家的副手交互.
     *
     * <p>副手不在 Window 的协议槽位里, 所以 {@link #frozenAt(int)} 和 Pane 的路径都看不见它.
     * 这个状态只拦玩家经当前 Window 发起的副手交换, 插件或者别处的服务端逻辑直接改副手它管不着.
     *
     * @return 副手交互被冻住时为 true
     */
    boolean offhandFrozen();

    /**
     * 冻住或者解冻玩家的副手交互.
     * <p>冻住之后玩家在这扇窗里按副手交换键, 被点的槽位和副手都不会变,
     * Bukkit, Sparrow Inventory 和 Item 点击事件也都不会派发.
     *
     * @param frozen true 表示冻结副手交互
     */
    void offhandFrozen(boolean frozen);

    /**
     * 玩家主动关这扇窗时, 所在会话要不要退回上一扇.
     *
     * @return 玩家主动关闭时是否返回上一扇
     */
    boolean backOnPlayerClose();

    /**
     * 设置玩家主动关窗时要不要退回上一扇, 默认 false.
     * <p>只有本窗口正好是某个会话的当前窗时这条开关才有意义: 为 true 而且有上一扇就退回去,
     * 否则会话以 PLAYER 原因结束. 不在任何会话里的窗口不认这条开关, 玩家关就是关.
     * <p>它只管玩家主动关闭(reason == PLAYER), 程序化导航不受影响.
     *
     * @param backOnPlayerClose true 表示返回上一扇
     */
    void backOnPlayerClose(boolean backOnPlayerClose);

    /**
     * 让 Signal 每次被标脏都回调一次.
     * <p>不会补一趟当前值, 第一次回调要等下一次标脏.
     * <p><strong>绑定跟着打开期走</strong>: 第一次打开时才把订阅挂上, 关掉时摘掉, 重新打开再按声明挂回去.
     *
     * @param signal 数据源
     * @param callback 失效回调
     * @return 订阅凭证, 重新打开后仍有效, 可用于提前解绑
     */
    @NotNull
    Subscription bind(@NotNull Signal<?> signal, @NotNull Consumer<? super Window> callback);

    /**
     * 整批换掉打开时要跑的处理器.
     *
     * @param openHandlers 新处理器列表
     */
    void setOpenHandlers(@NotNull List<? extends Runnable> openHandlers);

    /**
     * 现在的打开处理器, 给一份快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Runnable> getOpenHandlers();

    /**
     * 在打开处理器末尾追加一个.
     *
     * @param openHandler 打开处理器
     */
    void addOpenHandler(@NotNull Runnable openHandler);

    /**
     * 按 equals 摘掉一个打开处理器.
     *
     * @param openHandler 要移除的打开处理器
     */
    void removeOpenHandler(@NotNull Runnable openHandler);

    /**
     * 整批换掉关闭之后要跑的处理器.
     *
     * @param closeHandlers 新处理器列表
     */
    void setCloseHandlers(@NotNull List<? extends Consumer<? super WindowCloseReason>> closeHandlers);

    /**
     * 现在的关闭处理器, 给一份快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<WindowCloseReason>> getCloseHandlers();

    /**
     * 在关闭处理器末尾追加一个, 它拿到的参数是关闭原因.
     *
     * @param closeHandler 关闭处理器, 参数为关闭原因
     */
    void addCloseHandler(@NotNull Consumer<? super WindowCloseReason> closeHandler);

    /**
     * 按 equals 摘掉一个关闭处理器.
     *
     * @param closeHandler 要移除的关闭处理器
     */
    void removeCloseHandler(@NotNull Consumer<? super WindowCloseReason> closeHandler);

    /**
     * 整批换掉容器外点击的处理器.
     * <p>处理器可以取消 {@link WindowOutsideClick} 来拦下这次点击.
     *
     * @param outsideClickHandlers 新处理器列表
     */
    void setOutsideClickHandlers(@NotNull List<? extends Consumer<? super WindowOutsideClick>> outsideClickHandlers);

    /**
     * 现在的容器外点击处理器, 给一份快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<WindowOutsideClick>> getOutsideClickHandlers();

    /**
     * 在容器外点击处理器末尾追加一个.
     *
     * @param outsideClickHandler 容器外点击处理器
     */
    void addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler);

    /**
     * 按 equals 摘掉一个容器外点击处理器.
     *
     * @param outsideClickHandler 要移除的容器外点击处理器
     */
    void removeOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler);

    /**
     * 设置服务端这边的窗口状态, 已经开着的话顺带发个 Ping 等客户端确认.
     *
     * @param windowState 新服务器窗口状态
     */
    void setWindowState(int windowState);

    /**
     * 把服务端的窗口状态加一, 同样在已打开时等客户端确认.
     */
    void incrementWindowState();

    /**
     * 最近一次设的服务端窗口状态.
     *
     * @return 服务器窗口状态
     */
    int serverWindowState();

    /**
     * 最近一次收到 Pong 确认的客户端窗口状态.
     *
     * @return 客户端已确认窗口状态
     */
    int clientWindowState();

    /**
     * 整批换掉客户端 Pong 确认窗口状态时要跑的处理器.
     *
     * @param handlers 新处理器列表
     */
    void setWindowStateChangeHandlers(@NotNull List<? extends Consumer<? super Integer>> handlers);

    /**
     * 现在的状态确认处理器, 给一份快照.
     *
     * @return 不可变的处理器列表
     */
    @Unmodifiable
    @NotNull List<Consumer<Integer>> getWindowStateChangeHandlers();

    /**
     * 在状态确认处理器末尾追加一个.
     *
     * @param handler 状态确认处理器
     */
    void addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler);

    /**
     * 按 equals 摘掉一个状态确认处理器.
     *
     * @param handler 要移除的状态确认处理器
     */
    void removeWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler);

    /**
     * 本 Window 的两层槽位视觉配置.
     * <p>同一扇窗始终给同一个对象. 这份配置只影响本 Window 的查看者, 而且盖在显示路径最外层,
     * 先于沿途 Pane 和路径终点求值.
     *
     * @return 槽位视觉配置
     */
    @NotNull
    WindowVisual visual();

    /**
     * 现在这一层的全局视觉映射.
     *
     * @return 全局视觉映射; 没设过时是 null, 表示按路径终点显示
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider() {
        return this.visual().visualizerProvider();
    }

    /**
     * 换掉 Window 的全局视觉映射, 它盖在本 Window 每条显示路径的最外面.
     * <p>命中时沿途 Pane 和路径终点都不再参与显示. 输入是路径终点的同步可读内容, 约定见 {@link WindowVisual};
     * 映射返回 {@code null} 就是放行, 交给下一层.
     * <p>映射改的只是本 Window 里的展示结果, 槽位元素, 事务和点击语义都不动, 光标也不归它管.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual().setVisualizerProvider(visualizerProvider);
    }

    /**
     * 同 {@link #setVisualizerProvider(Function)}, 顺带给它配一个占位.
     * <p>提供器当场算得出结果时首帧就是真值, 占位用不上.
     *
     * @param visualizerProvider 新的全局视觉映射, {@code null} 表示不参与这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
     */
    default void setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual().setVisualizerProvider(visualizerProvider, placeholder);
    }

    /**
     * 用直接返回 ItemStack 的映射当 Window 的全局视觉映射.
     * <p>约定跟 {@link #setVisualizerProvider(Function)} 一样.
     *
     * @param visualizer 新的全局物品映射, {@code null} 表示不参与这一层
     */
    default void setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual().setVisualizerItem(visualizer);
    }

    /**
     * 这一格自己的视觉映射, 不含会回退过去的全局那层.
     *
     * @param windowSlot Window 槽位
     * @return 逐槽视觉映射; 这一格没覆盖时是 null, 也就是走全局映射
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider(int windowSlot) {
        return this.visual().visualizerProvider(windowSlot);
    }

    /**
     * 换掉某一格的逐槽视觉映射, 它是整条显示路径上最高的一层.
     * <p>映射给非 null 结果就直接用; 给 {@code null} 就是放行, 接着问全局映射.
     * 传 {@code null} 进来则是把这一层撤掉, 这一格从全局映射开始.
     * <p>输入输出的约定跟 {@link #setVisualizerProvider(Function)} 一样.
     *
     * @param windowSlot Window 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    default void setVisualizerProvider(int windowSlot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
        this.visual().setVisualizerProvider(windowSlot, visualizerProvider);
    }

    /**
     * 同 {@link #setVisualizerProvider(int, Function)}, 顺带给它配一个占位.
     *
     * @param windowSlot Window 槽位
     * @param visualizerProvider 新的逐槽视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    default void setVisualizerProvider(int windowSlot, @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.visual().setVisualizerProvider(windowSlot, visualizerProvider, placeholder);
    }

    /**
     * 用直接返回 ItemStack 的映射当这一格的视觉映射.
     * <p>映射返回 {@code null} 就是放行, 返回空 ItemStack 则是把它盖成空视觉.
     *
     * @param windowSlot Window 槽位
     * @param visualizer 新的逐槽物品映射, {@code null} 表示移除这一层
     * @throws IndexOutOfBoundsException 槽号越界时
     */
    default void setVisualizerItem(int windowSlot, @Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
        this.visual().setVisualizerItem(windowSlot, visualizer);
    }

    /**
     * 只管客户端光标的那份视觉配置.
     * <p>同一扇窗始终给同一个对象. 它标脏只影响光标, 不会碰 Window 槽位, 也不会请求全量同步.
     *
     * @return 光标视觉配置与失效范围
     */
    @NotNull
    CursorVisual cursorVisual();

    /**
     * 现在这一层的光标视觉映射.
     *
     * @return 光标视觉映射; 没设过时是 null, 也就是按菜单实际光标显示
     */
    @Nullable
    default Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider() {
        return this.cursorVisual().visualizerProvider();
    }

    /**
     * 换掉光标视觉映射.
     * <p>映射收到的是菜单实际光标的副本, 空光标给 null; 它返回 null 就还是显示菜单实际光标.
     * 映射本身在渲染线程上求值, 它只负责挑这次用哪个提供器, 重活放进返回的提供器里.
     * 提供器出结果之前显示菜单实际光标; 光标内容一变, 没算完的计算和已经算完的结果都作废.
     *
     * @param cursorVisualizerProvider 光标视觉映射, {@code null} 表示移除这一层
     */
    default void setCursorVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider) {
        this.cursorVisual().setVisualizerProvider(cursorVisualizerProvider);
    }

    /**
     * 同 {@link #setCursorVisualizerProvider(Function)}, 顺带给它配一个占位.
     *
     * @param cursorVisualizerProvider 光标视觉映射, {@code null} 表示移除这一层
     * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
     */
    default void setCursorVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider, @Nullable ImmediateItemProvider placeholder) {
        this.cursorVisual().setVisualizerProvider(cursorVisualizerProvider, placeholder);
    }

    /**
     * 用直接返回 ItemStack 的映射当光标视觉.
     * <p>映射收到的是实际光标的副本, 空光标给 null; 它返回 null 就仍旧显示菜单实际光标.
     *
     * @param cursorVisualizer 光标物品映射, {@code null} 表示移除这一层
     */
    default void setCursorVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> cursorVisualizer) {
        this.cursorVisual().setVisualizerItem(cursorVisualizer);
    }

    /**
     * 让 Window 重算某一格的显示.
     * <p>从哪个线程喊都行, 真正的渲染和协议同步会合并到玩家实体 tick.
     * 超出 Window 范围的槽号会被忽略.
     *
     * @param windowSlot Window 槽位
     */
    void notifyUpdate(int windowSlot);

    /**
     * 让 Window 整片重算一遍, 不看缓存.
     */
    void notifyUpdateAll();

    /**
     * 读某一格最近一次推给客户端的内容.
     * <p>拿到的是渲染缓存的副本, 不会触发重新渲染; 槽号越界或者还没渲染过就给空物品.
     *
     * @param windowSlot Window 槽位
     * @return 显示内容的副本; 没有内容时是空物品
     */
    @NotNull
    ItemStack displayedAt(int windowSlot);

    /**
     * 读这一格在最近一次渲染里记下的东西.
     * <p>槽号越界, 还没渲染, 或者当时没记, 都给 {@code null}.
     *
     * @param windowSlot Window 槽位
     * @return 记下的东西; 没有时是 {@code null}
     */
    @Nullable
    Object rememberedAt(int windowSlot);

    /**
     * 这扇窗的查看者.
     *
     * @return 查看者
     */
    @NotNull Player viewer();

    /**
     * 这扇窗现在开着.
     *
     * @return 开着时为 true
     */
    boolean isOpen();

    /**
     * 玩家主动发来的关闭请求收不收.
     *
     * @return 收时为 true
     */
    boolean isCloseable();

    /**
     * 占着玩家物品栏那一片的下部 Pane.
     * <p>合并窗口里 upper 和 lower 是同一个根 Pane.
     *
     * @return 下部 Pane
     */
    @NotNull
    Pane lowerPane();

    /**
     * 从下部 Pane 的第一格试着认一下那个 ReferencingInventory.
     * <p>下部 Pane 得是 9x4, 而且第一格接的是那个 Inventory 的 0 号槽; 形状一样的自定义 Pane 照样匹配.
     *
     * @return 认出来的 ReferencingInventory; 对不上时为 null
     */
    @Nullable
    default ReferencingInventory defaultLowerInventory() {
        Pane lowerPane = this.lowerPane();
        if (lowerPane.width() != 9 || lowerPane.height() != 4) {
            return null;
        }
        return lowerPane.element(0) instanceof Element.InventoryLink(ReferencingInventory inventory, int slot) && slot == 0
                ? inventory
                : null;
    }

    /**
     * Window 直接拥有的根 Pane, 嵌在里面的子 Pane 不算.
     *
     * @return 根 Pane 列表
     */
    @Unmodifiable
    @NotNull List<Pane> panes();

    /**
     * 这一格对应的根 Pane 链接.
     *
     * @param windowSlot Window 槽位
     * @return 根 Pane 链接
     * @throws IndexOutOfBoundsException 槽位超出 Window 范围时
     */
    @NotNull
    Element.PaneLink paneAt(int windowSlot);

    /**
     * 快捷栏这一格对应的根 Pane 链接.
     *
     * @param hotbarSlot 快捷栏索引
     * @return 根 Pane 链接
     * @throws IndexOutOfBoundsException 快捷栏索引超出 0-8 时
     */
    @NotNull
    Element.PaneLink paneAtHotbar(int hotbarSlot);

    /**
     * 快捷栏这一格落在哪个协议槽位.
     *
     * @param hotbarSlot 快捷栏索引(0-8)
     * @return 对应的协议槽位(raw slot)
     * @throws IndexOutOfBoundsException 快捷栏索引超出 0-8 时
     */
    int windowSlotAtHotbar(int hotbarSlot);

    /**
     * 这次 open() 的结果.
     */
    enum OpenResult {
        OPENED,              // 已经走完打开流程
        ALREADY_OPEN,        // 它本来就开着
        VIEWER_UNAVAILABLE   // 玩家现在打不开菜单
    }

    /**
     * 这次 close() 的结果.
     */
    enum CloseResult {
        CLOSED,          // 已经走完关闭流程
        ALREADY_CLOSED   // 它本来就关着
    }

    /**
     * 可以反复用的类型化 Window Builder.
     * <p>一份配置可以拿去建多扇窗; 想各建各互不影响, 先 {@link #clone()} 一份.
     *
     * @param <W> 创建的 Window 类型
     * @param <B> 具体 Builder 类型
     */
    interface Builder<W extends Window, B extends Builder<W, B>> extends Cloneable {

        /**
         * 指定 {@link #build()} 用哪个玩家.
         *
         * @param viewer 查看者
         * @return 此 Builder
         */
        @NotNull B setViewer(@NotNull Player viewer);

        /**
         * 设置动态标题来源.
         * <p>Supplier 返回 null 就显示空标题.
         *
         * @param titleSupplier 标题来源
         * @return 此 Builder
         */
        @NotNull B setTitleSupplier(@NotNull Supplier<? extends Component> titleSupplier);

        /**
         * 设置固定标题.
         *
         * @param title 标题
         * @return 此 Builder
         */
        @NotNull B setTitle(@NotNull Component title);

        /**
         * 拿纯文本当固定标题.
         *
         * @param title 标题
         * @return 此 Builder
         */
        default @NotNull B setTitle(@NotNull String title) {
            return this.setTitle(Component.text(title));
        }

        /**
         * 设置玩家能不能自己关掉这扇窗.
         *
         * @param closeable 是否可由客户端主动关闭
         * @return 此 Builder
         */
        @NotNull B setCloseable(boolean closeable);

        /**
         * 整批换掉打开时要跑的处理器.
         *
         * @param openHandlers 打开处理器
         * @return 此 Builder
         */
        @NotNull B setOpenHandlers(@NotNull List<? extends Consumer<? super W>> openHandlers);

        /**
         * 追加一个打开处理器. 它收到的是本 Builder 建出来的那个具体 Window, 想看查看者用 {@link Window#viewer()}.
         *
         * @param openHandler 打开处理器
         * @return 此 Builder
         */
        @NotNull B addOpenHandler(@NotNull Consumer<? super W> openHandler);

        /**
         * 整批换掉关闭之后要跑的处理器.
         *
         * @param closeHandlers 关闭处理器
         * @return 此 Builder
         */
        @NotNull B setCloseHandlers(
                @NotNull List<? extends BiConsumer<? super W, ? super WindowCloseReason>> closeHandlers
        );

        /**
         * 追加一个关闭处理器. 第一个参数是本 Builder 建出来的具体 Window, 第二个是关闭原因.
         *
         * @param closeHandler 关闭处理器, 第二个参数为关闭原因
         * @return 此 Builder
         */
        @NotNull B addCloseHandler(@NotNull BiConsumer<? super W, ? super WindowCloseReason> closeHandler);

        /**
         * 整批换掉容器外点击的处理器, 每个处理器还会收到本 Builder 建出来的具体 Window.
         *
         * @param outsideClickHandlers 容器外点击处理器
         * @return 此 Builder
         */
        @NotNull
        B setOutsideClickHandlers(
                @NotNull List<? extends BiConsumer<? super W, ? super WindowOutsideClick>> outsideClickHandlers
        );

        /**
         * 追加一个容器外点击处理器, 它同时收到本 Builder 建出来的具体 Window.
         *
         * @param outsideClickHandler 容器外点击处理器
         * @return 此 Builder
         */
        @NotNull
        default B addOutsideClickHandler(@NotNull BiConsumer<? super W, ? super WindowOutsideClick> outsideClickHandler) {
            return this.addModifier(window -> window.addOutsideClickHandler(click -> outsideClickHandler.accept(window, click)));
        }

        /**
         * 追加一个只看点击上下文的容器外点击处理器.
         *
         * @param outsideClickHandler 容器外点击处理器
         * @return 此 Builder
         */
        @NotNull
        B addOutsideClickHandler(@NotNull Consumer<? super WindowOutsideClick> outsideClickHandler);

        /**
         * 设置玩家主动关窗时要不要退回上一扇, 默认 false.
         * <p>语义同 {@link Window#backOnPlayerClose(boolean)}.
         *
         * @param backOnPlayerClose true 表示返回上一扇
         * @return 此 Builder
         */
        @NotNull B setBackOnPlayerClose(boolean backOnPlayerClose);

        /**
         * 设置跟着这扇窗走的用户对象, 语义见 {@link Window#data()}.
         *
         * @param data 携带的对象
         * @return 此 Builder
         */
        @NotNull B setData(@NotNull Object data);

        /**
         * 设置本 Window 成为根窗时新会话用哪种结构, 默认 {@link WindowSession.Kind#STACK}.
         * <p>本 Window 经 {@link Window#navigate} 接进别人的会话时, 这条声明不生效.
         *
         * @param kind 会话类型
         * @return 此 Builder
         */
        @NotNull B setSessionKind(@NotNull WindowSession.Kind kind);

        /**
         * 追加一个会话结束处理器: 本 Window 成为根窗时它被装进新会话, 整段交互结束时恰好跑一次.
         * <p>本 Window 经 {@link Window#navigate} 接进既有会话时这条声明不生效.
         *
         * @param handler 结束处理器, 参数为结束原因
         * @return 此 Builder
         */
        @NotNull B addSessionEndHandler(@NotNull Consumer<? super WindowCloseReason> handler);

        /**
         * 设置初始的服务端窗口状态.
         *
         * @param windowState 初始状态
         * @return 此 Builder
         */
        @NotNull B setWindowState(int windowState);

        /**
         * 整批换掉客户端状态确认的处理器.
         *
         * @param handlers 状态确认处理器
         * @return 此 Builder
         */
        @NotNull B setWindowStateChangeHandlers(
                @NotNull List<? extends Consumer<? super Integer>> handlers
        );

        /**
         * 追加一个客户端状态确认处理器.
         *
         * @param handler 状态确认处理器
         * @return 此 Builder
         */
        @NotNull B addWindowStateChangeHandler(@NotNull Consumer<? super Integer> handler);

        /**
         * 设置 Window 全局视觉映射; 提供器出结果之前, 先显示那一格的真实内容.
         * <p>约定跟 {@link Window#setVisualizerProvider(Function)} 一样, 而且打开之前就已经生效.
         *
         * @param visualizerProvider 全局视觉映射, {@code null} 表示不设置这一层
         * @return 此 Builder
         */
        @NotNull
        default B setVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider) {
            return this.setVisualizerProvider(visualizerProvider, null);
        }

        /**
         * 同 {@link #setVisualizerProvider(Function)}, 顺带指定占位.
         *
         * @param visualizerProvider 全局视觉映射, {@code null} 表示不设置这一层
         * @param placeholder 首次成功结果前显示的占位, {@code null} 表示终点连接 Inventory 时显示该槽真实内容, 其余终点显示空
         * @return 此 Builder
         */
        @NotNull
        B setVisualizerProvider(
                @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> visualizerProvider,
                @Nullable ImmediateItemProvider placeholder
        );

        /**
         * 用直接返回 ItemStack 的映射当 Window 的全局视觉映射.
         *
         * @param visualizer 全局物品映射, {@code null} 表示不设置这一层
         * @return 此 Builder
         */
        @NotNull
        default B setVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> visualizer) {
            return this.setVisualizerProvider(VisualLayer.itemVisualizer(visualizer));
        }

        /**
         * 设置光标视觉映射; 提供器出结果之前先显示菜单实际光标.
         *
         * @param cursorVisualizerProvider 光标视觉映射, {@code null} 表示不设置这一层
         * @return 此 Builder
         */
        @NotNull
        default B setCursorVisualizerProvider(@Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider) {
            return this.setCursorVisualizerProvider(cursorVisualizerProvider, null);
        }

        /**
         * 同 {@link #setCursorVisualizerProvider(Function)}, 顺带指定占位.
         *
         * @param cursorVisualizerProvider 光标视觉映射, {@code null} 表示不设置这一层
         * @param placeholder 首次成功结果前显示的占位, {@code null} 表示显示菜单实际光标
         * @return 此 Builder
         */
        @NotNull
        B setCursorVisualizerProvider(
                @Nullable Function<@Nullable ItemStack, @Nullable ItemProvider> cursorVisualizerProvider,
                @Nullable ImmediateItemProvider placeholder
        );

        /**
         * 用直接返回 ItemStack 的映射当光标视觉.
         * <p>映射收到的是实际光标的副本, 空光标给 null.
         *
         * @param cursorVisualizer 光标物品映射, {@code null} 表示不设置这一层
         * @return 此 Builder
         */
        @NotNull
        default B setCursorVisualizerItem(@Nullable Function<@Nullable ItemStack, @Nullable ItemStack> cursorVisualizer) {
            return this.setCursorVisualizerProvider(VisualLayer.itemVisualizer(cursorVisualizer));
        }

        /**
         * 整批换掉创建完成之后要跑的修改器.
         *
         * @param modifiers Window 修改器
         * @return 此 Builder
         */
        @NotNull B setModifiers(@NotNull List<? extends Consumer<? super W>> modifiers);

        /**
         * 追加一个创建完成之后要跑的修改器.
         *
         * @param modifier Window 修改器
         * @return 此 Builder
         */
        @NotNull B addModifier(@NotNull Consumer<? super W> modifier);

        /**
         * 复制一份独立的 Builder.
         * <p>可变的处理器列表会复制一份, 已经引用的 Pane 和函数对象照旧共用.
         *
         * @return Builder 副本
         */
        @NotNull B clone();

        /**
         * 拿设置好的查看者建一扇 Window.
         * <p>没显式设过 lower Pane 时, 这个调用会同步读查看者的 Bukkit 背包来建
         * {@link ReferencingInventory}; 调用方得保证当前线程能合法访问那个背包.
         *
         * @return 新的未打开 Window
         * @throws IllegalStateException 没设查看者时
         */
        @NotNull W build();

        /**
         * 给指定查看者建一扇 Window.
         * <p>没显式设过 lower Pane 时, 这个调用会同步读查看者的 Bukkit 背包来建
         * {@link ReferencingInventory}; 调用方得保证当前线程能合法访问那个背包.
         *
         * @param viewer 查看者
         * @return 新的未打开 Window
         */
        @NotNull W build(@NotNull Player viewer);

        /**
         * 给指定查看者建一扇 Window 并请求打开.
         * <p>这个入口先在调用线程上跑 {@link #build(Player)}, 所以同样受它的 Bukkit 背包线程约束.
         *
         * @param viewer 查看者
         * @return 打开请求的执行结果
         */
        default @NotNull CompletableFuture<OpenResult> open(@NotNull Player viewer) {
            return this.build(viewer).open();
        }

    }
}
