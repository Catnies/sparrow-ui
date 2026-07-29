package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.remapper.Remapper;
import org.bukkit.inventory.InventoryView;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static org.objectweb.asm.Opcodes.*;

/**
 * 使用 ASM 生成的 {@code AbstractContainerMenu} 隐藏子类.
 */
public final class MenuSubclassFactory {
    private static final MethodHandle CONSTRUCTOR = MenuSubclassFactory.linkConstructor();

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
            return (Object) CONSTRUCTOR.invokeExact(menuType, containerId, state);
        } catch (RuntimeException | Error throwable) {
            throw throwable;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to instantiate the generated menu subclass", throwable);
        }
    }

    /**
     * 生成菜单和插件菜单的共享接口.
     */
    public interface State {

        Object carried();

        void carried(Object item);

        InventoryView view();

        Object emptyItem();
    }

    /**
     * 解析当前 NMS 形状, 定义隐藏子类并链接其构造器.
     */
    private static MethodHandle linkConstructor() {
        try {
            Remapper remapper = SReflection.getRemapper();
            Class<?> menuClass = MenuSubclassFactory.loadRuntimeClass(remapper, "net.minecraft.world.inventory.AbstractContainerMenu");
            Class<?> menuTypeClass = MenuSubclassFactory.loadRuntimeClass(remapper, "net.minecraft.world.inventory.MenuType");
            Class<?> itemStackClass = MenuSubclassFactory.loadRuntimeClass(remapper, "net.minecraft.world.item.ItemStack");
            Class<?> playerClass = MenuSubclassFactory.loadRuntimeClass(remapper, "net.minecraft.world.entity.player.Player");
            MenuMethods methods = MenuSubclassFactory.resolveMethods(remapper, menuClass, itemStackClass, playerClass);

            byte[] bytecode = new MenuClassWriter(menuClass).write(menuTypeClass, methods);
            MethodHandles.Lookup hiddenLookup = MethodHandles.privateLookupIn(menuClass, SReflection.getLookup())
                    .defineHiddenClass(bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            MethodHandle constructor = hiddenLookup.findConstructor(
                    hiddenLookup.lookupClass(),
                    MethodType.methodType(void.class, menuTypeClass, int.class, State.class)
            );
            return constructor.asType(MethodType.methodType(Object.class, Object.class, int.class, State.class));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to generate the AbstractContainerMenu subclass", exception);
        }
    }

    private static Class<?> loadRuntimeClass(Remapper remapper, String sourceName) throws ClassNotFoundException {
        String runtimeName = remapper.remapClassName(sourceName);
        return Class.forName(runtimeName, false, MenuSubclassFactory.class.getClassLoader());
    }

    private static MenuMethods resolveMethods(Remapper remapper, Class<?> menuClass, Class<?> itemStackClass, Class<?> playerClass) throws NoSuchMethodException {
        return new MenuMethods(
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "getCarried"),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "setCarried", itemStackClass),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "broadcastCarriedItem"),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "broadcastChanges"),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "broadcastFullState"),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "getBukkitView"),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "quickMoveStack", playerClass, int.class),
                MenuSubclassFactory.resolveMethod(remapper, menuClass, "stillValid", playerClass)
        );
    }

    private static Method resolveMethod(Remapper remapper, Class<?> owner, String sourceName, Class<?>... parameterTypes) throws NoSuchMethodException {
        String runtimeName = remapper.remapMethodName(owner, sourceName, parameterTypes);
        return owner.getMethod(runtimeName, parameterTypes);
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

    /**
     * 把菜单代理语义集中写入一个 Java 21 隐藏类.
     */
    private static final class MenuClassWriter {
        private static final String STATE_FIELD = "state";
        private static final String STATE_INTERNAL_NAME = Type.getInternalName(State.class);
        private static final String STATE_DESCRIPTOR = Type.getDescriptor(State.class);
        private static final String STATE_OBJECT_GETTER = Type.getMethodDescriptor(Type.getType(Object.class));
        private static final String STATE_OBJECT_SETTER = Type.getMethodDescriptor(
                Type.VOID_TYPE,
                Type.getType(Object.class)
        );
        private static final String STATE_VIEW_GETTER = Type.getMethodDescriptor(Type.getType(InventoryView.class));

        private final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        private final String menuInternalName;
        private final String generatedInternalName;

        private MenuClassWriter(Class<?> menuClass) {
            this.menuInternalName = Type.getInternalName(menuClass);
            this.generatedInternalName = this.menuInternalName + "$SparrowMenu";
        }

        private byte[] write(Class<?> menuTypeClass, MenuMethods methods) {
            this.writer.visit(
                    V21,
                    ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                    this.generatedInternalName,
                    null,
                    this.menuInternalName,
                    null
            );
            this.writer.visitField(ACC_PRIVATE | ACC_FINAL, STATE_FIELD, STATE_DESCRIPTOR, null, null).visitEnd();

            this.writeConstructor(menuTypeClass);
            this.writeStateObjectResult(methods.getCarried(), "carried");
            this.writeSetCarried(methods.setCarried());
            this.writeNoOp(methods.broadcastCarriedItem());
            this.writeNoOp(methods.broadcastChanges());
            this.writeNoOp(methods.broadcastFullState());
            this.writeViewGetter(methods.getBukkitView());
            this.writeStateObjectResult(methods.quickMoveStack(), "emptyItem");
            this.writeStillValid(methods.stillValid());

            this.writer.visitEnd();
            return this.writer.toByteArray();
        }

        private void writeConstructor(Class<?> menuTypeClass) {
            String parentDescriptor = Type.getMethodDescriptor(
                    Type.VOID_TYPE,
                    Type.getType(menuTypeClass),
                    Type.INT_TYPE
            );
            String descriptor = Type.getMethodDescriptor(
                    Type.VOID_TYPE,
                    Type.getType(menuTypeClass),
                    Type.INT_TYPE,
                    Type.getType(State.class)
            );
            MethodVisitor visitor = this.writer.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null);
            visitor.visitCode();

            visitor.visitVarInsn(ALOAD, 0);
            visitor.visitVarInsn(ALOAD, 1);
            visitor.visitVarInsn(ILOAD, 2);
            visitor.visitMethodInsn(INVOKESPECIAL, this.menuInternalName, "<init>", parentDescriptor, false);

            visitor.visitVarInsn(ALOAD, 0);
            visitor.visitVarInsn(ALOAD, 3);
            visitor.visitFieldInsn(PUTFIELD, this.generatedInternalName, STATE_FIELD, STATE_DESCRIPTOR);
            visitor.visitInsn(RETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeStateObjectResult(Method targetMethod, String stateMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            this.loadState(visitor);
            visitor.visitMethodInsn(
                    INVOKEINTERFACE,
                    STATE_INTERNAL_NAME,
                    stateMethod,
                    STATE_OBJECT_GETTER,
                    true
            );
            visitor.visitTypeInsn(CHECKCAST, Type.getInternalName(targetMethod.getReturnType()));
            visitor.visitInsn(ARETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeSetCarried(Method targetMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            this.loadState(visitor);
            visitor.visitVarInsn(ALOAD, 1);
            visitor.visitMethodInsn(
                    INVOKEINTERFACE,
                    STATE_INTERNAL_NAME,
                    "carried",
                    STATE_OBJECT_SETTER,
                    true
            );
            visitor.visitInsn(RETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeNoOp(Method targetMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            visitor.visitInsn(RETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeViewGetter(Method targetMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            this.loadState(visitor);
            visitor.visitMethodInsn(
                    INVOKEINTERFACE,
                    STATE_INTERNAL_NAME,
                    "view",
                    STATE_VIEW_GETTER,
                    true
            );
            visitor.visitInsn(ARETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeStillValid(Method targetMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            visitor.visitInsn(ICONST_1);
            visitor.visitInsn(IRETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void loadState(MethodVisitor visitor) {
            visitor.visitVarInsn(ALOAD, 0);
            visitor.visitFieldInsn(GETFIELD, this.generatedInternalName, STATE_FIELD, STATE_DESCRIPTOR);
        }

        private MethodVisitor beginOverride(Method targetMethod) {
            MethodVisitor visitor = this.writer.visitMethod(
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
    }
}
