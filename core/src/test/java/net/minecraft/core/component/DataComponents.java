package net.minecraft.core.component;

public final class DataComponents {

    public static final DataComponentType CUSTOM_NAME = new TestDataComponentType();
    public static final DataComponentType TOOLTIP_DISPLAY = new TestDataComponentType();
    public static final DataComponentType ITEM_MODEL = new TestDataComponentType();
    public static final DataComponentType MAP_ID = new TestDataComponentType();
    public static final DataComponentType BUNDLE_CONTENTS = new TestDataComponentType();
    private DataComponents() {
    }

    private static final class TestDataComponentType implements DataComponentType {
    }
}
