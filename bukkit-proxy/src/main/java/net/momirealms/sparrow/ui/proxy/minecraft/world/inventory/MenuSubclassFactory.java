package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.nyana.reflection.NyanaReflection;
import net.nyana.reflection.remapper.Remapper;
import org.bukkit.inventory.InventoryView;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.ICONST_1;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V21;

/**
 * 为 SparrowUI 菜单生成受控的 {@code AbstractContainerMenu} 隐藏子类.
 *
 * <p>生成类与 NMS 菜单位于同一 package 和 ClassLoader, 但只通过 {@link State}
 * 访问 Core 持有的光标和 Bukkit 视图. 这样 Core 不需要链接任何 NMS 类型, 同时保留
 * 原菜单代理禁止原版广播和点击副作用的语义.</p>
 */
public final class MenuSubclassFactory {
    private MenuSubclassFactory() {
    }

    /**
     * 创建一个安装到 {@code ServerPlayer.containerMenu} 的 NMS 菜单对象.
     *
     * @param menuType NMS {@code MenuType<?>}
     * @param containerId 当前菜单容器编号
     * @param state 菜单与 Core 共享的最小状态
     * @return NMS {@code AbstractContainerMenu}
     */
    public static Object create(Object menuType, int containerId, State state) {
        try {
            return (Object) RuntimeCache.CONSTRUCTOR.invokeExact(menuType, containerId, state);
        } catch (RuntimeException | Error throwable) {
            throw throwable;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to instantiate the generated menu subclass", throwable);
        }
    }

    /**
     * 解析当前运行时的 NMS 菜单形状, 生成隐藏子类并返回其归一化构造器.
     */
    private static MethodHandle generateConstructor(
            Remapper remapper,
            Class<?> menuClass,
            Class<?> menuTypeClass,
            Class<?> itemStackClass,
            Class<?> playerClass
    ) {
        try {
            if (menuClass.getClassLoader() != MenuSubclassFactory.class.getClassLoader()) {
                throw new IllegalStateException("Menu generator uses " + MenuSubclassFactory.class.getClassLoader() + ", but AbstractContainerMenu uses " + menuClass.getClassLoader());
            }

            Constructor<?> parentConstructor = menuClass.getDeclaredConstructor(menuTypeClass, int.class);
            MenuMethods methods = MenuSubclassFactory.resolveMethods(
                    remapper,
                    menuClass,
                    itemStackClass,
                    playerClass
            );

            // hidden class 必须和 lookup host 同包, NESTMATE 则允许沿用目标侧的访问能力
            byte[] bytecode = MenuSubclassFactory.writeClass(menuClass, menuTypeClass, parentConstructor, methods);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(menuClass, NyanaReflection.getLookup());
            MethodHandles.Lookup hiddenLookup = lookup.defineHiddenClass(
                    bytecode,
                    true,
                    MethodHandles.Lookup.ClassOption.NESTMATE
            );

            MethodHandle generatedConstructor = hiddenLookup.findConstructor(
                    hiddenLookup.lookupClass(),
                    MethodType.methodType(void.class, menuTypeClass, int.class, State.class)
            );
            return generatedConstructor.asType(MethodType.methodType(
                    Object.class,
                    Object.class,
                    int.class,
                    State.class
            ));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to generate the AbstractContainerMenu subclass", exception);
        }
    }

    /**
     * 把固定的菜单代理语义写成 Java 21 class file.
     */
    private static byte[] writeClass(
            Class<?> menuClass,
            Class<?> menuTypeClass,
            Constructor<?> parentConstructor,
            MenuMethods methods
    ) {
        String menuInternalName = Type.getInternalName(menuClass);
        String generatedInternalName = menuInternalName + "$SparrowMenu";
        String stateInternalName = Type.getInternalName(State.class);
        String stateDescriptor = Type.getDescriptor(State.class);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V21,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                generatedInternalName,
                null,
                menuInternalName,
                null
        );
        writer.visitField(ACC_PRIVATE | ACC_FINAL, "state", stateDescriptor, null, null).visitEnd();

