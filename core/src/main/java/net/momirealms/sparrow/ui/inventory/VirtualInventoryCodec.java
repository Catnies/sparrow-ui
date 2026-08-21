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

    // 把 VirtualInventory 这一刻的槽内容编成字节数组.
    static byte @NotNull [] serialize(@NotNull VirtualInventory inventory) {
        UUID uuid = inventory.uuid();
        @Nullable ItemStack[] items = inventory.snapshot();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // 明文段保留身份, 版本和槽位图, 解码时可先完成尺寸校验
            DataOutputStream envelope = new DataOutputStream(buffer);
            envelope.writeLong(uuid.getMostSignificantBits());
            envelope.writeLong(uuid.getLeastSignificantBits());
            envelope.writeByte(FORMAT);
            envelope.writeInt(VersionHelper.WORLD_VERSION);
            envelope.writeInt(items.length);
            envelope.write(VirtualInventoryCodec.buildMask(items));
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

    // 从字节数组恢复 VirtualInventory, 任何格式畸形都在造出对象之前就拒掉.
    // 这里不限制物品区的解压体积和 NBT 堆用量, 输入来源与资源边界归调用方管.
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

    // 走原版 ItemStack Codec 生成裸 NBT, DataVersion 与压缩交给外面那层信封统一承担, 这里一件也不管.
    private static byte @NotNull [] serializeItem(@NotNull ItemStack item) {
        Object tag = ItemStackProxy.CODEC.encodeStart(REGISTRY_OPS, ItemUtils.getItemStackHandle(item)).getOrThrow();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            NbtIoProxy.INSTANCE.write(tag, output);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to write item NBT", exception);
        }
        return buffer.toByteArray();
    }

    // 按槽位图逐个读出物品区里的物品, region 是解压后的物品区字节.
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
            byte[] nbt = new byte[length];
            items.readFully(nbt);
            // 无法识别的内容一律归为解码失败, 不放半成品出去
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

    // 读一件物品的裸 NBT, 版本旧的先过 DataFixerUpper 升上来, 再交给原版 ItemStack Codec.
    @NotNull
    private static ItemStack deserializeItem(byte @NotNull [] nbt, int dataVersion) {
        // 根标签之后不得有尾随字节, 有就说明这段数据不干净
        Object tag;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(nbt))) {
            tag = NbtIoProxy.INSTANCE.read(input, NbtAccounterProxy.INSTANCE.unlimitedHeap());
            if (input.read() != -1) {
                throw new InventoryDecodeException("item NBT has trailing bytes after the root tag");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read item NBT", exception);
        }
        // 把旧版本数据升到当前 DataVersion, 之后 Codec 面对的永远是当版格式
        if (dataVersion < VersionHelper.WORLD_VERSION) {
            Dynamic<Object> outdated = new Dynamic<>(NbtOpsProxy.INSTANCE.getINSTANCE(), tag);
            tag = DataFixersProxy.INSTANCE.getDataFixer()
                    .update(ReferencesProxy.INSTANCE.getITEM_STACK(), outdated, dataVersion, VersionHelper.WORLD_VERSION)
                    .getValue();
        }
        Object decoded = ItemStackProxy.INSTANCE.getCODEC()
                .parse(REGISTRY_OPS, tag)
                .getOrThrow();
        return CraftItemStackProxy.INSTANCE.asCraftMirror(decoded);
    }

    // 按槽位空不空建位图, 每个字节从低位到高位对应连续八个槽位.
    private static byte @NotNull [] buildMask(@Nullable ItemStack @NotNull [] items) {
        byte[] mask = new byte[Math.ceilDiv(items.length, 8)];
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] != null) {
                mask[slot >> 3] |= (byte) (1 << (slot & 7));
            }
        }
        return mask;
    }

    // 尾字节里超出 size 的填充位必须是零, 不然这条流就在声明一些根本不存在的槽位.
    private static void requireCleanMaskPadding(byte[] mask, int size) {
        int usedBits = size & 7;
        if (usedBits == 0) return;
        int padding = (mask[mask.length - 1] & 0xFF) >>> usedBits;
        if (padding != 0) {
            throw new InventoryDecodeException("mask sets bits beyond the declared slot count " + size);
        }
    }

    // 自己实现 VarInt 而不是借 NMS 的: NMS 那套只吃 ByteBuf, 拿来用就得在 GZIP 流里多一层临时缓冲和复制.
    private static void writeVarInt(DataOutput output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    // 五字节封顶, 更长的续位串已经不是合法 int 了.
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
