package net.momirealms.sparrow.ui.item;

import net.momirealms.sparrow.ui.Observer;
import net.momirealms.sparrow.ui.WindowStub;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import java.lang.reflect.Proxy;
import java.util.UUID;

public final class AttachSupport {

    private AttachSupport() {
    }

    public static ItemAttachment attach(Item item, Observer<? super Item> observer) {
        Player viewer = player();
        return item.attach(window(viewer), observer);
    }

    public static Window window(Player viewer) {
        return new WindowStub(viewer);
    }

    public static Player player() {
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                AttachSupport.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> arguments != null && arguments.length == 1 && arguments[0] == proxy;
                    case "toString" -> "AttachSupportPlayer";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