        MenuSubclassFactory.writeConstructor(
                writer,
                generatedInternalName,
                menuInternalName,
                menuTypeClass,
                parentConstructor,
                stateDescriptor
        );
        MenuSubclassFactory.writeStateObjectGetter(
                writer,
                generatedInternalName,
                stateInternalName,
                stateDescriptor,
                methods.getCarried(),
                "carried"
        );
        MenuSubclassFactory.writeSetCarried(
                writer,
                generatedInternalName,
                stateInternalName,
                stateDescriptor,
                methods.setCarried()
        );
        MenuSubclassFactory.writeNoOp(writer, methods.broadcastCarriedItem());
        MenuSubclassFactory.writeNoOp(writer, methods.broadcastChanges());
        MenuSubclassFactory.writeNoOp(writer, methods.broadcastFullState());
        MenuSubclassFactory.writeViewGetter(
                writer,
                generatedInternalName,
                stateInternalName,
                stateDescriptor,
                methods.getBukkitView()
        );
        MenuSubclassFactory.writeStateObjectGetter(
                writer,
                generatedInternalName,
                stateInternalName,
                stateDescriptor,
                methods.quickMoveStack(),
                "emptyItem"
        );
        MenuSubclassFactory.writeStillValid(writer, methods.stillValid());

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeConstructor(
            ClassWriter writer,
            String generatedInternalName,
            String menuInternalName,
            Class<?> menuTypeClass,
            Constructor<?> parentConstructor,
            String stateDescriptor
    ) {
        String descriptor = Type.getMethodDescriptor(
                Type.VOID_TYPE,
                Type.getType(menuTypeClass),
                Type.INT_TYPE,
                Type.getType(State.class)
        );
        MethodVisitor visitor = writer.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null);
        visitor.visitCode();

        visitor.visitVarInsn(ALOAD, 0);
        visitor.visitVarInsn(ALOAD, 1);
        visitor.visitVarInsn(ILOAD, 2);
        visitor.visitMethodInsn(
                INVOKESPECIAL,
                menuInternalName,
                "<init>",
                Type.getConstructorDescriptor(parentConstructor),
                false
        );

