package net.kyrptonaught.quickshulker.api.shulker.server;

import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.kyrptonaught.quickshulker.api.QuickOpenableRegistry;
import net.kyrptonaught.quickshulker.api.QuickShulkerData;
import net.kyrptonaught.quickshulker.internal.storage.PolicySlottedStorage;
import net.kyrptonaught.shulkerutils.ShulkerUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Resolves carried shulker boxes as standard Fabric item storages. */
public final class ShulkerStorages {
    private ShulkerStorages() {
    }

    /**
     * Resolves the shulker box currently occupying one player inventory slot.
     *
     * <p>The returned storage is live, slotted and transaction-aware. It is
     * bound to the player inventory slot and follows its authoritative
     * contents while Fabric's resolved item storage remains valid. Resolve the
     * slot again after replacing the host item type. Multiple resolutions of
     * the same slot share Fabric's player-inventory transaction identity. All
     * access must remain on the owning player's server thread. Callers control
     * grouping, ordering, simulation, commit and rollback with the Fabric
     * Transfer API.</p>
     *
     * @param player owning player
     * @param playerInventorySlot slot in the player's non-equipment inventory
     * @return the accessible shulker storage, or empty when the slot is out of
     * bounds or does not contain a supported shulker box
     */
    public static Optional<SlottedStorage<ItemVariant>> findCarried(
            ServerPlayer player, int playerInventorySlot) {
        requireServerThread(player);
        if (playerInventorySlot < 0) {
            throw new IllegalArgumentException("playerInventorySlot");
        }

        Inventory inventory = player.getInventory();
        if (playerInventorySlot >= inventory.items.size()) {
            return Optional.empty();
        }

        ItemStack host = inventory.getItem(playerInventorySlot);
        QuickShulkerData data = supportedData(host);
        if (data == null || data.getInventory(player, host) == null) {
            return Optional.empty();
        }

        SingleSlotStorage<ItemVariant> hostSlot =
                PlayerInventoryStorage.of(player).getSlot(playerInventorySlot);
        ContainerItemContext context =
                ContainerItemContext.ofPlayerSlot(player, hostSlot);
        Storage<ItemVariant> storage = new net.kyrptonaught.quickshulker.internal.storage.CarriedComponentStorage(context, data.getInventory(player, host).getContainerSize());
        if (!(storage instanceof SlottedStorage<?> slotted)) {
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        SlottedStorage<ItemVariant> delegate =
                (SlottedStorage<ItemVariant>) slotted;
        return Optional.of(new PolicySlottedStorage(
                player,
                data,
                () -> inventory.getItem(playerInventorySlot),
                currentHost -> data.getInventory(player, currentHost),
                () -> supportedData(inventory.getItem(playerInventorySlot)) == data,
                delegate));
    }

    private static QuickShulkerData supportedData(ItemStack host) {
        if (host == null || host.isEmpty() || host.getCount() != 1
                || !ShulkerUtils.isShulkerItem(host)) {
            return null;
        }
        QuickShulkerData data = QuickOpenableRegistry.getQuickie(host.getItem());
        return data != null && data.supportsBundleing ? data : null;
    }

    private static void requireServerThread(ServerPlayer player) {
        if (player == null) throw new IllegalArgumentException("player");
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException(
                    "carried shulker access must run on the server thread");
        }
    }
}
