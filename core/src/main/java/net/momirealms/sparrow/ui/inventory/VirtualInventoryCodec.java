package net.momirealms.sparrow.ui.inventory;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.momirealms.sparrow.ui.exception.InventoryDecodeException;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.CraftRegistryProxy;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.core.HolderLookupProviderProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.nbt.NbtAccounterProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.nbt.NbtIoProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.nbt.NbtOpsProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.util.datafix.DataFixersProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.util.datafix.fixes.ReferencesProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import net.momirealms.sparrow.ui.util.MiscUtils;
import net.momirealms.sparrow.ui.util.VersionHelper;
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
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * {@link VirtualInventory} 的包内字节编解码实现.
 * <p>此类不缓存任何反射代理或注册表对象, 只有真正调用编解码方法时才会解析代理.
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
 */
final class VirtualInventoryCodec {
    private static final int FORMAT = 1; // 信封格式版本

    private static final DynamicOps<Object> REGISTRY_OPS; // 带注册表上下文的 ops, 原版 ItemStack Codec 编解码需要

    static {
        REGISTRY_OPS = HolderLookupProviderProxy.INSTANCE.createSerializationContext(
                CraftRegistryProxy.INSTANCE.getMinecraftRegistry(),
                NbtOpsProxy.INSTANCE.getINSTANCE()
        );
    }

    private VirtualInventoryCodec() {
    }

