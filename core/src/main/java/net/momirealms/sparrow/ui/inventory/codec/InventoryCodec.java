package net.momirealms.sparrow.ui.inventory.codec;

import net.momirealms.sparrow.ui.exception.InventoryDecodeException;
import net.momirealms.sparrow.ui.inventory.VirtualInventory;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@link VirtualInventory} 与字节数组之间的编解码.
 * <p>只序列化**身份与槽内容**: 堆叠上限, 迭代顺序, guiPriority 与订阅者都是运行期
 * 装配, 一律不入流. 落盘, 入库还是发网络由调用方决定, 本类只产出和消费 {@code byte[]}.
 *
 * <h2>字节布局</h2>
 * <pre>
 * uuid        : 16 字节   高 64 位与低 64 位, 大端
 * format      : 1 字节    信封格式版本, 当前为 1
 * dataVersion : 4 字节    写出时的 Minecraft DataVersion, 全流一次
 * size        : 4 字节    槽数
 * mask        : ceil(size/8) 字节  非空槽位图, 每字节低位在前
 * items       : GZIP 流   每个非空槽一段 [varint 长度 + 裸 NBT], 按槽号升序
 * </pre>
 * 空槽在掩码里是一个 0 位, 不占任何字节; DataVersion 与压缩各只出现一次 ——
 * 这正是取道 {@link NmsItemCodec} 而不是逐物品 {@code serializeAsBytes()} 的收益.
 *
 * <h2>解码契约</h2>
 * 输入按不受信任对待: 每个长度与计数字段在分配前经 {@link DecodeLimits} 校验,
 * 掩码填充位, 物品区尾部残留与降级加载都会明确拒绝. 任何不合法的输入抛
 * {@link InventoryDecodeException} 且零产出, 绝不返回半加载的库存 —— 静默丢数据
 * 比失败更糟. 调用方按"该条数据损坏"处理: 跳过, 旁置或上报, 不要重试;
 * 唯一例外是越限类失败, 字节流可能完好, 调大 {@link DecodeLimits} 后可完整重读.
 */
public final class InventoryCodec {
    static final int FORMAT = 1; // 本实现读写的信封格式版本

    // InvUI 的 VirtualInventory 用同样的 uuid 打头, 靠这个位置的版本字节区分自己的
    // 三代格式. 取值不重叠即互斥: 拿到它的流要能报出人话, 而不是当成畸形数据
    private static final int INVUI_FORMAT_MIN = 3;
    private static final int INVUI_FORMAT_MAX = 5;

    private final ItemCodec itemCodec;
    private final DecodeLimits limits;

    // 包内构造: 测试注入物品编解码替身, 从而在没有服务端的环境里覆盖整个信封路径
    InventoryCodec(@NotNull ItemCodec itemCodec, @NotNull DecodeLimits limits) {
        this.itemCodec = itemCodec;
        this.limits = limits;
    }

    /**
     * 以缺省限额创建编解码器.
     */
    @NotNull
    public static InventoryCodec create() {
        return InventoryCodec.create(DecodeLimits.DEFAULT);
    }

    /**
     * 以给定限额创建编解码器.
     * <p>物品编解码走原版 Codec, 因此实例只在有服务端与注册表访问的环境里可用.
     */
    @NotNull
    public static InventoryCodec create(@NotNull DecodeLimits limits) {
        return new InventoryCodec(NmsItemCodec.INSTANCE, limits);
    }

