package net.momirealms.sparrow.ui.example;

import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.example.command.SparrowCommand;
import net.momirealms.sparrow.ui.example.menu.shulkerboxedit.ShulkerBoxEditListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class SparrowExample extends JavaPlugin {
    public static SparrowExample INSTANCE;

    @Override
    public void onEnable() {
        INSTANCE = this;
        SparrowUI.getInstance().setUp(this);
        SparrowCommand.register();
        this.getServer().getPluginManager().registerEvents(new ShulkerBoxEditListener(), this);
    }
}
