package net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory;

import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * 创建原生 CraftInventory, 并为插件侧的 NMS Container handler 提供运行时方法映射.
 */
public final class CraftInventoryFactory {
    private static final Class<?> CONTAINER_CLASS = CraftInventoryFactory.loadRuntimeClass("net.minecraft.world.Container");
    private static final Map<Method, ContainerOperation> OPERATIONS = CraftInventoryFactory.linkOperations();

    private CraftInventoryFactory() {
    }

    public static Inventory create(InvocationHandler handler) {
        Object container = Proxy.newProxyInstance(
                CONTAINER_CLASS.getClassLoader(),
                new Class<?>[]{CONTAINER_CLASS},
                handler
        );
        return CraftInventoryProxy.INSTANCE.newInstance(container);
    }

    public static ContainerOperation operation(Method method) {
        return OPERATIONS.get(method);
    }

    private static Map<Method, ContainerOperation> linkOperations() {
        try {
            Remapper remapper = SReflection.getRemapper();
            Class<?> itemStackClass = CraftInventoryFactory.loadRuntimeClass("net.minecraft.world.item.ItemStack");
            Class<?> playerClass = CraftInventoryFactory.loadRuntimeClass("net.minecraft.world.entity.player.Player");
            Class<?> clearableClass = CraftInventoryFactory.loadRuntimeClass("net.minecraft.world.Clearable");
            Class<?> craftHumanEntityClass = CraftInventoryFactory.loadRuntimeClass("org.bukkit.craftbukkit.entity.CraftHumanEntity");
            HashMap<Method, ContainerOperation> operations = new HashMap<>();
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getContainerSize", ContainerOperation.GET_CONTAINER_SIZE);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "isEmpty", ContainerOperation.IS_EMPTY);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getItem", ContainerOperation.GET_ITEM, int.class);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "removeItem", ContainerOperation.REMOVE_ITEM, int.class, int.class);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "removeItemNoUpdate", ContainerOperation.REMOVE_ITEM_NO_UPDATE, int.class);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "setItem", ContainerOperation.SET_ITEM, int.class, itemStackClass);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getMaxStackSize", ContainerOperation.GET_MAX_STACK_SIZE);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "setChanged", ContainerOperation.SET_CHANGED);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "stillValid", ContainerOperation.STILL_VALID, playerClass);
            CraftInventoryFactory.register(operations, remapper, clearableClass, "clearContent", ContainerOperation.CLEAR_CONTENT);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getContents", ContainerOperation.GET_CONTENTS);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "onOpen", ContainerOperation.ON_OPEN, craftHumanEntityClass);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "onClose", ContainerOperation.ON_CLOSE, craftHumanEntityClass);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getViewers", ContainerOperation.GET_VIEWERS);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getOwner", ContainerOperation.GET_OWNER);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "setMaxStackSize", ContainerOperation.SET_MAX_STACK_SIZE, int.class);
            CraftInventoryFactory.register(operations, remapper, CONTAINER_CLASS, "getLocation", ContainerOperation.GET_LOCATION);
            return Map.copyOf(operations);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to link the Inventory Container proxy", exception);
        }
    }

    private static Class<?> loadRuntimeClass(String sourceName) {
        try {
            String runtimeName = SReflection.getRemapper().remapClassName(sourceName);
            return Class.forName(runtimeName, false, CraftInventoryFactory.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to load runtime class " + sourceName, exception);
        }
    }

    private static void register(
            Map<Method, ContainerOperation> operations,
            Remapper remapper,
            Class<?> owner,
            String sourceName,
            ContainerOperation operation,
            Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        String runtimeName = remapper.remapMethodName(owner, sourceName, parameterTypes);
        operations.put(owner.getMethod(runtimeName, parameterTypes), operation);
    }

    public enum ContainerOperation {
        GET_CONTAINER_SIZE,
        IS_EMPTY,
        GET_ITEM,
        REMOVE_ITEM,
        REMOVE_ITEM_NO_UPDATE,
        SET_ITEM,
        GET_MAX_STACK_SIZE,
        SET_CHANGED,
        STILL_VALID,
        CLEAR_CONTENT,
        GET_CONTENTS,
        ON_OPEN,
        ON_CLOSE,
        GET_VIEWERS,
        GET_OWNER,
        SET_MAX_STACK_SIZE,
        GET_LOCATION
    }

    public static Object toNms(ItemStack item) {
        return item == null || item.isEmpty() ? ItemStackProxy.EMPTY : CraftItemStackProxy.INSTANCE.unwrap(item);
    }

    public static ItemStack toBukkit(Object item) {
        ItemStack converted = CraftItemStackProxy.INSTANCE.asCraftMirror(item);
        return converted.isEmpty() ? null : converted;
    }
}
