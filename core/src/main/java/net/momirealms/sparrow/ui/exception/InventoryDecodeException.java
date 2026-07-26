package net.momirealms.sparrow.ui.exception;

/**
 * 表示一段字节流不是本实现能够解析的库存信封, 或其内容越过了防御性解码限额.
 * <p>解码路径对不受信任的输入一律以本异常明确失败, 绝不静默产出半加载的库存;
 * 调用方按"该条数据损坏"处理: 跳过, 旁置或上报, 不要重试.
 * <p>唯一例外是越限类失败(槽数, 单物品体积或解压总量超过 DecodeLimits):
 * 字节流本身可能完好, 用更大的限额重新解码即可完整读回 —— 只有这一类
 * 值得在调整配置后重试.
 */
public final class InventoryDecodeException extends IllegalArgumentException {

    public InventoryDecodeException(String message) {
        super(message);
    }

    public InventoryDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
