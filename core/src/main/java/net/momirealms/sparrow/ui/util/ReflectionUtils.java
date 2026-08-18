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
            // 通过 Unsafe 取出 JDK 内部 IMPL_LOOKUP, 获得可解包任意成员的特权 Lookup
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            UNSAFE = (Unsafe) unsafeField.get(null);
            Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            @SuppressWarnings("deprecation") long offset = UNSAFE.staticFieldOffset(implLookup);
            LOOKUP = (MethodHandles.Lookup) UNSAFE.getObject(MethodHandles.Lookup.class, offset);
            // 预取 JDK 内部方法句柄, 供 unreflectSetter 为 final 字段构造写句柄
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
     * 按给定的全限定类名列表依次尝试加载类, 并返回第一个成功加载的结果.
     * 适合处理不同平台, 版本或混淆映射下可能变化的类名.
     *
     * @param classes 候选类名列表, 按优先级从前到后匹配
     * @return 首个成功加载的 Class 对象, 全部失败时返回 null
     * @apiNote 候选项中单个元素为 null 时, 会被内部单类加载方法视为失败并继续尝试下一个名称
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
     * 根据单个全限定类名尝试加载类, 加载失败时吞掉异常并返回 null.
     *
     * @param clazz 需要加载的类全限定名
     * @return 成功时返回对应的 Class 对象, 失败时返回 null
     * @apiNote 该方法捕获所有 Throwable, ClassNotFoundException, LinkageError 等错误也会被折叠为 null
     */
    public static Class<?> getClazz(String clazz) {
        try {
            return Class.forName(clazz);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 判断指定类名对应的类是否存在并可被当前类加载器加载.
     *
     * @param clazz 目标类的全限定名
     * @return 类可以成功加载时返回 true, 否则返回 false
     * @apiNote 加载阶段的各种错误统一视为不存在
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
     * 判断指定的公共方法是否存在.
     * 基于 Class.getMethod, 因此只会查找当前类及其父类, 接口中可见的公共方法.
     *
     * @param clazz 目标类
     * @param method 目标方法名
     * @param parameterTypes 参数类型列表, 需要与方法签名完全一致
     * @return 找到匹配的公共方法时返回 true, 否则返回 false
     * @apiNote 不检查返回值类型, 也不匹配私有或受保护方法
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
     * 按字段名获取当前类中声明的字段, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param field 字段名
     * @return 找到时返回已执行 setAccessible(true) 的字段对象, 未找到时返回 null
     * @throws SecurityException 当 JVM 安全策略阻止访问控制修改时抛出
     * @apiNote 仅查找当前类声明的字段, 不会向父类继续搜索
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
     * 按多个候选字段名获取当前类中声明的字段, 按声明顺序返回第一个名称命中的字段.
     *
     * @param clazz 目标类
     * @param possibleNames 候选字段名列表
     * @return 命中时返回字段对象, 未命中时返回 null
     * @apiNote 该重载不会调用 setAccessible(true), 返回的字段保持原有访问性
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
     * 按声明顺序获取当前类中指定索引位置的字段, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param index 字段索引, 基于 getDeclaredFields() 的返回顺序从 0 开始
     * @return 命中时返回字段对象, 索引越界时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
     * @apiNote 反射返回的字段顺序依赖 JVM 实现, 不适合作为长期稳定协议
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
     * 按声明顺序获取当前类中第 index 个实例字段, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param index 实例字段索引, 仅统计非 static 字段
     * @return 命中时返回字段对象, 未找到时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 按类型和索引获取当前类中声明的静态字段, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型静态字段中的序号, 从 0 开始
     * @return 命中时返回字段对象, 未找到时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 按类型和索引获取公共静态字段, 并尝试设置为可访问.
     * 基于 Class.getFields() 搜索, 因此会包含从父类继承的公共字段.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型公共静态字段中的序号, 从 0 开始
     * @return 命中时返回字段对象, 未找到时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 按类型和索引获取当前类中声明的字段, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型字段中的序号, 从 0 开始
     * @return 命中时返回字段对象, 未找到时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 按声明逆序和类型获取当前类中声明的字段, 并尝试设置为可访问.
     * 从 getDeclaredFields() 的末尾向前遍历, 适合优先命中后声明字段的场景.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 逆序遍历下同类型字段中的序号, 从 0 开始
     * @return 命中时返回字段对象, 未找到时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 按类型和索引获取当前类中声明的实例字段, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param type 字段类型, 需要完全相等匹配
     * @param index 同类型实例字段中的序号, 从 0 开始
     * @return 命中时返回字段对象, 未找到时返回 null
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 获取当前类声明的全部字段, 并统一设置为可访问.
     *
     * @param clazz 目标类
     * @return 包含全部声明字段的列表, 顺序与 getDeclaredFields() 返回顺序一致
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 获取当前类声明的全部实例字段, 并统一设置为可访问.
     *
     * @param clazz 目标类
     * @return 包含所有非静态字段的列表
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 获取当前类声明的全部指定类型字段, 并统一设置为可访问.
     *
     * @param clazz 目标类
     * @param type 目标字段类型, 需要完全相等匹配
     * @return 所有命中的字段组成的列表
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 获取当前类声明的全部指定类型实例字段, 并统一设置为可访问.
     *
     * @param clazz 目标类
     * @param type 目标字段类型, 需要完全相等匹配
     * @return 所有命中的非静态字段列表
     * @throws SecurityException 当修改字段访问性被拒绝时抛出
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
     * 在公共方法中按返回值类型, 候选方法名和参数类型查找目标方法.
     * 搜索范围基于 Class.getMethods(), 因此包含继承而来的公共方法.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回方法对象, 未命中时返回 null
     * @apiNote 返回的方法不会主动调用 setAccessible(true), 但 getMethods() 返回的公共方法本身通常可访问
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
     * 在公共方法中按候选方法名和参数类型查找目标方法.
     *
     * @param clazz 目标类
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回方法对象, 未命中时返回 null
     * @apiNote 搜索范围包含父类和接口中的公共方法
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
     * 在公共方法中按返回值类型和参数类型查找目标方法.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回方法对象, 未命中时返回 null
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
     * 在公共实例方法中按返回值类型和参数类型查找目标方法.
     * 与 getMethod(Class, Class, Class...) 的区别是会跳过所有静态方法.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回实例方法对象, 未命中时返回 null
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
     * 在当前类声明的方法中按返回值类型, 候选方法名和参数类型查找目标方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回已执行 setAccessible(true) 的方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
     * @apiNote 搜索范围仅限当前类声明的方法, 不包含继承方法
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
     * 在当前类声明的方法中按返回值类型和参数类型查找目标方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回已执行 setAccessible(true) 的方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
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
     * 按返回值类型和序号获取公共方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param index 命中方法中的序号, 从 0 开始
     * @return 命中时返回方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
     * @apiNote 序号基于 getMethods() 的返回顺序, 不保证跨 JVM 版本稳定
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
     * 在公共静态方法中按返回值类型和参数类型查找目标方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回静态方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
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
     * 在公共静态方法中按返回值类型, 候选方法名和参数类型查找目标方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param possibleNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 命中时返回静态方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
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
     * 按序号获取公共静态方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param index 静态方法序号, 从 0 开始
     * @return 命中时返回静态方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
     * @apiNote 序号基于 getMethods() 的返回顺序, 不保证稳定
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
     * 按序号获取公共方法, 并尝试设置为可访问.
     *
     * @param clazz 目标类
     * @param index 公共方法序号, 从 0 开始
     * @return 命中时返回方法对象, 未命中时返回 null
     * @throws SecurityException 当修改方法访问性被拒绝时抛出
     * @apiNote 结果包含继承方法, 顺序依赖反射实现
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
     * 按候选方法名和参数类型获取公共方法, 未找到时抛出异常.
     *
     * @param clazz 目标类
     * @param possibleMethodNames 候选方法名列表
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 找到的公共方法对象
     * @throws NoSuchMethodException 当所有候选名称都未匹配到对应方法时抛出
     * @apiNote 异常消息中包含候选名称, 参数类型和类名, 便于定位版本差异问题
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
     * 获取所有满足返回值类型和参数签名的公共方法列表.
     *
     * @param clazz 目标类
     * @param returnType 期望返回值类型, 使用 isAssignableFrom 判断兼容性
     * @param parameterTypes 参数类型列表, 需要逐项完全匹配
     * @return 所有命中方法组成的列表, 没有匹配项时返回空列表
     * @apiNote 返回结果不会主动设置可访问性, 且包含继承自父类或接口的公共方法
     */
    @NotNull
    public static List<Method> getMethods(@NotNull Class<?> clazz, @NotNull Class<?> returnType, @NotNull Class<?>... parameterTypes) {
        List<Method> list = new ArrayList<>();
        for (Method method : clazz.getMethods()) {
            // 返回值类型兼容且参数个数一致才继续逐项比对参数类型
            if (!returnType.isAssignableFrom(method.getReturnType()) || method.getParameterCount() != parameterTypes.length) continue;
            Class<?>[] types = method.getParameterTypes();
            // 参数逐项完全匹配才加入结果
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
     * 把反射对象设置为可访问并原样返回, 便于链式调用.
     *
     * @param o 需要开放访问权限的反射对象, 例如 Field, Method, Constructor
     * @param <T> 反射对象类型
     * @return 已执行 setAccessible(true) 的原对象
     * @throws SecurityException 当 JVM 安全策略阻止修改访问性时抛出
     * @apiNote 在强模块封装环境下, 该方法也可能触发 InaccessibleObjectException
     */
    @NotNull
    public static <T extends AccessibleObject> T setAccessible(@NotNull final T o) {
        o.setAccessible(true);
        return o;
    }

    /**
     * 获取指定公共构造方法, 基于 Class.getConstructor.
     *
     * @param clazz 目标类
     * @param parameterTypes 构造参数类型列表, 需要逐项完全匹配
     * @return 命中时返回构造器对象, 未找到或访问受限时返回 null
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
     * 获取指定声明构造方法, 并尝试设置为可访问.
     * 基于 Class.getDeclaredConstructor, 因此可以匹配私有或受保护构造器.
     *
     * @param clazz 目标类
     * @param parameterTypes 构造参数类型列表, 需要逐项完全匹配
     * @return 命中时返回已执行 setAccessible(true) 的构造器对象, 未找到或访问受限时返回 null
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
     * 按索引获取声明构造器, 并尝试设置为可访问.
     * 基于 getDeclaredConstructors() 的返回结果, 因此包含非公共构造器.
     *
     * @param clazz 目标类
     * @param index 构造器索引, 基于 getDeclaredConstructors() 的返回顺序从 0 开始
     * @return 命中时返回构造器对象, 访问受限时返回 null
     * @throws IndexOutOfBoundsException 当 index 不在有效范围内时抛出
     * @apiNote 构造器顺序依赖反射实现, 不适合作为稳定协议
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
     * 获取目标类唯一的公共构造器.
     *
     * @param clazz 目标类
     * @return 唯一的公共构造器
     * @throws RuntimeException 当公共构造器数量不等于 1 时抛出
     * @apiNote 只统计 getConstructors() 返回的公共构造器, 不包含私有构造器
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
     * 把字段解包为 getter 形式的 MethodHandle.
     * 首次解包因访问限制失败时, 先对字段执行 setAccessible(true) 再重试.
     *
     * @param field 目标字段
     * @return 可用于读取字段值的 MethodHandle
     * @throws IllegalAccessException 当重试后仍无法访问该字段时抛出
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
     * 把字段解包为 setter 形式的 MethodHandle.
     * 标准解包因访问限制失败时, 会退回基于 JDK 内部 MemberName 和 getDirectField 的绕过逻辑,
     * 尝试为 final 等受限字段构造写句柄.
     *
     * @param field 目标字段
     * @return 成功时返回可写入字段的 MethodHandle, 全部尝试失败时返回 null
     * @apiNote 该方法依赖 JDK 内部实现细节和 Unsafe, 在不同 Java 版本上存在兼容性风险
     */
    @Nullable
    public static MethodHandle unreflectSetter(Field field) {
        try {
            return LOOKUP.unreflectSetter(field);
        } catch (IllegalAccessException e) {
            // 标准解包被拒绝时, 走 MemberName + getDirectField 内部路径绕过 final 限制
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
     * 把方法解包为 MethodHandle.
     * 首次解包因访问限制失败时, 先对方法执行 setAccessible(true) 再重试.
     *
     * @param method 目标方法
     * @return 对应的 MethodHandle
     * @throws IllegalAccessException 当重试后仍无法访问该方法时抛出
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
     * 把构造器解包为 MethodHandle.
     * 首次解包因访问限制失败时, 先对构造器执行 setAccessible(true) 再重试.
     *
     * @param constructor 目标构造器
     * @return 对应的 MethodHandle
     * @throws IllegalAccessException 当重试后仍无法访问该构造器时抛出
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
     * 按类, 字段名和字段类型查找 VarHandle.
     * 先通过 privateLookupIn 创建针对目标类的私有查找上下文, 再执行字段查找.
     *
     * @param clazz 声明字段的类
     * @param name 字段名
     * @param type 字段类型
     * @return 成功时返回对应的 VarHandle, 未找到或访问失败时返回 null
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
     * 按字段对象直接查找对应的 VarHandle.
     *
     * @param field 目标字段
     * @return 成功时返回对应的 VarHandle, 未找到或访问失败时返回 null
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
