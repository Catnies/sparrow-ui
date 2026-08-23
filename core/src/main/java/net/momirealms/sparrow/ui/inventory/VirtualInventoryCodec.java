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
 * {@link VirtualInventory} 的字节编解码实现.
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

    // 编码 UUID 与当前槽位快照.
    static byte @NotNull [] serialize(@NotNull VirtualInventory inventory) {
        UUID uuid = inventory.uuid();
        @Nullable ItemStack[] items = inventory.snapshot();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            // 信封保留身份, 版本和槽位图.
            DataOutputStream envelope = new DataOutputStream(buffer);
            envelope.writeLong(uuid.getMostSignificantBits());
            envelope.writeLong(uuid.getLeastSignificantBits());
            envelope.writeByte(FORMAT);
            envelope.writeInt(VersionHelper.WORLD_VERSION);
            envelope.writeInt(items.length);
            envelope.write(VirtualInventoryCodec.buildMask(items));
            envelope.flush();

            // 物品区整体压缩, 每个非空槽写长度和裸 NBT.
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

    // 完整校验信封与物品区后再构造 VirtualInventory.
    @NotNull
    static VirtualInventory deserialize(byte @NotNull [] bytes) {
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        DataInputStream envelope = new DataInputStream(source);
        try {
            UUID uuid = new UUID(envelope.readLong(), envelope.readLong());
            int format = envelope.readUnsignedByte();
            switch (format) {
                case 1 -> {
                    // 拒绝降级读取, 新组件在旧版本可能静默丢失.
                    int dataVersion = envelope.readInt();
                    if (dataVersion < 0 || dataVersion > VersionHelper.WORLD_VERSION)
                        throw new InventoryDecodeException("stream comes from a newer Minecraft or invalid data version (data version: " + dataVersion + ", current data version: " + VersionHelper.WORLD_VERSION + ")");
                    int size = envelope.readInt();
                    if (size < 0)
                        throw new InventoryDecodeException("declared slot count " + size + " is negative");
                    byte[] mask = new byte[Math.ceilDiv(size, 8)];
                    envelope.readFully(mask);
                    VirtualInventoryCodec.requireCleanMaskPadding(mask, size);
                    byte[] region;
                    try (GZIPInputStream compressed = new GZIPInputStream(source)) {
                        region = compressed.readAllBytes();
                    }
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

    // 原版 ItemStack Codec 只生成裸 NBT, 版本与压缩由信封承担.
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

    // 按槽位图读取解压后的物品区.
    private static @Nullable ItemStack @NotNull [] deserializeItems(byte[] region, byte[] mask, int size, int dataVersion) throws IOException {
        DataInputStream items = new DataInputStream(new ByteArrayInputStream(region));
        @Nullable ItemStack[] slots = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            if ((mask[slot >> 3] & (1 << (slot & 7))) == 0) {
                continue;
            }
            // 长度通过剩余区间校验后才用于分配.
            int length = VirtualInventoryCodec.readVarInt(items);
            int remaining = items.available();
            if (length < 1 || length > remaining) {
                throw new InventoryDecodeException("slot " + slot + " declares " + length + " NBT bytes, but only " + remaining + " remain");
            }
            byte[] nbt = new byte[length];
            items.readFully(nbt);
            ItemStack decoded;
            try {
                decoded = VirtualInventoryCodec.deserializeItem(nbt, dataVersion);
            } catch (InventoryDecodeException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new InventoryDecodeException("slot " + slot + " holds an item that could not be decoded", exception);
            }
            if (ItemUtils.isNullOrEmpty(decoded)) {
                throw new InventoryDecodeException("slot " + slot + " is masked as filled but decoded to an empty item");
            }
            slots[slot] = decoded;
        }
        if (items.read() != -1) {
            throw new InventoryDecodeException("item region has trailing bytes after the last masked slot");
        }
        return slots;
    }

    // 旧版 NBT 先经 DataFixerUpper 升级, 再交给当前 ItemStack Codec.
    @NotNull
    private static ItemStack deserializeItem(byte @NotNull [] nbt, int dataVersion) {
        Object tag;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(nbt))) {
            tag = NbtIoProxy.INSTANCE.read(input, NbtAccounterProxy.INSTANCE.unlimitedHeap());
            if (input.read() != -1) {
                throw new InventoryDecodeException("item NBT has trailing bytes after the root tag");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read item NBT", exception);
        }
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

    // 每字节从低位到高位对应连续八个槽位.
    private static byte @NotNull [] buildMask(@Nullable ItemStack @NotNull [] items) {
        byte[] mask = new byte[Math.ceilDiv(items.length, 8)];
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] != null) {
                mask[slot >> 3] |= (byte) (1 << (slot & 7));
            }
        }
        return mask;
    }

    // <strong>尾字节超出 size 的填充位必须为零</strong>.
    private static void requireCleanMaskPadding(byte[] mask, int size) {
        int usedBits = size & 7;
        if (usedBits == 0) return;
        int padding = (mask[mask.length - 1] & 0xFF) >>> usedBits;
        if (padding != 0) {
            throw new InventoryDecodeException("mask sets bits beyond the declared slot count " + size);
        }
    }

    // NMS VarInt 只接受 ByteBuf, 这里直接写入 GZIP 数据流.
    private static void writeVarInt(DataOutput output, int value) throws IOException {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            output.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        output.writeByte(remaining);
    }

    // int VarInt 最多占五字节.
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
