package net.momirealms.sparrow.ui.inventory;

import net.momirealms.sparrow.ui.inventory.event.UpdateReason;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryFactory.ContainerOperation;
import net.momirealms.sparrow.ui.proxy.bukkit.craftbukkit.inventory.CraftInventoryFactory;
import net.momirealms.sparrow.ui.proxy.minecraft.world.item.ItemStackProxy;
import net.momirealms.sparrow.ui.util.ItemUtils;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class InventoryContainerHandler implements InvocationHandler {
    private final SparrowInventory inventory;

    InventoryContainerHandler(@NotNull SparrowInventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return this.invokeObject(proxy, method, arguments);
        }
        ContainerOperation operation = CraftInventoryFactory.operation(method);
        if (operation == null) {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }
            throw new UnsupportedOperationException(method.toString());
        }
        return switch (operation) {
            case GET_CONTAINER_SIZE -> this.inventory.size();
            case IS_EMPTY -> this.isEmpty();
            case GET_ITEM -> ownedHandle(this.inventory.unsafeItemAt((int) arguments[0]));
            case REMOVE_ITEM -> CraftInventoryFactory.toNms(this.removeItem((int) arguments[0], (int) arguments[1]));
            case REMOVE_ITEM_NO_UPDATE -> CraftInventoryFactory.toNms(this.removeItemNoUpdate((int) arguments[0]));
            case SET_ITEM -> {
                this.setItem((int) arguments[0], CraftInventoryFactory.toBukkit(arguments[1]));
                yield null;
            }
            case GET_MAX_STACK_SIZE -> this.maxStackSize();
            case SET_CHANGED, ON_OPEN, ON_CLOSE -> null;
            case STILL_VALID -> true;
            case CLEAR_CONTENT -> {
                this.clearContent();
                yield null;
            }
            case GET_CONTENTS -> this.contents();
            case GET_VIEWERS -> List.of();
            case GET_OWNER -> null;
            case SET_MAX_STACK_SIZE -> {
                this.maxStackSize((int) arguments[0]);
                yield null;
            }
            case GET_LOCATION -> null;
        };
    }

    private Object invokeObject(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "SparrowContainer[" + this.inventory + ']';
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private boolean isEmpty() {
        ItemStack[] contents = this.inventory.unsafeSnapshot();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private ItemStack removeItem(int slot, int amount) {
        ItemStack current = this.inventory.itemAt(slot);
        if (current == null || current.isEmpty() || amount <= 0) {
            return null;
        }
        int removedAmount = Math.min(amount, current.getAmount());
        ItemStack removed = current.clone();
        removed.setAmount(removedAmount);
        if (removedAmount == current.getAmount()) {
            this.setItem(slot, null);
        } else {
            current.setAmount(current.getAmount() - removedAmount);
            this.setItem(slot, current);
        }
        return removed;
    }

    @Nullable
    private ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = this.inventory.itemAt(slot);
        if (current != null && !current.isEmpty()) {
            this.setItem(slot, null);
        }
        return current;
    }

    private void setItem(int slot, @Nullable ItemStack item) {
        this.inventory.setItem(UpdateReason.Program.INSTANCE, slot, item);
    }

    private int maxStackSize() {
        int maxStackSize = 0;
        for (int slot = 0; slot < this.inventory.size(); slot++) {
            maxStackSize = Math.max(maxStackSize, this.inventory.slotMaxStackSize(slot));
        }
        return maxStackSize > 0 ? maxStackSize : SparrowInventory.DEFAULT_MAX_STACK_SIZE;
    }

    private void maxStackSize(int maxStackSize) {
        if (this.inventory instanceof VirtualInventory virtualInventory) {
            int[] maxStackSizes = new int[virtualInventory.size()];
            Arrays.fill(maxStackSizes, maxStackSize);
            virtualInventory.setMaxStackSizes(maxStackSizes);
        }
    }

    private void clearContent() {
        ItemStack[] contents = this.inventory.unsafeSnapshot();
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].isEmpty()) {
                this.setItem(slot, null);
            }
        }
    }

    private static Object ownedHandle(@Nullable ItemStack item) {
        return ItemUtils.isNullOrEmpty(item)
                ? ItemStackProxy.EMPTY
                : ItemStackProxy.INSTANCE.copy(ItemUtils.getItemStackHandle(item));
    }

    private ArrayList<Object> contents() {
        ItemStack[] contents = this.inventory.unsafeSnapshot();
        ArrayList<Object> converted = new ArrayList<>(contents.length);
        for (int slot = 0; slot < contents.length; slot++) {
            converted.add(ownedHandle(contents[slot]));
        }
        return converted;
    }
}
