package net.momirealms.sparrow.ui.example;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.plugin.java.JavaPlugin;

public final class SparrowExample extends JavaPlugin {
    public static SparrowExample INSTANCE;

    @Override
    public void onEnable() {
        INSTANCE = this;
        SparrowUI.getInstance().setUp(this);
    }
}
