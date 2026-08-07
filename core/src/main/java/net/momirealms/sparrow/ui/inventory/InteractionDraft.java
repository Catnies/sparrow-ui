package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次玩家交互的副作用草稿: 保存事务提交后要留在容器外的最终状态.
 * <p>光标, 副手和掉落物不属于任何 Inventory, 没有办法放进按 Inventory 分组的事务写集.
 * 规划阶段先把候选算出的结果写进这里, 事务提交后, Post 事件派发前一次性应用, 因此提交后处理器
 * 已经能读到最终光标.
 * <p>这里保存的都是提交后的最终值. 并发检测使用的规划期原值由框架单独持有, 谁也不能改写:
 * 改了它, 交互要么每次都因为对不上而静默消失, 要么彻底失去发现并发写入的能力.
 * <p><b>草稿只提供改写能力, 不负责守恒.</b> 缩小某个槽位的最终值不会让框架自动把差额还给光标 ——
 * 只有处理器自己知道这次交互的语义. 需要还回去多少, 由处理器算好后写进这里.
 * <p>草稿在事务离开提交前阶段时封笔. 处理器把引用带出同步回调之后再写入会直接失败.
 */
public final class InteractionDraft {
    @Nullable private ItemStack cursor;      // 提交后的光标, null 表示不改动
    private boolean cursorAdopted;           // 光标是否由规划器交出所有权, 决定提交时采纳还是复制
    @Nullable private ItemStack consumedCursor; // 被整堆放进槽位的那个光标实例, 落地时据此把它的 NMS 句柄一起搬过去
    @Nullable private ItemStack offhand;     // 提交后的副手, 是否生效由 offhandTouched 决定
    private boolean offhandTouched;          // 与"副手要被清空"区分开, null 副手也是一次有效改动
    @Nullable private List<ItemStack> drops; // 提交后要丢进世界的物品, 没有掉落时保持 null
    private final Thread interactionThread;  // 规划本次交互的线程, 只有它能改写草稿
    private volatile boolean editable = true; // 编辑窗口是否仍然打开

    private InteractionDraft() {
        // 从规划到提交都在同一个交互线程上同步跑完, 构造线程就是唯一合法的写入线程.
        this.interactionThread = Thread.currentThread();
    }

    /**
     * 创建一份不改动任何容器外状态的草稿.
     *
     * @return 空草稿
     */
    @NotNull
    static InteractionDraft empty() {
        return new InteractionDraft();
    }

    /**
     * 创建一份只覆盖光标的草稿.
     *
     * @param cursor 提交后的光标物品
     * @return 已经记录光标最终值的草稿
     */
    @NotNull
    static InteractionDraft cursorAfter(@NotNull ItemStack cursor) {
        InteractionDraft draft = new InteractionDraft();
        draft.adoptCursor(cursor);
        return draft;
    }

    /**
     * 采纳规划器算出的光标物品, 不复制.
     * <p>只有规划器能走这条入口: 它交出的实例要么是本次规划新造的, 要么是被整体搬运的槽位物品,
     * 所有权明确. 提交时这份实例会被菜单原样接管, 物品从槽位搬到光标时对象身份因此得以保持.
     *
     * @param cursor 提交后的光标物品
     * @throws IllegalStateException 草稿已经封笔, 或从其他线程调用
     */
    void adoptCursor(@NotNull ItemStack cursor) {
        this.checkEditable();
        this.cursor = cursor;
        this.cursorAdopted = true;
    }

    /**
     * 记下这次规划整堆放进槽位的那个光标实例.
     * <p>只有规划器能走这条入口: 记下的就是写集里某条变更的变更后实例. 内容放在外部存储的 Inventory
     * 落地时靠它认出"这件物品是从光标搬过来的", 直接把 NMS 句柄接过去, 而不是写一份副本进去.
     * <p>能这么记的前提是本草稿同时也写了新的光标最终值: 这个实例因此一定会离开光标, 不会出现光标和槽位共用一个对象.
     *
     * @param cursor 被放进槽位的那个光标实例
     * @throws IllegalStateException 草稿已经封笔, 或从其他线程调用
     */
    void consumedCursor(@NotNull ItemStack cursor) {
        this.checkEditable();
        this.consumedCursor = cursor;
    }

    /**
     * 返回这次规划整堆放进槽位的那个光标实例.
     *
     * @return 那个光标实例; 本次规划没有整堆搬走光标时为 {@code null}
     */
    @Nullable
    ItemStack consumedCursor() {
        return this.consumedCursor;
    }

