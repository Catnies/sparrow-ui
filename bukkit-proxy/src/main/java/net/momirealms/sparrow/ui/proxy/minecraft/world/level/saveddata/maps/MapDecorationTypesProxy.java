package net.momirealms.sparrow.ui.proxy.minecraft.world.level.saveddata.maps;

import net.nyana.reflection.proxy.ASMProxyFactory;
import net.nyana.reflection.proxy.annotation.FieldGetter;
import net.nyana.reflection.proxy.annotation.ReflectionProxy;

@ReflectionProxy(name = "net.minecraft.world.level.saveddata.maps.MapDecorationTypes")
public interface MapDecorationTypesProxy {
    MapDecorationTypesProxy INSTANCE = ASMProxyFactory.create(MapDecorationTypesProxy.class);
    Object PLAYER = INSTANCE.PLAYER();
    Object FRAME = INSTANCE.FRAME();
    Object RED_MARKER = INSTANCE.RED_MARKER();
    Object BLUE_MARKER = INSTANCE.BLUE_MARKER();
    Object TARGET_X = INSTANCE.TARGET_X();
    Object TARGET_POINT = INSTANCE.TARGET_POINT();
    Object PLAYER_OFF_MAP = INSTANCE.PLAYER_OFF_MAP();
    Object PLAYER_OFF_LIMITS = INSTANCE.PLAYER_OFF_LIMITS();
    Object WOODLAND_MANSION = INSTANCE.WOODLAND_MANSION();
    Object JUNGLE_TEMPLE = INSTANCE.JUNGLE_TEMPLE();
    Object WHITE_BANNER = INSTANCE.WHITE_BANNER();
    Object ORANGE_BANNER = INSTANCE.ORANGE_BANNER();
    Object MAGENTA_BANNER = INSTANCE.MAGENTA_BANNER();
    Object LIGHT_BLUE_BANNER = INSTANCE.LIGHT_BLUE_BANNER();
    Object YELLOW_BANNER = INSTANCE.YELLOW_BANNER();
    Object LIME_BANNER = INSTANCE.LIME_BANNER();
    Object PINK_BANNER = INSTANCE.PINK_BANNER();
    Object GRAY_BANNER = INSTANCE.GRAY_BANNER();
    Object LIGHT_GRAY_BANNER = INSTANCE.LIGHT_GRAY_BANNER();
    Object CYAN_BANNER = INSTANCE.CYAN_BANNER();
    Object PURPLE_BANNER = INSTANCE.PURPLE_BANNER();
    Object BLUE_BANNER = INSTANCE.BLUE_BANNER();
    Object BROWN_BANNER = INSTANCE.BROWN_BANNER();
    Object GREEN_BANNER = INSTANCE.GREEN_BANNER();
    Object RED_BANNER = INSTANCE.RED_BANNER();
    Object BLACK_BANNER = INSTANCE.BLACK_BANNER();
    Object RED_X = INSTANCE.RED_X();

    @FieldGetter(name = "PLAYER", isStatic = true)
    Object PLAYER();

    @FieldGetter(name = "FRAME", isStatic = true)
    Object FRAME();

    @FieldGetter(name = "RED_MARKER", isStatic = true)
    Object RED_MARKER();

    @FieldGetter(name = "BLUE_MARKER", isStatic = true)
    Object BLUE_MARKER();

    @FieldGetter(name = "TARGET_X", isStatic = true)
    Object TARGET_X();

    @FieldGetter(name = "TARGET_POINT", isStatic = true)
    Object TARGET_POINT();

    @FieldGetter(name = "PLAYER_OFF_MAP", isStatic = true)
    Object PLAYER_OFF_MAP();

    @FieldGetter(name = "PLAYER_OFF_LIMITS", isStatic = true)
    Object PLAYER_OFF_LIMITS();

    @FieldGetter(name = "WOODLAND_MANSION", isStatic = true)
    Object WOODLAND_MANSION();

    @FieldGetter(name = "JUNGLE_TEMPLE", isStatic = true)
    Object JUNGLE_TEMPLE();

    @FieldGetter(name = "WHITE_BANNER", isStatic = true)
    Object WHITE_BANNER();

    @FieldGetter(name = "ORANGE_BANNER", isStatic = true)
    Object ORANGE_BANNER();

    @FieldGetter(name = "MAGENTA_BANNER", isStatic = true)
    Object MAGENTA_BANNER();

    @FieldGetter(name = "LIGHT_BLUE_BANNER", isStatic = true)
    Object LIGHT_BLUE_BANNER();

    @FieldGetter(name = "YELLOW_BANNER", isStatic = true)
    Object YELLOW_BANNER();

    @FieldGetter(name = "LIME_BANNER", isStatic = true)
    Object LIME_BANNER();

    @FieldGetter(name = "PINK_BANNER", isStatic = true)
    Object PINK_BANNER();

    @FieldGetter(name = "GRAY_BANNER", isStatic = true)
    Object GRAY_BANNER();

    @FieldGetter(name = "LIGHT_GRAY_BANNER", isStatic = true)
    Object LIGHT_GRAY_BANNER();

    @FieldGetter(name = "CYAN_BANNER", isStatic = true)
    Object CYAN_BANNER();

    @FieldGetter(name = "PURPLE_BANNER", isStatic = true)
    Object PURPLE_BANNER();

    @FieldGetter(name = "BLUE_BANNER", isStatic = true)
    Object BLUE_BANNER();

    @FieldGetter(name = "BROWN_BANNER", isStatic = true)
    Object BROWN_BANNER();

    @FieldGetter(name = "GREEN_BANNER", isStatic = true)
    Object GREEN_BANNER();

    @FieldGetter(name = "RED_BANNER", isStatic = true)
    Object RED_BANNER();

    @FieldGetter(name = "BLACK_BANNER", isStatic = true)
    Object BLACK_BANNER();

    @FieldGetter(name = "RED_X", isStatic = true)
    Object RED_X();
}
