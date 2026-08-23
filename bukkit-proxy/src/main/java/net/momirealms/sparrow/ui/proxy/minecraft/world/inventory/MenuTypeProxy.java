package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.reflection.proxy.ASMProxyFactory;
import net.momirealms.sparrow.reflection.proxy.annotation.FieldGetter;
import net.momirealms.sparrow.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.inventory.MenuType")
public interface MenuTypeProxy {
    MenuTypeProxy INSTANCE = ASMProxyFactory.create(MenuTypeProxy.class);
    Object GENERIC_9x1 = INSTANCE.GENERIC_9x1();
    Object GENERIC_9x2 = INSTANCE.GENERIC_9x2();
    Object GENERIC_9x3 = INSTANCE.GENERIC_9x3();
    Object GENERIC_9x4 = INSTANCE.GENERIC_9x4();
    Object GENERIC_9x5 = INSTANCE.GENERIC_9x5();
    Object GENERIC_9x6 = INSTANCE.GENERIC_9x6();
    Object HOPPER = INSTANCE.HOPPER();
    Object ANVIL = INSTANCE.ANVIL();
    Object GENERIC_3x3 = INSTANCE.GENERIC_3x3();
    Object GRINDSTONE = INSTANCE.GRINDSTONE();
    Object SMITHING = INSTANCE.SMITHING();
    Object BREWING_STAND = INSTANCE.BREWING_STAND();
    Object CARTOGRAPHY_TABLE = INSTANCE.CARTOGRAPHY_TABLE();
    Object CRAFTER_3x3 = INSTANCE.CRAFTER_3x3();
    Object CRAFTING = INSTANCE.CRAFTING();
    Object FURNACE = INSTANCE.FURNACE();
    Object SMOKER = INSTANCE.SMOKER();
    Object BLAST_FURNACE = INSTANCE.BLAST_FURNACE();
    Object ENCHANTMENT = INSTANCE.ENCHANTMENT();
    Object STONECUTTER = INSTANCE.STONECUTTER();
    Object MERCHANT = INSTANCE.MERCHANT();

    @FieldGetter(name = "GENERIC_9x1", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_9x1();

    @FieldGetter(name = "GENERIC_9x2", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_9x2();

    @FieldGetter(name = "GENERIC_9x3", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_9x3();

    @FieldGetter(name = "GENERIC_9x4", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_9x4();

    @FieldGetter(name = "GENERIC_9x5", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_9x5();

    @FieldGetter(name = "GENERIC_9x6", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_9x6();

    @FieldGetter(name = "HOPPER", isStatic = true, activeIf = "min_version=1.20.1")
    Object HOPPER();

    @FieldGetter(name = "ANVIL", isStatic = true, activeIf = "min_version=1.20.1")
    Object ANVIL();

    @FieldGetter(name = "GENERIC_3x3", isStatic = true, activeIf = "min_version=1.20.1")
    Object GENERIC_3x3();

    @FieldGetter(name = "GRINDSTONE", isStatic = true, activeIf = "min_version=1.20.1")
    Object GRINDSTONE();

    @FieldGetter(name = "SMITHING", isStatic = true, activeIf = "min_version=1.20.1")
    Object SMITHING();

    @FieldGetter(name = "BREWING_STAND", isStatic = true, activeIf = "min_version=1.20.1")
    Object BREWING_STAND();

    @FieldGetter(name = "CARTOGRAPHY_TABLE", isStatic = true, activeIf = "min_version=1.20.1")
    Object CARTOGRAPHY_TABLE();

    @FieldGetter(name = "CRAFTER_3x3", isStatic = true, activeIf = "min_version=1.20.3")
    Object CRAFTER_3x3();

    @FieldGetter(name = "CRAFTING", isStatic = true, activeIf = "min_version=1.20.1")
    Object CRAFTING();

    @FieldGetter(name = "FURNACE", isStatic = true, activeIf = "min_version=1.20.1")
    Object FURNACE();

    @FieldGetter(name = "SMOKER", isStatic = true, activeIf = "min_version=1.20.1")
    Object SMOKER();

    @FieldGetter(name = "BLAST_FURNACE", isStatic = true, activeIf = "min_version=1.20.1")
    Object BLAST_FURNACE();

    @FieldGetter(name = "ENCHANTMENT", isStatic = true, activeIf = "min_version=1.20.1")
    Object ENCHANTMENT();

    @FieldGetter(name = "STONECUTTER", isStatic = true, activeIf = "min_version=1.20.1")
    Object STONECUTTER();

    @FieldGetter(name = "MERCHANT", isStatic = true, activeIf = "min_version=1.20.1")
    Object MERCHANT();
}
