package net.momirealms.sparrow.ui.state.internal.keyed;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;

final class PlayerSignalTestRuntime {

    private static Plugin previousPlugin;
    private PlayerSignalTestRuntime() {
    }

    static synchronized void install() {
        MockBukkit.mock();
        previousPlugin = swapPlugin(MockBukkit.createMockPlugin());
    }

    static synchronized void restore() {
        swapPlugin(previousPlugin);
        previousPlugin = null;
        MockBukkit.unmock();
    }

    private static Plugin swapPlugin(Plugin plugin) {
        try {
            Field field = SparrowUI.class.getDeclaredField("plugin");
            field.setAccessible(true);
            Plugin previous = (Plugin) field.get(SparrowUI.getInstance());
            field.set(SparrowUI.getInstance(), plugin);
            return previous;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to swap the SparrowUI plugin", exception);
        }
    }
}
