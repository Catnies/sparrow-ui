package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次玩家交互的副作用草稿, 记着事务提交后光标, 副手和掉落物该是什么样.
 * <p>这三样都不属于任何 Inventory, 塞不进按 Inventory 分组的事务写集, 所以单独攒在这里,
 * 提交之后, Post 派发之前一次性应用, Post 处理器因此读得到最终光标.
 * <p><strong>草稿只提供改写能力, 不负责守恒</strong>, 把某个槽位的最终值改小, 框架不会自动把差额还给光标 ——
 * 只有处理器自己知道这次交互的语义, 要还多少由它算好写进来.
 * <p>草稿在事务离开提交前阶段时封笔, 把引用带出同步回调之后再写入会直接失败.
 */
public final class InteractionDraft {
    @Nullable private ItemStack cursor;       // 提交后的光标, null 表示不改动
    @Nullable private ItemStack offhand;      // 提交后的副手, 是否生效由 offhandTouched 决定
    private boolean offhandTouched;           // 有它才能把"清空副手"和"不动副手"分开, null 副手也是一次有效改动
    @Nullable private List<ItemStack> drops;  // 提交后要丢进世界的物品, 没有掉落时保持 null
    private final Thread interactionThread;   // 规划本次交互的线程, 只有它能改写草稿
    private volatile boolean editable = true; // 编辑窗口是否仍然打开

    private InteractionDraft() {
        // 从规划到提交都在同一个交互线程上同步跑完, 构造线程就是唯一合法的写入线程.
        this.interactionThread = Thread.currentThread();
    }

    // 一份什么都不改的空草稿.
    @NotNull
    static InteractionDraft empty() {
        return new InteractionDraft();
    }

    // 只覆盖光标的草稿.
    @NotNull
    static InteractionDraft cursorAfter(@NotNull ItemStack cursor) {
        InteractionDraft draft = new InteractionDraft();
        draft.cursor(cursor);
        return draft;
    }

    // 只覆盖副手的草稿, offhand 为 null 表示清空副手.
    @NotNull
    static InteractionDraft offhandAfter(@Nullable ItemStack offhand) {
        InteractionDraft draft = new InteractionDraft();
        draft.offhand(offhand);
        return draft;
    }

    // 只记一件掉落物的草稿.
    @NotNull
    static InteractionDraft dropped(@NotNull ItemStack item) {
        InteractionDraft draft = new InteractionDraft();
        draft.drop(item);
        return draft;
    }

    /**
     * 返回提交后的光标物品的副本.
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

    // 封笔, 事务在最终提交条件通过后调用, 之后任何写入都会失败, 免得逃逸出去的引用在加锁写入期间改动已经定案的值.
    @ApiStatus.Internal
    public void seal() {
        this.editable = false;
    }

    // 把草稿的最终值应用到 Window 侧, 由提交成功的事务在 Post 派发前调用.
    @ApiStatus.Internal
    public void apply(@NotNull ClickSemantics.Context context) {
        ItemStack cursor = this.cursor;
        if (cursor != null) {
            context.cursor(cursor);
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