    /**
     * 把库存的一致性快照编码为字节数组.
     *
     * @param inventory 待编码的库存
     * @return 编码后的字节数组, 布局见类级说明
     * @throws UncheckedIOException 当底层流写出失败时
     */
    static byte @NotNull [] serialize(@NotNull VirtualInventory inventory) {
        UUID uuid = inventory.uuid();
        @Nullable ItemStack[] items = inventory.snapshot();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // 明文段保留身份、版本和槽位图, 解码时可先完成尺寸校验
            DataOutputStream envelope = new DataOutputStream(buffer);
            envelope.writeLong(uuid.getMostSignificantBits());
            envelope.writeLong(uuid.getLeastSignificantBits());
            envelope.writeByte(FORMAT);
            envelope.writeInt(VersionHelper.WORLD_VERSION);
            envelope.writeInt(items.length);
            envelope.write(MiscUtils.buildMask(items));
            envelope.flush();

            // 物品区整体压缩, 每个非空槽只写长度和裸 NBT
            try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(buffer))) {
                for (int slot = 0; slot < items.length; slot++) {
                    ItemStack item = items[slot];
                    if (item != null) {
                        byte[] nbt = VirtualInventoryCodec.serializeItem(item);
                        VirtualInventoryCodec.writeVarInt(dos, nbt.length);
                        dos.write(nbt);
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to serialize inventory " + uuid, exception);
        }
        return buffer.toByteArray();
    }

    /**
     * 从字节数组恢复完整库存, 任何格式畸形都在产出库存前拒绝.
     * <p>此入口不限制物品区解压体积或 NBT 堆用量, 调用方负责控制输入来源与资源边界.
     *
     * @param bytes 完整的库存字节数据
     * @return 恢复出的库存
     * @throws InventoryDecodeException 当数据被截断或畸形时
     */
    @NotNull
    static VirtualInventory deserialize(byte @NotNull [] bytes) {
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        DataInputStream envelope = new DataInputStream(source);
        try {
            UUID uuid = new UUID(envelope.readLong(), envelope.readLong());
            int format = envelope.readUnsignedByte();
            switch (format) {
                case 1 -> {
                    // 校验 DataVersion, 降级加载会让新组件在旧版本上静默丢失, 因此只允许同版本或向上升级.
                    int dataVersion = envelope.readInt();
                    if (dataVersion < 0 || dataVersion > VersionHelper.WORLD_VERSION)
                        throw new InventoryDecodeException("stream comes from a newer Minecraft or invalid data version (data version: " + dataVersion + ", current data version: " + VersionHelper.WORLD_VERSION + ")");
                    // 校验 Size, 不得为负数.
                    int size = envelope.readInt();
                    if (size < 0)
                        throw new InventoryDecodeException("declared slot count " + size + " is negative");
                    // 校验 Marks, 不得包含空元素.
                    byte[] mask = new byte[Math.ceilDiv(size, 8)];
                    envelope.readFully(mask);
                    VirtualInventoryCodec.requireCleanMaskPadding(mask, size);
                    // 读取剩余压缩流.
                    byte[] region;
                    try (GZIPInputStream compressed = new GZIPInputStream(source)) {
                        region = compressed.readAllBytes();
                    }
                    // 读取物品, 创建 VirtualInventory.
                    @Nullable ItemStack[] itemStacks = deserializeItems(region, mask, size, dataVersion);
                    return new VirtualInventory(uuid, itemStacks);
                }
                default -> throw new InventoryDecodeException("unsupported inventory format " + format + ", this build reads " + FORMAT);
            }
        } catch (EOFException exception) {
            throw new InventoryDecodeException("inventory stream is truncated", exception);
        } catch (IOException exception) {
            throw new InventoryDecodeException("inventory stream is malformed", exception);
        }
    }

    /**
     * 使用原版 ItemStack Codec 生成裸 NBT, DataVersion 与压缩由库存信封统一承担.
     *
     * @param item 待编码的物品
     * @return 物品的裸 NBT 字节
     * @throws UncheckedIOException 当 NBT 写出失败时
     */
    private static byte @NotNull [] serializeItem(@NotNull ItemStack item) {
        // 编码物品为 NBT 标签.
        Object tag = ItemStackProxy.CODEC.encodeStart(REGISTRY_OPS, ItemUtils.getItemStackHandle(item)).getOrThrow();
        // 写出 NBT 为裸字节.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            NbtIoProxy.INSTANCE.write(tag, output);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to write item NBT", exception);
        }
        return buffer.toByteArray();
    }

    /**
     * 按槽位图读取物品区, 物品长度必须落在当前物品区的剩余范围内.
     *
     * @param region 解压后的物品区字节
     * @param mask 非空槽位图
     * @param size 槽数
     * @param dataVersion 流写出时的 Minecraft DataVersion
     * @return 按槽号排列的物品数组, 空槽为 null
     * @throws InventoryDecodeException 当物品区内容畸形时
     * @throws IOException 当物品区读取失败时
     */
    private static @Nullable ItemStack @NotNull [] deserializeItems(byte[] region, byte[] mask, int size, int dataVersion) throws IOException {
        DataInputStream items = new DataInputStream(new ByteArrayInputStream(region));
        @Nullable ItemStack[] slots = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            // 跳过位图标记为空的槽位.
            if ((mask[slot >> 3] & (1 << (slot & 7))) == 0) {
                continue;
            }
            // 校验声明长度, 不得超过物品区剩余字节, 避免让输入直接决定内存分配.
            int length = VirtualInventoryCodec.readVarInt(items);
            int remaining = items.available();
            if (length < 1 || length > remaining) {
                throw new InventoryDecodeException("slot " + slot + " declares " + length + " NBT bytes, but only " + remaining + " remain");
            }
            // 读取该槽的裸 NBT.
            byte[] nbt = new byte[length];
            items.readFully(nbt);

            // 解码物品, 无法识别的内容统一归为解码失败.
            ItemStack decoded;
            try {
                decoded = VirtualInventoryCodec.deserializeItem(nbt, dataVersion);
            } catch (InventoryDecodeException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new InventoryDecodeException("slot " + slot + " holds an item that could not be decoded", exception);
            }

            // 校验解码结果, 位图声明的非空槽不得解出空物品.
            if (ItemUtils.isNullOrEmpty(decoded)) {
                throw new InventoryDecodeException("slot " + slot + " is masked as filled but decoded to an empty item");
            }
            slots[slot] = decoded;
        }
        // 校验物品区末尾, 最后一个声明槽之后不得有尾随字节.
        if (items.read() != -1) {
            throw new InventoryDecodeException("item region has trailing bytes after the last masked slot");
        }
        return slots;
    }

    /**
     * 读取单个裸 NBT, 必要时通过 DataFixerUpper 升级后再交给原版 ItemStack Codec.
     *
     * @param nbt 单个物品的裸 NBT 字节
     * @param dataVersion 流写出时的 Minecraft DataVersion, 低于当前版本时触发升级
     * @return 解码出的物品
     * @throws InventoryDecodeException 当 NBT 根标签后存在尾随字节时
     * @throws UncheckedIOException 当 NBT 读取失败时
     */
    @NotNull
    private static ItemStack deserializeItem(byte @NotNull [] nbt, int dataVersion) {
        // 读取裸 NBT, 根标签之后不得有尾随字节.
        Object tag;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(nbt))) {
            tag = NbtIoProxy.INSTANCE.read(input, NbtAccounterProxy.INSTANCE.unlimitedHeap());
            if (input.read() != -1) {
                throw new InventoryDecodeException("item NBT has trailing bytes after the root tag");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read item NBT", exception);
        }

        // 升级旧版本数据, 使其与当前 DataVersion 一致.
        if (dataVersion < VersionHelper.WORLD_VERSION) {
            Dynamic<Object> outdated = new Dynamic<>(NbtOpsProxy.INSTANCE.getINSTANCE(), tag);
            tag = DataFixersProxy.INSTANCE.getDataFixer()
                    .update(ReferencesProxy.INSTANCE.getITEM_STACK(), outdated, dataVersion, VersionHelper.WORLD_VERSION)
                    .getValue();
        }

        // 解析为原版物品, 并包装为 Bukkit 物品.
        Object decoded = ItemStackProxy.INSTANCE.getCODEC()
                .parse(REGISTRY_OPS, tag)
                .getOrThrow();
        return CraftItemStackProxy.INSTANCE.asCraftMirror(decoded);
    }

    /**
     * 尾字节里超出 size 的填充位必须为零, 否则流声明了不存在的槽位.
     *
     * @param mask 非空槽位图
     * @param size 槽数
     * @throws InventoryDecodeException 当填充位非零时
     */
    private static void requireCleanMaskPadding(byte[] mask, int size) {
        int usedBits = size & 7;
        if (usedBits == 0) return;
        int padding = (mask[mask.length - 1] & 0xFF) >>> usedBits;
        if (padding != 0) {
            throw new InventoryDecodeException("mask sets bits beyond the declared slot count " + size);
        }
    }

    /**
     * NMS VarInt 只接受 ByteBuf, 保留流式实现可避免在 GZIP 流内引入临时缓冲和复制.
     *
     * @param output 目标输出
     * @param value 待写入的非负整数
     * @throws IOException 当流写出失败时
     */
    private static void writeVarInt(DataOutput output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    /**
     * 五字节封顶, 更长的续位串不是合法 int.
     *
     * @param input 源输入
     * @return 读取到的整数
     * @throws InventoryDecodeException 当 varint 超过 5 字节时
     * @throws IOException 当流读取失败时
     */
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