    /**
     * 创建一份只覆盖副手的草稿.
     *
     * @param offhand 提交后的副手物品, {@code null} 表示清空
     * @return 已经记录副手最终值的草稿
     */
    @NotNull
    static InteractionDraft offhandAfter(@Nullable ItemStack offhand) {
        InteractionDraft draft = new InteractionDraft();
        draft.offhand(offhand);
        return draft;
    }

    /**
     * 创建一份只记录一件掉落物的草稿.
     *
     * @param item 提交后要丢进世界的物品
     * @return 已经记录掉落物的草稿
     */
    @NotNull
    static InteractionDraft dropped(@NotNull ItemStack item) {
        InteractionDraft draft = new InteractionDraft();
        draft.drop(item);
        return draft;
    }

    /**
     * 返回提交后的光标物品的副本.
     * <p>返回副本而不是草稿实例: 搬运路径下草稿里的光标可能就是某个 Inventory 内部状态数组里的元素,
     * 交出活引用会让处理器直接改写容器内容, 绕过事务与 Window 同步.
     *
     * @return 光标最终值的副本; 返回 {@code null} 表示本次交互不改动光标, 提交后光标保持交互前的样子
     */
    @Nullable
    public ItemStack cursor() {
        return ItemUtils.copyOrNull(this.cursor);
    }

    /**
     * 覆盖提交后的光标物品.
     * <p>写入的物品会被复制, 草稿不持有调用方实例. 处理器常常直接把 {@code Inventory#getItem} 或
     * {@code getItemInMainHand} 的返回值写进来, 而它们在 CraftBukkit 上是与真实槽位共享底层句柄的活视图;
     * 若原样接管, 光标就会和那个槽位变成同一件物品.
     *
     * @param cursor 新的光标最终值, 空物品表示提交后光标为空
     * @throws IllegalStateException 草稿已经封笔, 或从其他线程调用
     */
    public void cursor(@NotNull ItemStack cursor) {
        this.checkEditable();
        this.cursor = ItemUtils.copyOrEmpty(cursor);
        this.cursorAdopted = false;
    }

    /**
     * 返回提交后的副手物品.
     *
     * @return 副手最终值; 清空副手与不改动副手都返回 {@code null}
     */
    @Nullable
    public ItemStack offhand() {
        return this.offhand;
    }

    /**
     * 覆盖提交后的副手物品.
     *
     * @param offhand 新的副手最终值, {@code null} 表示清空副手
     * @throws IllegalStateException 草稿已经封笔, 或从其他线程调用
     */
    public void offhand(@Nullable ItemStack offhand) {
        this.checkEditable();
        this.offhand = offhand;
        this.offhandTouched = true;
    }

    /**
     * 追加一件提交后要丢进世界的物品.
     * <p>掉落物按追加顺序在玩家脚下产出, 已经记录的掉落物不会被覆盖.
     *
     * @param item 要丢出的物品
     * @throws IllegalStateException 草稿已经封笔, 或从其他线程调用
     */
    public void drop(@NotNull ItemStack item) {
        this.checkEditable();
        List<ItemStack> drops = this.drops;
        if (drops == null) {
            drops = new ArrayList<>(1);
            this.drops = drops;
        }
        drops.add(item);
    }

    // 校验编辑窗口仍然打开, 且调用方就是发起本次交互的线程. 提交阶段读取本草稿时不能再被任何人改写.
    private void checkEditable() {
        if (!this.editable || Thread.currentThread() != this.interactionThread) {
            throw new IllegalStateException("interaction draft can only be edited before the transaction leaves its pre-update stage");
        }
    }

    /**
     * 封笔, 由事务在最终提交条件通过后调用.
     * <p>封笔后任何写入都会失败, 防止逃逸出去的引用在加锁写入期间改动已经定案的最终值.
     */
    @ApiStatus.Internal
    public void seal() {
        this.editable = false;
    }

    /**
     * 把草稿的最终值应用到 Window 侧, 由提交成功的事务在 Post 派发前调用.
     *
     * @param context 当前 Window 交互上下文
     */
    @ApiStatus.Internal
    public void apply(@NotNull ClickSemantics.Context context) {
        ItemStack cursor = this.cursor;
        if (cursor != null) {
            // 只有规划器交出所有权的实例才整体接管, 身份因此得以保持; 处理器写入的一律走复制入口,
            // 否则它交进来的活视图会直通菜单光标, 与来源槽位变成同一件物品.
            if (this.cursorAdopted) {
                context.adoptCursor(cursor);
            } else {
                context.cursor(cursor);
            }
        }
        if (this.offhandTouched) {
            context.offhand(this.offhand);
        }
        List<ItemStack> drops = this.drops;
        if (drops == null) {
            return;
        }
        for (int i = 0; i < drops.size(); i++) {
            context.drop(drops.get(i));
        }
    }
}
