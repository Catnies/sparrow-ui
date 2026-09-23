package net.momirealms.sparrow.ui.state.internal.collection;

import net.momirealms.sparrow.ui.state.ListSignal;
import net.momirealms.sparrow.ui.state.MapSignal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CollectionSignalGuardTest {

    @Test
    void listImplementationAndItsViewsDeclareEveryListMethod() {
        assertDeclaresAll(List.class, ListSignalImpl.class);
        assertDeclaresAll(List.class, ReadOnlyListSignal.class);
        ListSignal<String> signal = ListSignal.of();

        assertDeclaresAll(List.class, signal.subList(0, 0).getClass());
        assertDeclaresAll(List.class, signal.reversed().getClass());
    }

    @Test
    void setImplementationDeclaresEverySetMethod() {
        assertDeclaresAll(Set.class, SetSignalImpl.class);
        assertDeclaresAll(Set.class, ReadOnlySetSignal.class);
    }

    @Test
    void mapImplementationAndViewsDeclareEveryMethod() throws ReflectiveOperationException {
        assertDeclaresAll(Map.class, MapSignalImpl.class);
        assertDeclaresAll(Map.class, ReadOnlyMapSignal.class);
        MapSignal<String, String> signal = MapSignal.of();

        assertDeclaresAll(Set.class, signal.keySet().getClass());
        assertDeclaresAll(Collection.class, signal.values().getClass());
        assertDeclaresAll(Set.class, signal.entrySet().getClass());
    }

    private static void assertDeclaresAll(Class<?> contract, Class<?> implementation) {
        List<String> missing = new ArrayList<>();
        for (Method method : contract.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || isObjectMethod(method)) continue;
            if (!declaredInOurHierarchy(implementation, method)) {
                missing.add(method.getName() + parameterList(method));
            }
        }

        assertEquals(List.of(), missing, implementation.getSimpleName() + " 没有自己声明这些 " + contract.getSimpleName() + " 方法");
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private static boolean declaredInOurHierarchy(Class<?> type, Method method) {
        for (Class<?> current = type; current != null && current.getName().startsWith("net.momirealms."); current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod(method.getName(), method.getParameterTypes());
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private static String parameterList(Method method) {
        StringBuilder out = new StringBuilder("(");
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) out.append(", ");
            out.append(types[i].getSimpleName());
        }
        return out.append(')').toString();
    }
}
