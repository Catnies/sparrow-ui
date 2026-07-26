package net.momirealms.sparrow.ui.inventory.codec;

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
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 取道原版 {@code ItemStack.CODEC} 与 DataFixerUpper 的物品编解码.
 * <p>选它而不是 Bukkit 的 {@code serializeAsBytes()}: 后者给每个物品各写一份
 * DataVersion 再各套一层 GZIP, 在整库存场景里是纯冗余. 这里只产出裸 NBT,
 * DataVersion 与压缩由信封统一承担.
 * <p>本类是整个 inventory 子系统里唯一触碰 NMS 的地方, 需要运行中的服务端与
 * 注册表访问 —— 首次调用才会解析代理, 纯 JUnit 环境不加载它.
 * <p>注册表访问每次调用现取: 服务端重载会换掉注册表实例, 缓存会读到失效引用.
 */
final class NmsItemCodec implements ItemCodec {
    static final NmsItemCodec INSTANCE = new NmsItemCodec();

    private NmsItemCodec() {
    }

    // getUnsafe 带着 UnsafeValues 整体的弃用标记, 但这是 Bukkit 取 DataVersion 的唯一
    // 公开入口, 且它的实现就是 CraftMagicNumbers 那条 SharedConstants 链, 故意用它
    @Override
    public int currentDataVersion() {
        return Bukkit.getUnsafe().getDataVersion();
    }

    @Override
    public byte @NotNull [] encodeItem(@NotNull ItemStack item) {
        Object tag = ItemStackProxy.INSTANCE.getCODEC()
                .encodeStart(NmsItemCodec.registryOps(), ItemUtils.getItemStackHandle(item))
                .getOrThrow();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            NbtIoProxy.INSTANCE.write(tag, output);
        } catch (IOException exception) {
            // 目标是内存缓冲, 走到这里只可能是 NBT 写出本身失败
            throw new UncheckedIOException("failed to write item NBT", exception);
        }
        return buffer.toByteArray();
    }

    @Override
    @NotNull
    public ItemStack decodeItem(byte @NotNull [] nbt, int dataVersion, long heapQuota) {
        Object tag;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(nbt))) {
            // 计量器是真正的分配防线: 声明超大数组的畸形 NBT 在分配前就被拒绝
            tag = NbtIoProxy.INSTANCE.read(input, NbtAccounterProxy.INSTANCE.create(heapQuota));

            // 根标签必须恰好吃尽整段字节: 有剩余说明声明长度与真实 NBT 不符
            if (input.read() != -1) {
                throw new InventoryDecodeException("item NBT has trailing bytes after the root tag");
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read item NBT", exception);
        }

        // 升级链跑在裸 NbtOps 上: DataFixer 只认标签结构, 不需要注册表
        int current = this.currentDataVersion();
        if (dataVersion < current) {
            Dynamic<Object> outdated = new Dynamic<>(NbtOpsProxy.INSTANCE.getINSTANCE(), tag);
            tag = DataFixersProxy.INSTANCE.getDataFixer()
                    .update(ReferencesProxy.INSTANCE.getITEM_STACK(), outdated, dataVersion, current)
                    .getValue();
        }

        Object decoded = ItemStackProxy.INSTANCE.getCODEC()
                .parse(NmsItemCodec.registryOps(), tag)
                .getOrThrow();
        return CraftItemStackProxy.INSTANCE.asCraftMirror(decoded);
    }

    // 物品组件里含注册表引用(附魔, 药水效果等), 裸 NbtOps 解不动它们
    private static DynamicOps<Object> registryOps() {
        return HolderLookupProviderProxy.INSTANCE.createSerializationContext(
                CraftRegistryProxy.INSTANCE.getMinecraftRegistry(),
                NbtOpsProxy.INSTANCE.getINSTANCE()
        );
    }
}
