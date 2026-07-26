package net.momirealms.sparrow.ui.inventory.codec;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 单个物品与其裸 NBT 字节之间的窄转换缝.
 * <p>它把整条 NMS 依赖(物品 Codec, NBT 字节 IO, DataFixerUpper)收敛到一个接口后面:
 * {@link InventoryCodec} 只负责信封编排, 对物品内部一无所知. 真实实现是
 * {@link NmsItemCodec}, 需要运行中的服务端与注册表访问; 纯 JUnit 环境注入替身,
 * 即可在没有 Bukkit 的情况下验证信封, 掩码, 限额与损坏拒绝的全部行为.
 * <p>字节是**裸 NBT**: 不含 DataVersion, 不含压缩包装 —— 这两者由信封统一承担,
 * 全文件各一次.
 */
interface ItemCodec {

    /**
     * 当前服务端的 Minecraft DataVersion, 编码时写入信封, 解码时用作升级目标.
     */
    int currentDataVersion();

    /**
     * 把物品编码为裸 NBT 字节.
     *
     * @param item 非空物品; 空物品由信封的掩码排除, 不会到达这里
     * @return 该物品的裸 NBT 字节
     */
    byte @NotNull [] encodeItem(@NotNull ItemStack item);

    /**
     * 把裸 NBT 字节解码为物品, 必要时先过 DataFixerUpper 升级.
     *
     * @param nbt 裸 NBT 字节, 长度已由信封按限额校验
     * @param dataVersion 该字节写出时的 DataVersion, 不大于 {@link #currentDataVersion()}
     * @param heapQuota 解析期间允许分配的堆字节上限, 越额即失败
     * @return 解码出的物品
     */
    @NotNull
    ItemStack decodeItem(byte @NotNull [] nbt, int dataVersion, long heapQuota);
}
