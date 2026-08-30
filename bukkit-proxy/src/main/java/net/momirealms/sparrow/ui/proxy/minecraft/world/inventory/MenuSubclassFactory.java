package net.momirealms.sparrow.ui.proxy.minecraft.world.inventory;

import net.momirealms.sparrow.reflection.SReflection;
import net.momirealms.sparrow.reflection.clazz.SparrowClass;
import org.bukkit.inventory.InventoryView;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mAllOf;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mNamed;
import static net.momirealms.sparrow.reflection.method.matcher.MethodMatchers.mTakeArguments;
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
            Class<?> menuClass = MenuSubclassFactory.loadRuntimeClass("net.minecraft.world.inventory.AbstractContainerMenu");
            Class<?> menuTypeClass = MenuSubclassFactory.loadRuntimeClass("net.minecraft.world.inventory.MenuType");
            Class<?> itemStackClass = MenuSubclassFactory.loadRuntimeClass("net.minecraft.world.item.ItemStack");
            Class<?> playerClass = MenuSubclassFactory.loadRuntimeClass("net.minecraft.world.entity.player.Player");
            MenuMethods methods = MenuSubclassFactory.resolveMethods(menuClass, itemStackClass, playerClass);

            byte[] bytecode = new MenuClassWriter(menuClass).write(menuTypeClass, methods);
            MethodHandles.Lookup hiddenLookup = MethodHandles.privateLookupIn(menuClass, SReflection.getLookup())
                    .defineHiddenClass(bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE);
            MenuSubclassFactory.linkStateHandles(hiddenLookup);
            MethodHandle constructor = hiddenLookup.findConstructor(
                    hiddenLookup.lookupClass(),
                    MethodType.methodType(void.class, menuTypeClass, int.class, Object.class)
            );
            return constructor.asType(MethodType.methodType(Object.class, Object.class, int.class, State.class));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to generate the AbstractContainerMenu subclass", exception);
        }
    }

    // NMS 隐藏类只保存 Object, 通过这些句柄回调插件类加载器中的 State.
    private static void linkStateHandles(MethodHandles.Lookup hiddenLookup) throws ReflectiveOperationException {
        MethodHandles.Lookup stateLookup = MethodHandles.lookup();
        MethodType objectGetter = MethodType.methodType(Object.class);
        MethodType objectSetter = MethodType.methodType(void.class, Object.class);
        MethodType viewGetter = MethodType.methodType(InventoryView.class);
        MethodType bridgedGetter = MethodType.methodType(Object.class, Object.class);
        MethodType bridgedSetter = MethodType.methodType(void.class, Object.class, Object.class);

        MenuSubclassFactory.setStateHandle(
                hiddenLookup,
                MenuClassWriter.CARRIED_GETTER_FIELD,
                stateLookup.findVirtual(State.class, "carried", objectGetter).asType(bridgedGetter)
        );
        MenuSubclassFactory.setStateHandle(
                hiddenLookup,
                MenuClassWriter.CARRIED_SETTER_FIELD,
                stateLookup.findVirtual(State.class, "carried", objectSetter).asType(bridgedSetter)
        );
        MenuSubclassFactory.setStateHandle(
                hiddenLookup,
                MenuClassWriter.VIEW_GETTER_FIELD,
                stateLookup.findVirtual(State.class, "view", viewGetter).asType(bridgedGetter)
        );
        MenuSubclassFactory.setStateHandle(
                hiddenLookup,
                MenuClassWriter.EMPTY_ITEM_GETTER_FIELD,
                stateLookup.findVirtual(State.class, "emptyItem", objectGetter).asType(bridgedGetter)
        );
    }

    private static void setStateHandle(MethodHandles.Lookup hiddenLookup, String fieldName, MethodHandle handle) throws ReflectiveOperationException {
        hiddenLookup.findStaticVarHandle(hiddenLookup.lookupClass(), fieldName, MethodHandle.class).set(handle);
    }

    private static Class<?> loadRuntimeClass(String sourceName) throws ClassNotFoundException {
        ClassLoader classLoader = MenuSubclassFactory.class.getClassLoader();
        Class<?> runtimeClass = SparrowClass.find(false, classLoader, sourceName);
        if (runtimeClass == null) {
            throw new ClassNotFoundException(sourceName);
        }
        return runtimeClass;
    }

    private static MenuMethods resolveMethods(Class<?> menuClass, Class<?> itemStackClass, Class<?> playerClass) throws NoSuchMethodException {
        return new MenuMethods(
                MenuSubclassFactory.resolveMethod(menuClass, "getCarried"),
                MenuSubclassFactory.resolveMethod(menuClass, "setCarried", itemStackClass),
                MenuSubclassFactory.resolveMethod(menuClass, "broadcastCarriedItem"),
                MenuSubclassFactory.resolveMethod(menuClass, "broadcastChanges"),
                MenuSubclassFactory.resolveMethod(menuClass, "broadcastFullState"),
                MenuSubclassFactory.resolveMethod(menuClass, "getBukkitView"),
                MenuSubclassFactory.resolveMethod(menuClass, "quickMoveStack", playerClass, int.class),
                MenuSubclassFactory.resolveMethod(menuClass, "stillValid", playerClass)
        );
    }

    private static Method resolveMethod(Class<?> owner, String sourceName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = SparrowClass.of(owner).getMethod(mAllOf(mNamed(sourceName), mTakeArguments(parameterTypes)));
        if (method == null) {
            throw new NoSuchMethodException(owner.getName() + "#" + sourceName);
        }
        return method;
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
        private static final String CARRIED_GETTER_FIELD = "carriedGetter";
        private static final String CARRIED_SETTER_FIELD = "carriedSetter";
        private static final String VIEW_GETTER_FIELD = "viewGetter";
        private static final String EMPTY_ITEM_GETTER_FIELD = "emptyItemGetter";
        private static final String METHOD_HANDLE_INTERNAL_NAME = Type.getInternalName(MethodHandle.class);
        private static final String METHOD_HANDLE_DESCRIPTOR = Type.getDescriptor(MethodHandle.class);
        private static final String HANDLE_OBJECT_GETTER = Type.getMethodDescriptor(
                Type.getType(Object.class),
                Type.getType(Object.class)
        );
        private static final String HANDLE_OBJECT_SETTER = Type.getMethodDescriptor(
                Type.VOID_TYPE,
                Type.getType(Object.class),
                Type.getType(Object.class)
        );

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
            this.writer.visitField(ACC_PRIVATE | ACC_FINAL, STATE_FIELD, Type.getDescriptor(Object.class), null, null).visitEnd();
            this.writer.visitField(ACC_PRIVATE | ACC_STATIC, CARRIED_GETTER_FIELD, METHOD_HANDLE_DESCRIPTOR, null, null).visitEnd();
            this.writer.visitField(ACC_PRIVATE | ACC_STATIC, CARRIED_SETTER_FIELD, METHOD_HANDLE_DESCRIPTOR, null, null).visitEnd();
            this.writer.visitField(ACC_PRIVATE | ACC_STATIC, VIEW_GETTER_FIELD, METHOD_HANDLE_DESCRIPTOR, null, null).visitEnd();
            this.writer.visitField(ACC_PRIVATE | ACC_STATIC, EMPTY_ITEM_GETTER_FIELD, METHOD_HANDLE_DESCRIPTOR, null, null).visitEnd();

            this.writeConstructor(menuTypeClass);
            this.writeStateObjectResult(methods.getCarried(), CARRIED_GETTER_FIELD);
            this.writeSetCarried(methods.setCarried());
            this.writeNoOp(methods.broadcastCarriedItem());
            this.writeNoOp(methods.broadcastChanges());
            this.writeNoOp(methods.broadcastFullState());
            this.writeStateObjectResult(methods.getBukkitView(), VIEW_GETTER_FIELD);
            this.writeStateObjectResult(methods.quickMoveStack(), EMPTY_ITEM_GETTER_FIELD);
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
                    Type.getType(Object.class)
            );
            MethodVisitor visitor = this.writer.visitMethod(ACC_PUBLIC, "<init>", descriptor, null, null);
            visitor.visitCode();

            visitor.visitVarInsn(ALOAD, 0);
            visitor.visitVarInsn(ALOAD, 1);
            visitor.visitVarInsn(ILOAD, 2);
            visitor.visitMethodInsn(INVOKESPECIAL, this.menuInternalName, "<init>", parentDescriptor, false);

            visitor.visitVarInsn(ALOAD, 0);
            visitor.visitVarInsn(ALOAD, 3);
            visitor.visitFieldInsn(PUTFIELD, this.generatedInternalName, STATE_FIELD, Type.getDescriptor(Object.class));
            visitor.visitInsn(RETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeStateObjectResult(Method targetMethod, String handleField) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            visitor.visitFieldInsn(GETSTATIC, this.generatedInternalName, handleField, METHOD_HANDLE_DESCRIPTOR);
            this.loadState(visitor);
            visitor.visitMethodInsn(
                    INVOKEVIRTUAL,
                    METHOD_HANDLE_INTERNAL_NAME,
                    "invokeExact",
                    HANDLE_OBJECT_GETTER,
                    false
            );
            visitor.visitTypeInsn(CHECKCAST, Type.getInternalName(targetMethod.getReturnType()));
            visitor.visitInsn(ARETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeSetCarried(Method targetMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            visitor.visitFieldInsn(GETSTATIC, this.generatedInternalName, CARRIED_SETTER_FIELD, METHOD_HANDLE_DESCRIPTOR);
            this.loadState(visitor);
            visitor.visitVarInsn(ALOAD, 1);
            visitor.visitMethodInsn(
                    INVOKEVIRTUAL,
                    METHOD_HANDLE_INTERNAL_NAME,
                    "invokeExact",
                    HANDLE_OBJECT_SETTER,
                    false
            );
            visitor.visitInsn(RETURN);
            MenuClassWriter.finishMethod(visitor);
        }

        private void writeNoOp(Method targetMethod) {
            MethodVisitor visitor = this.beginOverride(targetMethod);
            visitor.visitInsn(RETURN);
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
            visitor.visitFieldInsn(GETFIELD, this.generatedInternalName, STATE_FIELD, Type.getDescriptor(Object.class));
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
