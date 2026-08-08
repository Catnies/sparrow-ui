/**
 * 受事务保护的 Inventory 与点击语义.
 *
 * <p>两种 Inventory 实现: {@link net.momirealms.sparrow.ui.inventory.VirtualInventory} 内容在自己的
 * 状态数组里, 参与事务加锁, 并发校验与状态交换, 任意线程可读;
 * {@link net.momirealms.sparrow.ui.inventory.ReferencingInventory} 内容在
 * {@link net.momirealms.sparrow.ui.inventory.ExternalStorage} 里, 读写直达存储, 并发校验看 modCount,
 * 外部世界的直接修改由 lastKnown 比对吸收并以 External 原因派发 post 事件.
 *
 * <p>一次点击的主线:
 * <ol>
 * <li>{@code ClickSemantics} 收下 Window 解析好的点击或拖拽;</li>
 * <li>{@code ClickPlanner} 打开各 Inventory 的 {@code PlannedRoot} 规划基准, 按
 *     {@code ClickSlotRules}(槽位数学)与 {@code ClickBundleRules}(收纳袋组件)算出 {@code ClickCandidate};</li>
 * <li>{@code ClickExecutor} 带候选依次过闸门: Bukkit 事件期间的写入先攒进 {@code InteractionOverlay}
 *     (这一格现在就是这个值), 有覆盖或前提变化就重规划一次, 结算进 {@code InteractionEdits} 背后的
 *     两份草稿 —— 容器内容进 {@code TransactionDraft}, 光标副手掉落物进 {@code InteractionDraft};</li>
 * <li>{@code InventoryTransactions} 提交: pre 链询问 → 锁内校验与状态交换 → 落地进外部存储 →
 *     post 事件按提交顺序派发.</li>
 * </ol>
 *
 * <p>对象身份约定: 一笔事务写过的槽位, 提交后一律是新实例; 没写过的槽位与等值写入的槽位,
 * 实例原样不动. 光标, 副手和事件负载都是副本. 跨事务追踪变化请订阅事件, 不要指望缓存的实例跟着槽位变.
 */
package net.momirealms.sparrow.ui.inventory;
