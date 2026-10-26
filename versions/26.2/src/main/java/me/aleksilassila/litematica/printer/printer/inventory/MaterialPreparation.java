package me.aleksilassila.litematica.printer.printer.inventory;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.network.HandConfirmationGate;
import me.aleksilassila.litematica.printer.core.status.PrinterWaitReason;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.ServuxHandItemConfirmation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Material acquisition and hand confirmation policy, separate from inventory packet operations. */
public final class MaterialPreparation {
    private static final Minecraft client = Minecraft.getInstance();

    private MaterialPreparation() {}

    /**
     * Result of preparing the main hand for a print action.
     * Waiting results retain the block job for a later tick and carry the reason
     * so the HUD can distinguish item preparation from a Servux response.
     */
    public enum Result {
        READY,
        WAITING,
        WAITING_FOR_CONFIRMATION,
        UNAVAILABLE;

        public boolean isWaiting() {
            return this == WAITING || this == WAITING_FOR_CONFIRMATION;
        }

        public PrinterWaitReason waitingReason() {
            return switch (this) {
                case WAITING -> PrinterWaitReason.ITEM_SWITCH;
                case WAITING_FOR_CONFIRMATION -> PrinterWaitReason.SERVUX_HAND_CONFIRMATION;
                default -> throw new IllegalStateException("Result is not waiting: " + this);
            };
        }
    }

    public static Result prepare(LocalPlayer player, Item[] items) {
        // 包含潜影盒延迟关闭窗口在内，只要当前仍是非玩家背包容器，就必须保留作业等待。
        if (player.containerMenu != player.inventoryMenu) {
            return Result.WAITING;
        }
        if (items == null || items.length == 0) {
            return Result.READY;
        }

        // 已经手持任一允许物品时无需制造额外的等待 tick。
        ItemStack currentMainHand = player.getMainHandItem();
        for (Item item : items) {
            if (currentMainHand.is(item)) {
                HandConfirmationGate.Status confirmation =
                        ServuxHandItemConfirmation.verify(player, items);
                if (confirmation == HandConfirmationGate.Status.MISMATCH) {
                    ServuxHandItemConfirmation.retrySwitch(player);
                    return Result.WAITING_FOR_CONFIRMATION;
                }
                if (confirmation != HandConfirmationGate.Status.CONFIRMED) {
                    return Result.WAITING_FOR_CONFIRMATION;
                }
                return Result.READY;
            }
        }

        Inventory inventory = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            ServuxHandItemConfirmation.beginSwitch();
            ItemStack stack = new ItemStack(items[0]);
            boolean switched = InventoryUtils.setPickedItemToHand(stack, client);
            return switched || player.getMainHandItem().is(items[0])
                    ? Result.WAITING
                    : Result.UNAVAILABLE;
        }
        // 查找物品在背包中的槽位
        for (Item item : items) {
            int slot = findItemInInventory(inventory, item);
            if (slot != -1) {
                ServuxHandItemConfirmation.beginSwitch();
                ItemStack itemStack = inventory.getItem(slot);
                InventoryUtils.setOrderlyStoreItem(itemStack.copy());
                // 找到后只执行切换；实际打印留到下一 tick 重新验证主手后执行。
                boolean switched = InventoryUtils.setPickedItemToHand(slot, itemStack, client);
                return switched || player.getMainHandItem().is(item)
                        ? Result.WAITING
                        : Result.UNAVAILABLE;
            }
        }
        // 没找到，尝试使用快捷潜影盒
        boolean requestStarted = QuickShulkerCompat.requestShulkerItem(player, items);
        if (requestStarted
                || QuickShulkerCompat.isBusy()
                || (Configs.Print.USE_QUICK_SHULKER.getBooleanValue()
                    && QuickShulkerCompat.getCooldown() > 0)) {
            return Result.WAITING;
        }
        return Result.UNAVAILABLE;
    }

    private static int findItemInInventory(Inventory inventory, Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).getItem().equals(item)) {
                return i;
            }
        }
        return -1;
    }
}
