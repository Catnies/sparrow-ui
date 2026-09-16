package net.momirealms.sparrow.ui.internal.map;

import net.momirealms.sparrow.ui.proxy.BukkitProxy;
import net.momirealms.sparrow.ui.proxy.minecraft.world.level.material.MapColorProxy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MapColorProxyTest {

    @Test
    void invokesStaticPackedColorMethod() {
        BukkitProxy.init("1.21.8", List.of("paper"));

        assertEquals(0xFF0000FF, MapColorProxy.INSTANCE.getColorFromPackedId(255));
    }
}
