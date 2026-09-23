package net.momirealms.sparrow.ui.state.internal.player;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;

public final class PlayerSignalTestRuntime {
    private static Plugin previousPlugin;
    private static PlayerSignalRuntime previousRuntime;
    private static PlayerSignalRuntime runtime;
    private PlayerSignalTestRuntime() {
    }

    public static synchronized void install() {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        previousPlugin = swap("plugin", plugin);
        runtime = new PlayerSignalRuntime(plugin);
        runtime.initialize();
        previousRuntime = swap("playerSignals", runtime);
    }

    public static synchronized void restore() {
        runtime.close();
        swap("playerSignals", previousRuntime);
        swap("plugin", previousPlugin);
        previousRuntime = null;
        previousPlugin = null;
        runtime = null;
        MockBukkit.unmock();
    }

    static PlayerSignalRuntime runtime() {
        return runtime;
    }

    @SuppressWarnings("unchecked")
    private static <T> T swap(String name, T value) {
        try {
            Field field = SparrowUI.class.getDeclaredField(name);
            field.setAccessible(true);
            T previous = (T) field.get(SparrowUI.getInstance());
            field.set(SparrowUI.getInstance(), value);
            return previous;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to swap SparrowUI " + name, exception);
        }
    }
}
