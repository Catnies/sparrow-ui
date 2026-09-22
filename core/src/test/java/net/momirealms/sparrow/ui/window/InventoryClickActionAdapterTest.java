package net.momirealms.sparrow.ui.window;

import net.momirealms.sparrow.ui.inventory.event.InventoryClickAction;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InventoryClickActionAdapterTest {
    @TempDir
    Path temporary;

    @Test
    void paperRetainsEveryPreciseAction() {
        for (InventoryClickAction action : InventoryClickAction.values()) {
            assertSame(InventoryAction.valueOf(action.name()), InventoryClickActionAdapter.toBukkit(action));
        }
    }

    @Test
    void missingBundleConstantsOnlyAffectTheBukkitEventMapping() throws Exception {
        String names = Arrays.stream(InventoryAction.values())
                .map(Enum::name).filter(name -> !name.contains("BUNDLE")).collect(Collectors.joining(","));
        Path source = this.temporary.resolve("InventoryAction.java");
        Files.writeString(source, "package org.bukkit.event.inventory; public enum InventoryAction {" + names + "}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", this.temporary.toString(), source.toString()));
        URL classes = InventoryClickActionAdapter.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{this.temporary.toUri().toURL(), classes}, this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(InventoryAction.class.getName()) && !name.equals(InventoryClickActionAdapter.class.getName())) {
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
            Class<?> adapter = loader.loadClass(InventoryClickActionAdapter.class.getName());
            var toBukkit = adapter.getDeclaredMethod("toBukkit", InventoryClickAction.class);
            toBukkit.setAccessible(true);
            for (InventoryClickAction action : InventoryClickAction.values()) {
                Enum<?> mapped = (Enum<?>) toBukkit.invoke(null, action);
                assertEquals(action.name().contains("BUNDLE") ? "UNKNOWN" : action.name(), mapped.name());
            }
        }
    }
}
