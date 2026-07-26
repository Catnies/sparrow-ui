package net.momirealms.sparrow.ui.inventory.codec;

/**
 * 防御性解码的限额组: 在分配内存之前约束每个从字节流读出的长度或计数字段.
 * <p>字节流是不受信任的输入 —— 声明"槽数二十亿"或"单物品 NBT 一个 G"的流必须在
 * 分配前就被拒绝, 而不是先分配再 OOM. 越过任一限额即抛
 * {@link net.momirealms.sparrow.ui.exception.InventoryDecodeException}, 不做截断也不做尽力恢复.
 *
 * @param maxSize 允许的最大槽数
 * @param maxItemBytes 单个物品裸 NBT 的最大字节数
 * @param maxItemRegionBytes 物品区解压后的最大总字节数, 这是压缩炸弹的防线
 * @param maxItemHeapBytes 解析单个物品 NBT 时允许分配的最大堆字节数
 */
public record DecodeLimits(int maxSize, int maxItemBytes, long maxItemRegionBytes, long maxItemHeapBytes) {
    /**
     * 缺省限额: 足够宽松地容纳任何现实库存(含装满潜影盒的槽位), 又足够紧地
     * 让恶意流在分配前失败.
     * <p>槽数上限取 8192 —— 相当于一百多个大箱子, 远超任何真实库存, 同时把一段
     * 几十字节的流最多能诱导的分配压在几十 KB, 不给放大攻击留空间.
     */
    public static final DecodeLimits DEFAULT = new DecodeLimits(8192, 2 * 1024 * 1024, 16L * 1024 * 1024, 8L * 1024 * 1024);

    /**
     * @throws IllegalArgumentException 当任一限额不是正数, 或 maxSize 大到使掩码长度算术回绕时
     */
    public DecodeLimits {
        requirePositive("maxSize", maxSize);
        requirePositive("maxItemBytes", maxItemBytes);
        requirePositive("maxItemRegionBytes", maxItemRegionBytes);
        requirePositive("maxItemHeapBytes", maxItemHeapBytes);
        // 掩码长度算术 (size + 7) / 8 在 int 域进行, 上界保证它对任何通过校验的 size 都不回绕
        if (maxSize > Integer.MAX_VALUE - 7) {
            throw new IllegalArgumentException("maxSize must not exceed " + (Integer.MAX_VALUE - 7) + ": " + maxSize);
        }
    }

    private static void requirePositive(String name, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
