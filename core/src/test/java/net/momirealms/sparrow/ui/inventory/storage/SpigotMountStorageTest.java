package net.momirealms.sparrow.ui.inventory.storage;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.momirealms.sparrow.ui.inventory.ReferencingInventory;
import net.momirealms.sparrow.ui.proxy.MinecraftPredicate;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorseProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EntityEquipmentProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.entity.EquipmentSlotProxy;
import net.momirealms.sparrow.ui.util.ReflectionUtils;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftInventoryAbstractHorse;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SpigotMountStorageTest {
    @BeforeEach
    void setUp() throws Throwable {
        MockBukkit.mock();
        Class<?> reflection = Class.forName("net.momirealms.sparrow.reflection.SReflection");
        var setPredicate = reflection.getMethod("setActivePredicate", Predicate.class);
        Object previous = reflection.getMethod("getFilter").invoke(null);
        setPredicate.invoke(null, new MinecraftPredicate("26.2", List.of("spigot")));
        try {
            var create = Class.forName("net.momirealms.sparrow.reflection.proxy.ASMProxyFactory").getMethod("create", Class.class);
            for (Class<?> type : List.of(CraftInventoryAbstractHorseProxy.class, EntityEquipmentProxy.class, EquipmentSlotProxy.class)) {
                Class.forName(type.getName(), true, type.getClassLoader());
                ReflectionUtils.unreflectSetter(type.getField("INSTANCE")).invoke(create.invoke(null, type));
            }
        } finally {
            setPredicate.invoke(null, previous);
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void fullMountIncludesEquipmentEvenWithoutStorageSlots() {
        Fixture fixture = new Fixture(0);
        ReferencingInventory inventory = ReferencingInventory.fromMountContents(fixture.mount);
        assertEquals(2, inventory.size());
        inventory.setItem(0, new ItemStack(Material.SADDLE));
        inventory.setItem(1, new ItemStack(Material.DIAMOND_HORSE_ARMOR));
        assertEquals(Material.SADDLE, fixture.equipment.get(EquipmentSlot.SADDLE).getBukkitStack().getType());
        assertEquals(Material.DIAMOND_HORSE_ARMOR, fixture.equipment.get(EquipmentSlot.BODY).getBukkitStack().getType());
        inventory.setItem(1, new ItemStack(Material.WHITE_CARPET));
        assertEquals(Material.WHITE_CARPET, inventory.itemAt(1).getType());
        inventory.setItem(0, null);
        assertNull(inventory.itemAt(0));
    }

    @Test
    void storageViewsShareMainSlotIdentityWithoutAliasingEquipment() {
        Fixture fixture = new Fixture(15);
        ReferencingInventory full = ReferencingInventory.fromMountContents(fixture.mount);
        ReferencingInventory second = ReferencingInventory.fromMountContents(fixture.mount);
        ReferencingInventory contents = ReferencingInventory.fromContents(fixture.inventory);
        assertEquals(17, full.size());
        assertEquals(15, contents.size());
        full.setItem(2, new ItemStack(Material.DIAMOND, 7));
        assertEquals(new ItemStack(Material.DIAMOND, 7), contents.itemAt(0));
        assertEquals(full.physicalKey(2), contents.physicalKey(0));
        assertEquals(full.physicalKey(0), second.physicalKey(0));
        assertNotEquals(full.physicalKey(0), contents.physicalKey(0));
        fixture.alive.set(false);
        full.refresh();
        assertTrue(full.retired());
        assertNull(full.itemAt(2));
        assertThrows(IllegalStateException.class, () -> full.setItem(0, new ItemStack(Material.SADDLE)));
    }

    private static final class Fixture {
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final EntityEquipment equipment = new EntityEquipment();
        private final AbstractHorse mount;
        private CraftInventoryAbstractHorse inventory;

        private Fixture(int slots) {
            UUID id = UUID.randomUUID();
            this.mount = (AbstractHorse) Proxy.newProxyInstance(AbstractHorse.class.getClassLoader(), new Class<?>[]{AbstractHorse.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getInventory" -> this.inventory;
                case "isValid" -> this.alive.get();
                case "hashCode" -> id.hashCode();
                case "equals" -> proxy == arguments[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
            this.inventory = new CraftInventoryAbstractHorse(new SimpleContainer(slots, this.mount), this.equipment);
        }
    }
}
