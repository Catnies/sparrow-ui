package net.momirealms.sparrow.ui.exception;

/**
 * 表示一段字节流不是本实现能够解析的完整 Inventory 信封.
 * <p>解码路径对格式损坏的输入以本异常明确失败, 绝不静默产出半加载的 Inventory;
 * 调用方按"该条数据损坏"处理: 跳过, 旁置或上报, 不要重试.
 */
public final class InventoryDecodeException extends IllegalArgumentException {

    public InventoryDecodeException(String message) {
        super(message);
    }

    public InventoryDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
