package net.momirealms.sparrow.ui.inventory;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 外部存储的身份能力: 存储世界里存在"消费者会长期持有的活对象"(Bukkit/NMS 容器)时才有意义.
 * 回写路径靠它走句柄搬运与原地改数两条保身份快路径; 计数背包, 纯内存, 远端等没有活对象的存储
 * 不要实现本接口, 它们自然落在"等值跳过 + 内容替换"两路上, 内容永远正确.
 */
@ApiStatus.Internal
public interface LiveCapableStorage extends ExternalStorage {

    /**
     * 零拷贝读取槽位现值: 返回与存储共享底层句柄的活视图, 对它原地改数直接落进存储.
     * <p>给不出共享底层实例时必须返回 {@code null}, 让回写自然落到内容替换;
     *
     * @param slot 存储槽位
     * @return 槽位现值的活视图; 空槽或给不出共享实例时返回 {@code null}
     */
    @Nullable
    ItemStack liveView(int slot);

    /**
     * 是否支持把 NMS 物品句柄原样注入存储槽位.
     *
     * @return 支持句柄注入时返回 {@code true}
     */
    boolean supportsHandleTransfer();

    /**
     * 把 NMS 物品实例原样写进存储槽位, 不复制.
     * <p>调用方必须先经 {@link #supportsHandleTransfer()} 确认能力, 并在交出句柄后不再持有或修改它.
     *
     * @param slot 存储槽位
     * @param itemHandle NMS {@code ItemStack} 实例
     */
    void adoptHandle(int slot, @NotNull Object itemHandle);
}
