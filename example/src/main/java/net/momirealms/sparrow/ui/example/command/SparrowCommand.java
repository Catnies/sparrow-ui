package net.momirealms.sparrow.ui.example.command;

import net.momirealms.sparrow.ui.example.SparrowExample;
import net.momirealms.sparrow.ui.example.menu.animationpresets.AnimationPresetsCommand;
import net.momirealms.sparrow.ui.example.menu.anvilprompt.AnvilPromptCommand;
import net.momirealms.sparrow.ui.example.menu.cartographygallery.CartographyGalleryCommand;
import net.momirealms.sparrow.ui.example.menu.customframes.CustomFramesCommand;
import net.momirealms.sparrow.ui.example.menu.livesearch.LiveSearchCommand;
import net.momirealms.sparrow.ui.example.menu.skilltree.SkillTreeCommand;
import net.momirealms.sparrow.ui.example.menu.stoneappraisal.StoneAppraisalCommand;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.bukkit.CloudBukkitCapabilities;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.LegacyPaperCommandManager;
import org.incendo.cloud.setting.ManagerSetting;

public final class SparrowCommand {
    private SparrowCommand() {
    }

    /**
     * 按平台能力接入 Cloud, 注册全部示例菜单.
     */
    public static void register() {
        LegacyPaperCommandManager<CommandSender> manager = new LegacyPaperCommandManager<>(
                SparrowExample.INSTANCE, ExecutionCoordinator.simpleCoordinator(), SenderMapper.identity());
        manager.settings().set(ManagerSetting.ALLOW_UNSAFE_REGISTRATION, true);
        if (manager.hasCapability(CloudBukkitCapabilities.NATIVE_BRIGADIER)) {
            manager.registerBrigadier();
            manager.brigadierManager().setNativeNumberSuggestions(true);
        } else if (manager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            manager.registerAsynchronousCompletions();
        }
        LiveSearchCommand.register(manager);
        AnvilPromptCommand.register(manager);
        SkillTreeCommand.register(manager);
        CartographyGalleryCommand.register(manager);
        AnimationPresetsCommand.register(manager);
        StoneAppraisalCommand.register(manager);
        CustomFramesCommand.register(manager);
    }
}