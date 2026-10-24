package net.kyrptonaught.quickshulker.internal.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedSlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Minecraft 1.21's Transfer API predates ItemStorage.ITEM. Exchange the
 * authoritative host variant through its context so nested transactions and
 * independently resolved handles share the player-slot transaction identity.
 */
public final class CarriedComponentStorage
        extends CombinedSlottedStorage<ItemVariant, SingleSlotStorage<ItemVariant>> {
    private final ContainerItemContext context;
    private final Item originalItem;
    private final int size;

    public CarriedComponentStorage(ContainerItemContext context, int size) {
        super(Collections.emptyList());
        this.context = context;
        this.originalItem = context.getItemVariant().getItem();
        this.size = size;
        List<SingleSlotStorage<ItemVariant>> slots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) slots.add(new ComponentSlot(i));
        parts = List.copyOf(slots);
    }

    private NonNullList<ItemStack> contents() {
        NonNullList<ItemStack> result = NonNullList.withSize(size, ItemStack.EMPTY);
        context.getItemVariant().toStack(1)
                .getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .copyInto(result);
        return result;
    }

    private final class ComponentSlot implements SingleSlotStorage<ItemVariant> {
        private final int index;
        ComponentSlot(int index) { this.index = index; }

        private ItemStack stack() { return contents().get(index); }
        private boolean valid() { return context.getItemVariant().getItem() == originalItem; }
        private boolean replace(ItemStack value, TransactionContext transaction) {
            NonNullList<ItemStack> items = contents();
            items.set(index, value);
            ItemStack host = context.getItemVariant().toStack(1);
            host.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            return context.exchange(ItemVariant.of(host), 1, transaction) == 1;
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            if (!valid() || !resource.getItem().canFitInsideContainerItems()) return 0;
            ItemStack old = stack();
            if (!old.isEmpty() && !resource.matches(old)) return 0;
            int capacity = resource.toStack(1).getMaxStackSize();
            int amount = (int) Math.min(maxAmount, Math.max(0, capacity - old.getCount()));
            if (amount == 0) return 0;
            ItemStack next = resource.toStack(old.getCount() + amount);
            return replace(next, transaction) ? amount : 0;
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            if (!valid()) return 0;
            ItemStack old = stack();
            if (!resource.matches(old)) return 0;
            int amount = (int) Math.min(maxAmount, old.getCount());
            if (amount == 0) return 0;
            ItemStack next = old.copy();
            next.shrink(amount);
            return replace(next, transaction) ? amount : 0;
        }

        @Override public boolean isResourceBlank() { return stack().isEmpty(); }
        @Override public ItemVariant getResource() { return ItemVariant.of(stack()); }
        @Override public long getAmount() { return stack().getCount(); }
        @Override public long getCapacity() { return stack().getMaxStackSize(); }
    }
}
