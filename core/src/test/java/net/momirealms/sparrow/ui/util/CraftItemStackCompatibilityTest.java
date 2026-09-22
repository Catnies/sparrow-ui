package net.momirealms.sparrow.ui.util;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.PlayerConnectionTestSupport;
import net.momirealms.sparrow.ui.proxy.MinecraftPredicate;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftItemStackProxy;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftItemStackCompatibilityTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        BukkitProxy.init("1.21.8", List.of("paper"));
        CraftItemStack.copyCalls = 0;
        CraftItemStack.unwrapCalls = 0;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spigotBorrowsTheExistingHandleWithoutCopying() throws Exception {
        CraftItemStackProxy proxy = proxyFor(List.of("spigot"));
        net.minecraft.world.item.ItemStack handle = net.minecraft.world.item.ItemStack.wrap(new ItemStack(Material.DIAMOND, 3));
        CraftItemStack craft = new CraftItemStack(handle);

        for (int index = 0; index < 100; index++) {
            assertSame(handle, proxy.unwrap(craft));
        }
        assertSame(net.minecraft.world.item.ItemStack.EMPTY, proxy.unwrap(new CraftItemStack(null)));
        assertEquals(0, CraftItemStack.copyCalls);
        assertEquals(0, CraftItemStack.unwrapCalls);
    }

    @Test
    void spigotConvertsAnOrdinaryBukkitStackWithItsMetadata() throws Exception {
        CraftItemStackProxy proxy = proxyFor(List.of("spigot"));
        ItemStack source = new ItemStack(Material.DIAMOND, 3);
        ItemMeta meta = source.getItemMeta();
        meta.setDisplayName("conversion-source");
        source.setItemMeta(meta);

        net.minecraft.world.item.ItemStack handle = (net.minecraft.world.item.ItemStack) proxy.unwrap(source);
        ItemStack converted = handle.getBukkitStack();

        assertNotSame(source, converted);
        assertEquals(source, converted);
        source.setAmount(1);
        assertEquals(3, converted.getAmount());
        assertEquals("conversion-source", converted.getItemMeta().getDisplayName());
        assertEquals(1, CraftItemStack.copyCalls);
        assertEquals(0, CraftItemStack.unwrapCalls);
    }

    @Test
    void paperKeepsTheNativeUnwrapPathForOrdinaryStacks() throws Exception {
        assertNativeUnwrap(List.of("paper"));
    }

    @Test
    void foliaKeepsTheNativeUnwrapPathForOrdinaryStacks() throws Exception {
        assertNativeUnwrap(List.of("paper", "folia"));
    }

    @Test
    void spigotEmptyChecksBorrowCraftHandlesAndNeverCopyOrdinaryItems() throws Throwable {
        withPlatform(List.of("spigot"), items -> {
            net.minecraft.world.item.ItemStack handle = net.minecraft.world.item.ItemStack.wrap(new ItemStack(Material.DIAMOND, 3));
            CraftItemStack craft = new CraftItemStack(handle);
            assertFalse((boolean) items.getMethod("isEmpty", ItemStack.class).invoke(null, craft));
            assertSame(handle, items.getMethod("getItemStackHandle", ItemStack.class).invoke(null, craft));
            assertTrue((boolean) items.getMethod("isEmpty", ItemStack.class).invoke(null, new CraftItemStack(null)));

            ItemStack ordinary = new ItemStack(Material.DIAMOND, 3);
            assertFalse((boolean) items.getMethod("isEmpty", ItemStack.class).invoke(null, ordinary));
            ordinary.setAmount(0);
            assertTrue((boolean) items.getMethod("isEmpty", ItemStack.class).invoke(null, ordinary));
            assertSame(net.minecraft.world.item.ItemStack.EMPTY, items.getMethod("getItemStackHandle", ItemStack.class).invoke(null, ordinary));
            assertEquals(0, CraftItemStack.copyCalls);
            assertEquals(0, CraftItemStack.unwrapCalls);
        });
    }

    @Test
    void paperEmptyChecksUseNativeHandlesAndHandleAccessUnwrapsOnce() throws Throwable {
        withPlatform(List.of("paper"), items -> {
            ItemStack ordinary = new ItemStack(Material.DIAMOND, 3);
            Object handle = net.minecraft.world.item.ItemStack.wrap(ordinary);
            assertFalse((boolean) items.getMethod("isEmpty", ItemStack.class).invoke(null, ordinary));
            assertEquals(1, CraftItemStack.unwrapCalls);
            CraftItemStack.unwrapCalls = 0;
            assertSame(handle, items.getMethod("getItemStackHandle", ItemStack.class).invoke(null, ordinary));
            assertEquals(1, CraftItemStack.unwrapCalls);
            ordinary.setAmount(0);
            assertTrue((boolean) items.getMethod("isEmpty", ItemStack.class).invoke(null, ordinary));
            assertSame(net.minecraft.world.item.ItemStack.EMPTY, items.getMethod("getItemStackHandle", ItemStack.class).invoke(null, ordinary));
            assertEquals(0, CraftItemStack.copyCalls);
        });
    }

    @Test
    void paperDropOwnsACopyAndKeepsTheSourceUntouched() throws Throwable {
        assertDropCopy(List.of("paper"), 0);
    }

    @Test
    void spigotDropConvertsAnOrdinaryStackOnce() throws Throwable {
        assertDropCopy(List.of("spigot"), 1);
    }

    private static void assertDropCopy(List<String> patches, int conversions) throws Throwable {
        PlayerConnectionTestSupport.install();
        withPlatform(patches, items -> {
            AtomicReference<ItemStack> dropped = new AtomicReference<>();
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                if (!method.getName().equals("dropItem")) {
                    throw new AssertionError(method.getName());
                }
                dropped.set((ItemStack) arguments[0]);
                return null;
            });
            ItemStack source = new ItemStack(Material.DIAMOND, 3);
            ItemMeta meta = source.getItemMeta();
            meta.setDisplayName("drop-source");
            source.setItemMeta(meta);
            Class<?> players = items.getClassLoader().loadClass(PlayerUtils.class.getName());
            players.getMethod("dropItem", Player.class, ItemStack.class).invoke(null, player, source);
            assertNotSame(source, dropped.get());
            assertEquals(source, dropped.get());
            source.setAmount(1);
            assertEquals(3, dropped.get().getAmount());
            assertEquals("drop-source", dropped.get().getItemMeta().getDisplayName());
            assertEquals(conversions, CraftItemStack.copyCalls);
        });
    }

    private static void withPlatform(List<String> patches, PlatformCheck action) throws Throwable {
        CraftItemStackProxy previousProxy = CraftItemStackProxy.INSTANCE;
        var proxySetter = ReflectionUtils.unreflectSetter(CraftItemStackProxy.class.getField("INSTANCE"));
        // 每个平台独立加载工具类, 平台常量在首次调用前固定.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{ItemUtils.class.getProtectionDomain().getCodeSource().getLocation()}, ItemUtils.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(ItemUtils.class.getName()) && !name.equals(VersionHelper.class.getName()) && !name.equals(PlayerUtils.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = this.findLoadedClass(name);
                if (loaded == null) {
                    loaded = this.findClass(name);
                }
                if (resolve) {
                    this.resolveClass(loaded);
                }
                return loaded;
            }
        }) {
            proxySetter.invoke(proxyFor(patches));
            Class<?> version = Class.forName(VersionHelper.class.getName(), true, loader);
            var paperSetter = ReflectionUtils.unreflectSetter(version.getField("hasPaperPatch"));
            paperSetter.invoke(patches.contains("paper"));
            action.run(loader.loadClass(ItemUtils.class.getName()));
        } finally {
            proxySetter.invoke(previousProxy);
        }
    }

    private interface PlatformCheck {
        void run(Class<?> items) throws Exception;
    }

    private static void assertNativeUnwrap(List<String> patches) throws Exception {
        CraftItemStackProxy proxy = proxyFor(patches);
        ItemStack source = new ItemStack(Material.DIAMOND, 3);
        Object expected = net.minecraft.world.item.ItemStack.wrap(source);

        assertSame(expected, proxy.unwrap(source));
        assertEquals(0, CraftItemStack.copyCalls);
        assertEquals(1, CraftItemStack.unwrapCalls);
    }

    private static CraftItemStackProxy proxyFor(List<String> patches) throws Exception {
        Class<?> reflection = Class.forName("net.momirealms.sparrow.reflection.SReflection");
        Method setPredicate = reflection.getMethod("setActivePredicate", Predicate.class);
        Object previous = reflection.getMethod("getFilter").invoke(null);
        setPredicate.invoke(null, new MinecraftPredicate("26.2", patches));
        try {
            Class<?> factory = Class.forName("net.momirealms.sparrow.reflection.proxy.ASMProxyFactory");
            return (CraftItemStackProxy) factory.getMethod("create", Class.class).invoke(null, CraftItemStackProxy.class);
        } finally {
            setPredicate.invoke(null, previous);
        }
    }
}