        visitor.visitVarInsn(ALOAD, 0);
        visitor.visitVarInsn(ALOAD, 3);
        visitor.visitFieldInsn(PUTFIELD, generatedInternalName, "state", stateDescriptor);
        visitor.visitInsn(RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void writeStateObjectGetter(
            ClassWriter writer,
            String generatedInternalName,
            String stateInternalName,
            String stateDescriptor,
            Method targetMethod,
            String stateMethod
    ) {
        MethodVisitor visitor = MenuSubclassFactory.beginOverride(writer, targetMethod);
        visitor.visitVarInsn(ALOAD, 0);
        visitor.visitFieldInsn(GETFIELD, generatedInternalName, "state", stateDescriptor);
        visitor.visitMethodInsn(
                INVOKEINTERFACE,
                stateInternalName,
                stateMethod,
                "()Ljava/lang/Object;",
                true
        );
        visitor.visitTypeInsn(CHECKCAST, Type.getInternalName(targetMethod.getReturnType()));
        visitor.visitInsn(ARETURN);
        MenuSubclassFactory.finishMethod(visitor);
    }

    private static void writeSetCarried(
            ClassWriter writer,
            String generatedInternalName,
            String stateInternalName,
            String stateDescriptor,
            Method targetMethod
    ) {
        MethodVisitor visitor = MenuSubclassFactory.beginOverride(writer, targetMethod);
        visitor.visitVarInsn(ALOAD, 0);
        visitor.visitFieldInsn(GETFIELD, generatedInternalName, "state", stateDescriptor);
        visitor.visitVarInsn(ALOAD, 1);
        visitor.visitMethodInsn(
                INVOKEINTERFACE,
                stateInternalName,
                "carried",
                "(Ljava/lang/Object;)V",
                true
        );
        visitor.visitInsn(RETURN);
        MenuSubclassFactory.finishMethod(visitor);
    }

    private static void writeNoOp(ClassWriter writer, Method targetMethod) {
        MethodVisitor visitor = MenuSubclassFactory.beginOverride(writer, targetMethod);
        visitor.visitInsn(RETURN);
        MenuSubclassFactory.finishMethod(visitor);
    }

    private static void writeViewGetter(
            ClassWriter writer,
            String generatedInternalName,
            String stateInternalName,
            String stateDescriptor,
            Method targetMethod
    ) {
        MethodVisitor visitor = MenuSubclassFactory.beginOverride(writer, targetMethod);
        visitor.visitVarInsn(ALOAD, 0);
        visitor.visitFieldInsn(GETFIELD, generatedInternalName, "state", stateDescriptor);
        visitor.visitMethodInsn(
                INVOKEINTERFACE,
                stateInternalName,
                "view",
                Type.getMethodDescriptor(Type.getType(InventoryView.class)),
                true
        );
        visitor.visitInsn(ARETURN);
        MenuSubclassFactory.finishMethod(visitor);
    }

    private static void writeStillValid(ClassWriter writer, Method targetMethod) {
        MethodVisitor visitor = MenuSubclassFactory.beginOverride(writer, targetMethod);
        visitor.visitInsn(ICONST_1);
        visitor.visitInsn(IRETURN);
        MenuSubclassFactory.finishMethod(visitor);
    }

    private static MethodVisitor beginOverride(ClassWriter writer, Method targetMethod) {
        MethodVisitor visitor = writer.visitMethod(
                ACC_PUBLIC,
                targetMethod.getName(),
                Type.getMethodDescriptor(targetMethod),
                null,
                null
        );
        visitor.visitCode();
        return visitor;
    }

    private static void finishMethod(MethodVisitor visitor) {
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static MenuMethods resolveMethods(
            Remapper remapper,
            Class<?> menuClass,
            Class<?> itemStackClass,
            Class<?> playerClass
    ) {
        Method getCarried = MenuSubclassFactory.requireMethod(remapper, menuClass, "getCarried");
        Method setCarried = MenuSubclassFactory.requireMethod(
                remapper,
                menuClass,
                "setCarried",
                itemStackClass
        );
        Method broadcastCarriedItem = MenuSubclassFactory.requireMethod(
                remapper,
                menuClass,
                "broadcastCarriedItem"
        );
        Method broadcastChanges = MenuSubclassFactory.requireMethod(remapper, menuClass, "broadcastChanges");
        Method broadcastFullState = MenuSubclassFactory.requireMethod(
                remapper,
                menuClass,
                "broadcastFullState"
        );
        Method getBukkitView = MenuSubclassFactory.requireMethod(remapper, menuClass, "getBukkitView");
        Method quickMoveStack = MenuSubclassFactory.requireMethod(
                remapper,
                menuClass,
                "quickMoveStack",
                playerClass,
                int.class
        );
        Method stillValid = MenuSubclassFactory.requireMethod(
                remapper,
                menuClass,
                "stillValid",
                playerClass
        );

        MenuSubclassFactory.requireReturnType(getCarried, itemStackClass);
        MenuSubclassFactory.requireReturnType(setCarried, void.class);
        MenuSubclassFactory.requireReturnType(broadcastCarriedItem, void.class);
        MenuSubclassFactory.requireReturnType(broadcastChanges, void.class);
        MenuSubclassFactory.requireReturnType(broadcastFullState, void.class);
        MenuSubclassFactory.requireReturnType(getBukkitView, InventoryView.class);
        MenuSubclassFactory.requireReturnType(quickMoveStack, itemStackClass);
        MenuSubclassFactory.requireReturnType(stillValid, boolean.class);
        return new MenuMethods(
                getCarried,
                setCarried,
                broadcastCarriedItem,
                broadcastChanges,
                broadcastFullState,
                getBukkitView,
                quickMoveStack,
                stillValid
        );
    }

    private static Method requireMethod(
            Remapper remapper,
            Class<?> owner,
            String sourceName,
            Class<?>... parameterTypes
    ) {
        String runtimeName = remapper.remapMethodName(owner, sourceName, parameterTypes);
        try {
            Method method = owner.getMethod(runtimeName, parameterTypes);
            if (Modifier.isFinal(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                throw new IllegalStateException("Menu method cannot be overridden: " + method);
            }
            return method;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(
                    "Missing menu method " + sourceName + " mapped to " + runtimeName + " on " + owner.getName(),
                    exception
            );
        }
    }

    private static void requireReturnType(Method method, Class<?> returnType) {
        if (method.getReturnType() != returnType) {
            throw new IllegalStateException(
                    "Unexpected return type for menu method " + method + ", expected " + returnType.getName()
            );
        }
    }

    private static Class<?> requireClass(ClassLoader classLoader, Remapper remapper, String sourceName) {
        String runtimeName = remapper.remapClassName(sourceName);
        try {
            return Class.forName(runtimeName, false, classLoader);
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IllegalStateException(
                    "Missing NMS class " + sourceName + " mapped to " + runtimeName + " in " + classLoader,
                    exception
            );
        }
    }

    /**
     * 生成菜单与 Core 菜单句柄之间的最小共享状态.
     */
    public interface State {

        /**
         * @return 当前 NMS {@code ItemStack} 光标
         */
        Object carried();

        /**
         * 接受 NMS 菜单提交的新光标.
         *
         * @param item NMS {@code ItemStack}
         */
        void carried(Object item);

        /**
         * @return 提供给 Bukkit 事件系统的窗口视图
         */
        InventoryView view();

        /**
         * @return NMS {@code ItemStack.EMPTY}
         */
        Object emptyItem();
    }

    /**
     * 缓存当前 Minecraft ClassLoader 的类型解析结果和生成构造器.
     *
     * <p>JVM 类初始化负责并发安全发布. 首次菜单创建完成解析与字节码生成后,
     * 后续创建只读取不可变的构造器句柄, 不再执行类查找、成员查找或 volatile 分支.</p>
     */
    private static final class RuntimeCache {
        private static final ClassLoader CLASS_LOADER = MenuSubclassFactory.class.getClassLoader();
        private static final Remapper REMAPPER = NyanaReflection.getRemapper();
        private static final Class<?> MENU_CLASS = MenuSubclassFactory.requireClass(
                CLASS_LOADER,
                REMAPPER,
                "net.minecraft.world.inventory.AbstractContainerMenu"
        );
        private static final Class<?> MENU_TYPE_CLASS = MenuSubclassFactory.requireClass(
                CLASS_LOADER,
                REMAPPER,
                "net.minecraft.world.inventory.MenuType"
        );
        private static final Class<?> ITEM_STACK_CLASS = MenuSubclassFactory.requireClass(
                CLASS_LOADER,
                REMAPPER,
                "net.minecraft.world.item.ItemStack"
        );
        private static final Class<?> PLAYER_CLASS = MenuSubclassFactory.requireClass(
                CLASS_LOADER,
                REMAPPER,
                "net.minecraft.world.entity.player.Player"
        );
        private static final MethodHandle CONSTRUCTOR = MenuSubclassFactory.generateConstructor(
                REMAPPER,
                MENU_CLASS,
                MENU_TYPE_CLASS,
                ITEM_STACK_CLASS,
                PLAYER_CLASS
        ); // 归一化为 (Object, int, State)Object

        private RuntimeCache() {
        }
    }

    private record MenuMethods(
            Method getCarried,
            Method setCarried,
            Method broadcastCarriedItem,
            Method broadcastChanges,
            Method broadcastFullState,
            Method getBukkitView,
            Method quickMoveStack,
            Method stillValid
    ) {
    }
}
