package net.minecraft.world.item;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ItemLike;

public final class ItemStack {

    public static final ItemStack EMPTY = new ItemStack((org.bukkit.inventory.ItemStack) null);
    public static final Codec<Object> CODEC = null;
    private static final java.util.IdentityHashMap<org.bukkit.inventory.ItemStack, ItemStack> WRAPPERS = new java.util.IdentityHashMap<>();
    private final org.bukkit.inventory.ItemStack bukkit;
    private final java.util.IdentityHashMap<DataComponentType, Object> components;
    public ItemStack(ItemLike item) {
        this.bukkit = null;
        this.components = new java.util.IdentityHashMap<>();
    }

    private ItemStack(org.bukkit.inventory.ItemStack bukkit) {
        this.bukkit = bukkit;
        this.components = new java.util.IdentityHashMap<>();
    }

    private ItemStack(ItemStack source) {
        this.bukkit = source.bukkit == null ? null : source.bukkit.clone();
        this.components = new java.util.IdentityHashMap<>(source.components);
    }

    public static synchronized ItemStack wrap(org.bukkit.inventory.ItemStack bukkit) {
        return WRAPPERS.computeIfAbsent(bukkit, ItemStack::new);
    }

    public org.bukkit.inventory.ItemStack getBukkitStack() {
        return this.bukkit;
    }

    public boolean isEmpty() {
        return this.bukkit == null || this.bukkit.isEmpty();
    }

    public boolean is(TagKey tag) {
        if (tag != ItemTags.BUNDLES || this.bukkit == null) {
            return false;
        }
        String material = this.bukkit.getType().name();
        return material.equals("BUNDLE") || material.endsWith("_BUNDLE");
    }

    public static boolean matches(ItemStack a, ItemStack b) {
        if (a.bukkit == null || b.bukkit == null) {
            return a.bukkit == b.bukkit;
        }
        return a.bukkit.equals(b.bukkit);
    }

    public static boolean isSameItemSameComponents(ItemStack a, ItemStack b) {
        if (a.bukkit == null || b.bukkit == null) {
            return a.bukkit == b.bukkit;
        }
        return a.bukkit.isSimilar(b.bukkit);
    }

    public ItemStack copy() {
        return new ItemStack(this);
    }

    public Object getComponents() {
        throw new UnsupportedOperationException("测试假类未实现 getComponents");
    }

    public Object getItem() {
        throw new UnsupportedOperationException("测试假类未实现 getItem");
    }

    public Object getItemHolder() {
        throw new UnsupportedOperationException("测试假类未实现 getItemHolder");
    }

    public Object typeHolder() {
        throw new UnsupportedOperationException("测试假类未实现 typeHolder");
    }

    public ItemStack transmuteCopy(ItemLike item) {
        throw new UnsupportedOperationException("测试假类未实现 transmuteCopy");
    }

    public Object set(DataComponentType component, Object value) {
        return this.components.put(component, value);
    }

    public Object component(DataComponentType component) {
        return this.components.get(component);
    }
}