    /**
     * 把库存编码为字节数组.
     * <p>内容取自一次一致性快照, 与并发写入无竞争.
     * <p>编码本身不受 {@link DecodeLimits} 约束 —— 限额是给不受信任的输入用的,
     * 自家库存不必先过安检. 但两端要对得上: 槽数或单物品体积超过读取方限额的流,
     * 对方会明确拒绝, 这种库存需要双方都调大限额.
     *
     * @throws UncheckedIOException 当压缩或 NBT 写出失败时
     */
    public byte @NotNull [] encode(@NotNull VirtualInventory inventory) {
        @Nullable ItemStack[] slots = inventory.snapshot();
        UUID uuid = inventory.uuid();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // 明文段: 身份, 格式版本, DataVersion, 槽数与非空槽位图
            DataOutputStream envelope = new DataOutputStream(buffer);
            envelope.writeLong(uuid.getMostSignificantBits());
            envelope.writeLong(uuid.getLeastSignificantBits());
            envelope.writeByte(FORMAT);
            envelope.writeInt(this.itemCodec.currentDataVersion());
            envelope.writeInt(slots.length);
            envelope.write(InventoryCodec.buildMask(slots));
            envelope.flush();

            // 压缩只包物品区: 掩码留在明文段, 解码可以先读出它再决定要读几段物品
            try (DataOutputStream items = new DataOutputStream(new GZIPOutputStream(buffer))) {
                for (int slot = 0; slot < slots.length; slot++) {
                    ItemStack item = slots[slot];
                    if (item == null) {
                        continue;
                    }
                    byte[] nbt = this.itemCodec.encodeItem(item);
                    InventoryCodec.writeVarInt(items, nbt.length);
                    items.write(nbt);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to encode inventory " + uuid, exception);
        }
        return buffer.toByteArray();
    }

    /**
     * 把字节数组解码为库存.
     *
     * @throws InventoryDecodeException 当字节流不是本实现能读的信封, 越过限额,
     *         自相矛盾, 被截断, 或来自更新版本的 Minecraft 时
     */
    @NotNull
    public VirtualInventory decode(byte @NotNull [] bytes) {
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        DataInputStream envelope = new DataInputStream(source);
        try {
            UUID uuid = new UUID(envelope.readLong(), envelope.readLong());
            int dataVersion = this.readHeader(envelope);

            // 槽数先过限额再据以分配掩码: 顺序反过来就等于让流自己决定分配多少内存
            int size = envelope.readInt();
            if (size < 0 || size > this.limits.maxSize()) {
                throw new InventoryDecodeException("declared slot count " + size + " is outside 0.." + this.limits.maxSize());
            }
            byte[] mask = new byte[(size + 7) / 8];
            envelope.readFully(mask);
            InventoryCodec.requireCleanMaskPadding(mask, size);

            byte[] region;
            try (GZIPInputStream compressed = new GZIPInputStream(source)) {
                region = InventoryCodec.inflateBounded(compressed, this.limits.maxItemRegionBytes());
            }
            return new VirtualInventory(uuid, this.readItems(region, mask, size, dataVersion));
        } catch (EOFException exception) {
            throw new InventoryDecodeException("inventory stream is truncated", exception);
        } catch (IOException exception) {
            throw new InventoryDecodeException("inventory stream is malformed", exception);
        }
    }

    // 读出并校验格式版本与 DataVersion, 返回后者
    private int readHeader(DataInput envelope) throws IOException {
        int format = envelope.readUnsignedByte();
        if (format >= INVUI_FORMAT_MIN && format <= INVUI_FORMAT_MAX) {
            throw new InventoryDecodeException("this is an InvUI VirtualInventory stream (version " + format + "), which is not readable here");
        }
        if (format != FORMAT) {
            throw new InventoryDecodeException("unsupported inventory format " + format + ", this build reads " + FORMAT);
        }

        int dataVersion = envelope.readInt();
        if (dataVersion < 0) {
            throw new InventoryDecodeException("negative data version " + dataVersion);
        }
        // 降级加载会让新组件在旧版本上静默丢失, 明确拒绝而不是尽力解析
        int current = this.itemCodec.currentDataVersion();
        if (dataVersion > current) {
            throw new InventoryDecodeException("stream comes from a newer Minecraft (data version " + dataVersion + " > " + current + "), downgrades are unsupported");
        }
        return dataVersion;
    }

    /**
     * 按掩码从物品区逐段读出物品, 每一步都先校验再分配.
     * <p>这是整条解码路径上唯一直接消费不受信任长度的地方, 也是唯一会把字节交给
     * 物品层的地方, 因此限额, 空物品与物品层异常三道检查都收在这里.
     */
    private @Nullable ItemStack @NotNull [] readItems(byte[] region, byte[] mask, int size, int dataVersion) throws IOException {
        DataInputStream items = new DataInputStream(new ByteArrayInputStream(region));
        @Nullable ItemStack[] slots = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            if ((mask[slot >> 3] & (1 << (slot & 7))) == 0) {
                continue;
            }
            int length = InventoryCodec.readVarInt(items);
            if (length < 1 || length > this.limits.maxItemBytes()) {
                throw new InventoryDecodeException("slot " + slot + " declares " + length + " NBT bytes, outside 1.." + this.limits.maxItemBytes());
            }
            byte[] nbt = new byte[length];
            items.readFully(nbt);

            // 物品层是不受信任字节真正撞上 NBT 解析器的地方, 它的任何失败都等价于
            // "这段数据坏了"; 统一收敛成解码异常, 调用方才能只捕获一种类型
            ItemStack decoded;
            try {
                decoded = this.itemCodec.decodeItem(nbt, dataVersion, this.limits.maxItemHeapBytes());
            } catch (InventoryDecodeException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new InventoryDecodeException("slot " + slot + " holds an item that could not be decoded", exception);
            }
            if (ItemUtils.isEmpty(decoded)) {
                throw new InventoryDecodeException("slot " + slot + " is masked as filled but decoded to an empty item");
            }
            slots[slot] = decoded;
        }
        if (items.read() != -1) {
            throw new InventoryDecodeException("item region has trailing bytes after the last masked slot");
        }
        return slots;
    }

    // 非空槽位图, 每字节低位在前; 长度固定为 ceil(size/8), 尾部空位补零
    private static byte[] buildMask(@Nullable ItemStack[] slots) {
        byte[] mask = new byte[(slots.length + 7) / 8];
        for (int slot = 0; slot < slots.length; slot++) {
            if (slots[slot] != null) {
                mask[slot >> 3] |= (byte) (1 << (slot & 7));
            }
        }
        return mask;
    }

    // 尾字节里超出 size 的填充位必须为零: 置位说明流声明了不存在的槽, 是自相矛盾
    private static void requireCleanMaskPadding(byte[] mask, int size) {
        int usedBits = size & 7;
        if (usedBits == 0) {
            return;
        }
        int padding = (mask[mask.length - 1] & 0xFF) >>> usedBits;
        if (padding != 0) {
            throw new InventoryDecodeException("mask sets bits beyond the declared slot count " + size);
        }
    }

    // 边解压边计量: 先解完再看大小等于把压缩炸弹照单全收
    private static byte[] inflateBounded(InputStream input, long max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            if ((long) buffer.size() + read > max) {
                throw new InventoryDecodeException("item region inflates past the " + max + " byte decode limit");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void writeVarInt(DataOutput output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    // 五字节封顶: 更长的续位串是构造出来的溢出输入, 不是合法 int
    private static int readVarInt(DataInput input) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int read = input.readUnsignedByte();
            value |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return value;
            }
        }
        throw new InventoryDecodeException("varint is longer than 5 bytes");
    }
}
