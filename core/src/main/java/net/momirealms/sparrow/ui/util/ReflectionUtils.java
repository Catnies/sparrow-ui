package net.momirealms.sparrow.ui.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ReflectionUtils {
    public static final Unsafe UNSAFE;
    public static final MethodHandles.Lookup LOOKUP;
    private static final MethodHandle methodHandle$MethodHandleNatives$refKindIsSetter;
    private static final MethodHandle methodHandle$constructor$MemberName;
    private static final MethodHandle methodHandle$MemberName$getReferenceKind;
    private static final MethodHandle methodHandle$MethodHandles$Lookup$getDirectField;

    static {
        try {
            // Unsafe 可以直接读取 IMPL_LOOKUP, 供后续跨模块解包成员.
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            UNSAFE = (Unsafe) unsafeField.get(null);
            Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            @SuppressWarnings("deprecation") long offset = UNSAFE.staticFieldOffset(implLookup);
            LOOKUP = (MethodHandles.Lookup) UNSAFE.getObject(MethodHandles.Lookup.class, offset);
            // final 字段 setter 的后备路径依赖这些 JDK 内部句柄.
            Class<?> clazz$MethodHandleNatives = Class.forName("java.lang.invoke.MethodHandleNatives");
            Class<?> clazz$MemberName = Class.forName("java.lang.invoke.MemberName");
            methodHandle$MethodHandleNatives$refKindIsSetter = LOOKUP.unreflect(clazz$MethodHandleNatives.getDeclaredMethod("refKindIsSetter", byte.class));
            methodHandle$constructor$MemberName = LOOKUP.unreflectConstructor(clazz$MemberName.getDeclaredConstructor(Field.class, boolean.class));
            methodHandle$MemberName$getReferenceKind = LOOKUP.unreflect(clazz$MemberName.getDeclaredMethod("getReferenceKind"));
            methodHandle$MethodHandles$Lookup$getDirectField = LOOKUP.unreflect(MethodHandles.Lookup.class.getDeclaredMethod("getDirectField", byte.class, Class.class, clazz$MemberName));
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionUtils() {}

    /**
     * 按顺序尝试候选类名, 返回第一个成功加载的类.
     * <p>候选值为 {@code null} 或发生任意加载错误时继续尝试下一项.
     *
     * @param classes 候选类名列表, 按优先级从前到后匹配
     * @return 首个成功加载的 Class, 全部失败时返回 {@code null}
     */
    public static Class<?> getClazz(String... classes) {
        for (String className : classes) {
            Class<?> clazz = getClazz(className);
            if (clazz != null) {
                return clazz;
            }
        }
        return null;
    }

    /**
     * 尝试加载一个类, 任意加载错误都返回 {@code null}.
     *
     * @param clazz 需要加载的类全限定名
     * @return 加载到的 Class, 或 {@code null}
     */
    public static Class<?> getClazz(String clazz) {
        try {
            return Class.forName(clazz);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 判断指定类名能否由当前类加载器完成加载.
     *
     * @param clazz 目标类的全限定名
     * @return 加载成功时返回 {@code true}
     */
    public static boolean classExists(@NotNull final String clazz) {
        try {
            Class.forName(clazz);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 判断类及其父类或接口中是否存在参数完全匹配的公共方法.
     * <p>返回类型不参与匹配.
     *
     * @param clazz 目标类
     * @param method 目标方法名
     * @param parameterTypes 参数类型列表, 需要与方法签名完全一致
     * @return 存在匹配方法时返回 {@code true}
     * @throws SecurityException 当安全策略拒绝读取公共方法时
     */
    public static boolean methodExists(@NotNull final Class<?> clazz, @NotNull final String method, @NotNull final Class<?>... parameterTypes) {
        try {
            clazz.getMethod(method, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 按名称查找当前类声明的字段并开放访问权限.
     * <p>搜索范围不包含父类.
     *
     * @param clazz 目标类
     * @param field 字段名
     * @return 已开放访问权限的字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getDeclaredField(final Class<?> clazz, final String field) {
        try {
            return setAccessible(clazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * 按反射返回顺序查找当前类声明的字段, 返回首个名称匹配项.
     * <p>返回字段保持原访问权限.
     *
     * @param clazz 目标类
     * @param possibleNames 候选字段名列表
     * @return 首个匹配字段, 未找到时返回 {@code null}
     */
    @Nullable
    public static Field getDeclaredField(@NotNull Class<?> clazz, @NotNull String... possibleNames) {
        List<String> possibleNameList = Arrays.asList(possibleNames);
        for (Field field : clazz.getDeclaredFields()) {
            if (possibleNameList.contains(field.getName())) {
                return field;
            }
        }
        return null;
    }

    /**
     * 按 {@link Class#getDeclaredFields()} 的返回顺序获取字段并开放访问权限.
     * <p>反射字段顺序由 JVM 决定, 不适合作为稳定协议.
     *
     * @param clazz 目标类
     * @param index 字段索引, 基于 getDeclaredFields() 的返回顺序从 0 开始
     * @return 指定字段, 索引越界时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getDeclaredField(final Class<?> clazz, final int index) {
        int i = 0;
        for (final Field field : clazz.getDeclaredFields()) {
            if (index == i) {
                return setAccessible(field);
            }
            i++;
        }
        return null;
    }

    /**
     * 按反射返回顺序获取当前类的第 index 个实例字段并开放访问权限.
     * <p>字段顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param index 实例字段索引, 仅统计非 static 字段
     * @return 指定实例字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getInstanceDeclaredField(final Class<?> clazz, final int index) {
        int i = 0;
        for (final Field field : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                if (index == i) {
                    return setAccessible(field);
                }
                i++;
            }
        }
        return null;
    }

    /**
     * 按反射返回顺序获取当前类中指定类型的第 index 个静态字段.
     * <p>返回字段已开放访问权限, 字段顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型静态字段中的序号, 从 0 开始
     * @return 指定静态字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getStaticDeclaredField(final Class<?> clazz, final Class<?> type, final int index) {
        int i = 0;
        for (final Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type) {
                if (Modifier.isStatic(field.getModifiers())) {
                    if (index == i) {
                        return setAccessible(field);
                    }
                    i++;
                }
            }
        }
        return null;
    }

    /**
     * 按反射返回顺序获取指定类型的第 index 个公共静态字段.
     * <p>搜索范围包含继承字段, 返回字段已开放访问权限, 字段顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型公共静态字段中的序号, 从 0 开始
     * @return 指定公共静态字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getStaticField(final Class<?> clazz, final Class<?> type, final int index) {
        int i = 0;
        for (final Field field : clazz.getFields()) {
            if (field.getType() == type) {
                if (Modifier.isStatic(field.getModifiers())) {
                    if (index == i) {
                        return setAccessible(field);
                    }
                    i++;
                }
            }
        }
        return null;
    }

    /**
     * 按反射返回顺序获取当前类中指定类型的第 index 个字段.
     * <p>返回字段已开放访问权限, 字段顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型字段中的序号, 从 0 开始
     * @return 指定字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getDeclaredField(final Class<?> clazz, final Class<?> type, int index) {
        int i = 0;
        for (final Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type) {
                if (index == i) {
                    return setAccessible(field);
                }
                i++;
            }
        }
        return null;
    }

    /**
     * 从 {@link Class#getDeclaredFields()} 末尾向前获取指定类型的第 index 个字段.
     * <p>返回字段已开放访问权限, 字段顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 逆序遍历下同类型字段中的序号, 从 0 开始
     * @return 指定字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getDeclaredFieldBackwards(final Class<?> clazz, final Class<?> type, int index) {
        int i = 0;
        Field[] fields = clazz.getDeclaredFields();
        for (int j = fields.length - 1; j >= 0; j--) {
            Field field = fields[j];
            if (field.getType() == type) {
                if (index == i) {
                    return setAccessible(field);
                }
                i++;
            }
        }
        return null;
    }

    /**
     * 按反射返回顺序获取当前类中指定类型的第 index 个实例字段.
     * <p>返回字段已开放访问权限, 字段顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型实例字段中的序号, 从 0 开始
     * @return 指定实例字段, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Field getInstanceDeclaredField(@NotNull Class<?> clazz, final Class<?> type, int index) {
        int i = 0;
        for (final Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
                if (index == i) {
                    return setAccessible(field);
                }
                i++;
            }
        }
        return null;
    }

    /**
     * 返回当前类声明的全部字段, 每个字段均已开放访问权限.
     *
     * @param clazz 目标类
     * @return 包含全部声明字段的列表, 顺序与 getDeclaredFields() 返回顺序一致
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @NotNull
    public static List<Field> getDeclaredFields(final Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            fields.add(setAccessible(field));
        }
        return fields;
    }

    /**
     * 返回当前类声明的全部实例字段, 每个字段均已开放访问权限.
     *
     * @param clazz 目标类
     * @return 包含所有非静态字段的列表
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @NotNull
    public static List<Field> getInstanceDeclaredFields(@NotNull Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                list.add(setAccessible(field));
            }
        }
        return list;
    }

    /**
     * 返回当前类声明的全部指定类型字段, 每个字段均已开放访问权限.
     *
     * @param clazz 目标类
     * @param type 目标字段类型, 需要完全相等匹配
     * @return 全部匹配字段
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @NotNull
    public static List<Field> getDeclaredFields(@NotNull final Class<?> clazz, @NotNull final Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type) {
                fields.add(setAccessible(field));
            }
        }
        return fields;
    }

    /**
     * 返回当前类声明的全部指定类型实例字段, 每个字段均已开放访问权限.
     *
     * @param clazz 目标类
     * @param type 目标字段类型, 需要完全相等匹配
     * @return 全部匹配实例字段
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @NotNull
    public static List<Field> getInstanceDeclaredFields(@NotNull Class<?> clazz, @NotNull Class<?> type) {
        List<Field> list = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
                list.add(setAccessible(field));
            }
        }
        return list;
    }

    /**
     * 在当前类及继承的公共方法中匹配返回类型, 候选名称与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类. 返回方法保持原访问权限.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配方法, 未找到时返回 {@code null}
     */
    @Nullable
    public static Method getMethod(final Class<?> clazz, Class<?> returnType, final String[] possibleMethodNames, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            for (String name : possibleMethodNames) {
                if (name.equals(method.getName())) {
                    if (returnType.isAssignableFrom(method.getReturnType())) {
                        return method;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在当前类及继承的公共方法中匹配候选名称与参数签名.
     * <p>参数类型要求完全相同, 返回类型不参与匹配.
     *
     * @param clazz 目标类
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配方法, 未找到时返回 {@code null}
     */
    @Nullable
    public static Method getMethod(final Class<?> clazz, final String[] possibleMethodNames, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            for (String name : possibleMethodNames) {
                if (name.equals(method.getName())) return method;
            }
        }
        return null;
    }

    /**
     * 在当前类及继承的公共方法中匹配返回类型与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配方法, 未找到时返回 {@code null}
     */
    @Nullable
    public static Method getMethod(final Class<?> clazz, Class<?> returnType, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            if (returnType.isAssignableFrom(method.getReturnType())) return method;
        }
        return null;
    }

    /**
     * 在当前类及继承的公共实例方法中匹配返回类型与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配实例方法, 未找到时返回 {@code null}
     */
    @Nullable
    public static Method getInstanceMethod(final Class<?> clazz, Class<?> returnType, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            if (returnType.isAssignableFrom(method.getReturnType())) return method;
        }
        return null;
    }

    /**
     * 在当前类声明的方法中匹配返回类型, 候选名称与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类. 返回方法已开放访问权限.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Method getDeclaredMethod(final Class<?> clazz, Class<?> returnType, final String[] possibleMethodNames, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            for (String name : possibleMethodNames) {
                if (name.equals(method.getName())) {
                    if (returnType.isAssignableFrom(method.getReturnType())) {
                        return setAccessible(method);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 在当前类声明的方法中匹配返回类型与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类. 返回方法已开放访问权限.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Method getDeclaredMethod(final Class<?> clazz, Class<?> returnType, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            if (returnType.isAssignableFrom(method.getReturnType())) return setAccessible(method);
        }
        return null;
    }

    /**
     * 按反射返回顺序获取指定返回类型的第 index 个公共方法.
     * <p>搜索范围包含继承方法, 返回方法已开放访问权限. 方法顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param index 命中方法中的序号, 从 0 开始
     * @return 指定方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Method getMethod(final Class<?> clazz, Class<?> returnType, int index) {
        int i = 0;
        for (Method method : clazz.getMethods()) {
            if (returnType.isAssignableFrom(method.getReturnType())) {
                if (i == index) {
                    return setAccessible(method);
                }
                i++;
            }
        }
        return null;
    }

    /**
     * 在当前类及继承的公共静态方法中匹配返回类型与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类. 返回方法已开放访问权限.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配静态方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Method getStaticMethod(final Class<?> clazz, Class<?> returnType, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            if (returnType.isAssignableFrom(method.getReturnType())) return setAccessible(method);
        }
        return null;
    }

    /**
     * 在当前类及继承的公共静态方法中匹配返回类型, 候选名称与参数签名.
     * <p>参数类型要求完全相同, 返回类型允许匹配期望类型的子类. 返回方法已开放访问权限.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param possibleNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 首个匹配静态方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Method getStaticMethod(final Class<?> clazz, Class<?> returnType, String[] possibleNames, final Class<?>... parameterTypes) {
        outer:
        for (Method method : clazz.getMethods()) {
            if (method.getParameterCount() != parameterTypes.length) {
                continue;
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != parameterTypes[i]) {
                    continue outer;
                }
            }
            if (returnType.isAssignableFrom(method.getReturnType())) {
                for (String name : possibleNames) {
                    if (name.equals(method.getName())) {
                        return setAccessible(method);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 按反射返回顺序获取第 index 个公共静态方法并开放访问权限.
     * <p>搜索范围包含继承方法, 方法顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param index 静态方法序号, 从 0 开始
     * @return 指定静态方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    public static Method getStaticMethod(final Class<?> clazz, int index) {
        int i = 0;
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                if (i == index) {
                    return setAccessible(method);
                }
                i++;
            }
        }
        return null;
    }

    /**
     * 按反射返回顺序获取第 index 个公共方法并开放访问权限.
     * <p>搜索范围包含继承方法, 方法顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param index 公共方法序号, 从 0 开始
     * @return 指定方法, 未找到时返回 {@code null}
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     */
    @Nullable
    public static Method getMethod(final Class<?> clazz, int index) {
        int i = 0;
        for (Method method : clazz.getMethods()) {
            if (i == index) {
                return setAccessible(method);
            }
            i++;
        }
        return null;
    }

    /**
     * 按候选名称与参数签名查找公共方法, 未找到时抛出异常.
     *
     * @param clazz 目标类
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 找到的公共方法对象
     * @throws NoSuchMethodException 当所有候选名称都未匹配时
     */
    public static Method getMethodOrElseThrow(final Class<?> clazz, final String[] possibleMethodNames, final Class<?>[] parameterTypes) throws NoSuchMethodException {
        Method method = getMethod(clazz, possibleMethodNames, parameterTypes);
        if (method == null) {
            throw new NoSuchMethodException("No method found with possible names " + Arrays.toString(possibleMethodNames) + " with parameters " +
                    Arrays.toString(parameterTypes) + " in class " + clazz.getName());
        }
        return method;
    }

    /**
     * 返回所有满足返回类型与参数签名的公共方法.
     * <p>搜索范围包含继承方法, 参数类型要求完全相同, 返回类型允许匹配期望类型的子类.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 全部匹配方法, 没有时返回空列表
     */
    @NotNull
    public static List<Method> getMethods(@NotNull Class<?> clazz, @NotNull Class<?> returnType, @NotNull Class<?>... parameterTypes) {
        List<Method> list = new ArrayList<>();
        for (Method method : clazz.getMethods()) {
            if (!returnType.isAssignableFrom(method.getReturnType()) || method.getParameterCount() != parameterTypes.length) continue;
            Class<?>[] types = method.getParameterTypes();
            outer: {
                for (int i = 0; i < types.length; i++) {
                    if (types[i] != parameterTypes[i]) {
                        break outer;
                    }
                }
                list.add(method);
            }
        }
        return list;
    }

    /**
     * 开放反射对象的访问权限并原样返回.
     *
     * @param o Field, Method 或 Constructor 等反射对象
     * @param <T> 反射对象类型
     * @return 同一个反射对象
     * @throws SecurityException 当安全策略拒绝修改访问权限时
     * @throws InaccessibleObjectException 当目标模块未向调用方开放对应包时
     */
    @NotNull
    public static <T extends AccessibleObject> T setAccessible(@NotNull final T o) {
        o.setAccessible(true);
        return o;
    }

    /**
     * 查找参数签名完全匹配的公共构造器.
     *
     * @param clazz 目标类
     * @param parameterTypes 构造参数类型列表, 需要逐项完全匹配
     * @return 匹配构造器, 未找到或安全策略拒绝访问时返回 {@code null}
     */
    @Nullable
    public static Constructor<?> getConstructor(Class<?> clazz, Class<?>... parameterTypes) {
        try {
            return clazz.getConstructor(parameterTypes);
        } catch (NoSuchMethodException | SecurityException ignore) {
            return null;
        }
    }

    /**
     * 查找参数签名完全匹配的声明构造器并开放访问权限.
     * <p>搜索范围包含私有和受保护构造器.
     *
     * @param clazz 目标类
     * @param parameterTypes 构造参数类型列表, 需要逐项完全匹配
     * @return 匹配构造器, 未找到或安全策略拒绝访问时返回 {@code null}
     * @throws InaccessibleObjectException 当目标模块未向调用方开放对应包时
     */
    @Nullable
    public static Constructor<?> getDeclaredConstructor(Class<?> clazz, Class<?>... parameterTypes) {
        try {
            return setAccessible(clazz.getDeclaredConstructor(parameterTypes));
        } catch (NoSuchMethodException | SecurityException ignore) {
            return null;
        }
    }

    /**
     * 按反射返回顺序获取声明构造器并开放访问权限.
     * <p>搜索范围包含非公共构造器, 构造器顺序由 JVM 决定.
     *
     * @param clazz 目标类
     * @param index 构造器索引, 基于 getDeclaredConstructors() 的返回顺序从 0 开始
     * @return 指定构造器, 安全策略拒绝访问时返回 {@code null}
     * @throws IndexOutOfBoundsException 当 index 不在有效范围内时
     * @throws InaccessibleObjectException 当目标模块未向调用方开放对应包时
     */
    @Nullable
    public static Constructor<?> getConstructor(Class<?> clazz, int index) {
        try {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            if (index < 0 || index >= constructors.length) {
                throw new IndexOutOfBoundsException("Invalid constructor index: " + index);
            }
            return setAccessible(constructors[index]);
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * 返回目标类唯一的公共构造器.
     *
     * @param clazz 目标类
     * @return 唯一的公共构造器
     * @throws RuntimeException 当公共构造器数量不等于 1 时
     */
    @NotNull
    public static Constructor<?> getTheOnlyConstructor(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getConstructors();
        if (constructors.length != 1) {
            throw new RuntimeException("This class is expected to have only one constructor but it has " + constructors.length);
        }
        return constructors[0];
    }

    /**
     * 将字段解包为 getter MethodHandle, 访问失败时开放字段权限后重试.
     *
     * @param field 目标字段
     * @return 可用于读取字段值的 MethodHandle
     * @throws IllegalAccessException 当重试后仍无法访问字段时
     */
    public static MethodHandle unreflectGetter(Field field) throws IllegalAccessException {
        try {
            return LOOKUP.unreflectGetter(field);
        } catch (IllegalAccessException e) {
            field.setAccessible(true);
            return LOOKUP.unreflectGetter(field);
        }
    }

    /**
     * 将字段解包为 setter MethodHandle, 标准解包失败时尝试 JDK 内部后备路径.
     * <p><strong>后备路径依赖 Unsafe, MemberName 与 getDirectField, 升级 JDK 后必须重新验证.</strong>
     *
     * @param field 目标字段
     * @return 可写入字段的 MethodHandle, 全部尝试失败时返回 {@code null}
     */
    @Nullable
    public static MethodHandle unreflectSetter(Field field) {
        try {
            return LOOKUP.unreflectSetter(field);
        } catch (IllegalAccessException e) {
            // MemberName 以 setter 模式创建, getDirectField 可为 final 字段生成写句柄.
            try {
                Object memberName = methodHandle$constructor$MemberName.invoke(field, true);
                Object refKind = methodHandle$MemberName$getReferenceKind.invoke(memberName);
                methodHandle$MethodHandleNatives$refKindIsSetter.invoke(refKind);
                return (MethodHandle) methodHandle$MethodHandles$Lookup$getDirectField.invoke(LOOKUP, refKind, field.getDeclaringClass(), memberName);
            } catch (Throwable ex) {
                return null;
            }
        }
    }

    /**
     * 将方法解包为 MethodHandle, 访问失败时开放方法权限后重试.
     *
     * @param method 目标方法
     * @return 对应的 MethodHandle
     * @throws IllegalAccessException 当重试后仍无法访问方法时
     */
    public static MethodHandle unreflectMethod(Method method) throws IllegalAccessException {
        try {
            return LOOKUP.unreflect(method);
        } catch (IllegalAccessException e) {
            method.setAccessible(true);
            return LOOKUP.unreflect(method);
        }
    }

    /**
     * 将构造器解包为 MethodHandle, 访问失败时开放构造器权限后重试.
     *
     * @param constructor 目标构造器
     * @return 对应的 MethodHandle
     * @throws IllegalAccessException 当重试后仍无法访问构造器时
     */
    public static MethodHandle unreflectConstructor(Constructor<?> constructor) throws IllegalAccessException {
        try {
            return LOOKUP.unreflectConstructor(constructor);
        } catch (IllegalAccessException e) {
            constructor.setAccessible(true);
            return LOOKUP.unreflectConstructor(constructor);
        }
    }

    /**
     * 使用目标类的私有 Lookup 按名称和类型查找实例字段 VarHandle.
     *
     * @param clazz 声明字段的类
     * @param name 字段名
     * @param type 字段类型
     * @return 对应 VarHandle, 未找到或访问失败时返回 {@code null}
     */
    public static VarHandle findVarHandle(Class<?> clazz, String name, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(clazz, LOOKUP)
                    .findVarHandle(clazz, name, type);
        } catch (NoSuchFieldException | SecurityException | IllegalAccessException e) {
            return null;
        }
    }

    /**
     * 使用字段声明类的私有 Lookup 查找实例字段 VarHandle.
     *
     * @param field 目标字段
     * @return 对应 VarHandle, 未找到或访问失败时返回 {@code null}
     */
    public static VarHandle findVarHandle(Field field) {
        try {
            return MethodHandles.privateLookupIn(field.getDeclaringClass(), LOOKUP)
                    .findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
        } catch (IllegalAccessException | NoSuchFieldException e) {
            return null;
        }
    }
}
