package me.aleksilassila.litematica.printer.utils;

import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.network.HandConfirmationGate;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.EasyPlaceUtilsAccessor;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.minecraft.network.HashedStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryUtils {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int OFFHAND_SLOT_INDEX = 40;
    private static final long MESSAGE_COOLDOWN_MS = 5000L;
    private static final Map<String, Long> LAST_MESSAGE_SEND_TIME = new ConcurrentHashMap<>();
    @Getter
    @Setter
    private static ItemStack orderlyStoreItem;

    /**
     * Result of preparing the main hand for a print action.
     * WAITING means either a hand switch was issued or an asynchronous item request
     * is in progress; callers that own a block job must retain it and retry later.
     */
    public enum ItemSwitchResult {
        READY,
        WAITING,
        UNAVAILABLE
    }



    public static int getSelectedSlot(Inventory inventory) {
        return inventory.getSelectedSlot();
    }

    public static void setSelectedSlot(Inventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
    }

    public static NonNullList<ItemStack> getMainStacks(Inventory inventory) {
        return inventory.getNonEquipmentItems();
    }

    public static boolean playerHasAccessToItem(LocalPlayer playerEntity, Item item) {
        return playerHasAccessToItems(playerEntity, item);
    }

    public static boolean playerHasAccessToItems(LocalPlayer playerEntity, Item... items) {
        if (items == null || items.length == 0) return true;
        if (PlayerUtils.getAbilities(playerEntity).mayBuild) return true;
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) return false;
        Inventory inventory = playerEntity.getInventory();
        for (Item item : items) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).getItem() == item) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean setPickedItemToHand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        int slotNum = mc.player.getInventory().findSlotMatchingItem(stack);
        return setPickedItemToHand(slotNum, stack, mc);
    }

    public static void setHotbarSlot(int slot, Inventory inventory) {
        boolean usePacket = Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
        if (usePacket || ServuxHandItemConfirmation.isEnabled()) {
            ServerboundSetCarriedItemPacket packet =
                    new ServerboundSetCarriedItemPacket(slot);
            ServuxHandItemConfirmation.recordSelectedSlotPacket(slot, packet);
            client.getConnection().send(packet);
        }
        setSelectedSlot(inventory, slot);
    }

    /**
     * 检查是否有可用的 Pick 槽位
     *
     * @param sourceSlot 源槽位（-1表示自动寻找）
     * @param mc         Minecraft实例
     * @return PickResult 枚举结果
     */
    public static PickResult checkPickSlotAvailable(int sourceSlot, Minecraft mc) {
        // 基础校验失败 → 返回通用FAIL
        if (mc.player == null) return PickResult.FAIL;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 源槽位是快捷栏 → 成功
        if (Inventory.isHotbarSlot(sourceSlot)) return PickResult.SUCCESS;
        // 无配置可拾取槽位 → 精准失败类型
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            return PickResult.FAIL_NO_PICK_SLOTS_CONFIGURED;
        }
        // 寻找可用槽位
        int hotbarSlot = sourceSlot;
        if (sourceSlot == -1 || !Inventory.isHotbarSlot(sourceSlot)) {
            hotbarSlot = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory);
        }
        if (hotbarSlot == -1) {
            hotbarSlot = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
        }
        // 无可用槽位 → 精准失败类型；否则成功
        return hotbarSlot != -1 ? PickResult.SUCCESS : PickResult.FAIL_NO_SUITABLE_SLOT_FOUND;
    }

    public static boolean setPickedItemToHand(int sourceSlot, ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;
        if (player.containerMenu != player.inventoryMenu) return false;
        Inventory inventory = player.getInventory();
        // 目标物品在热键栏中
        if (Inventory.isHotbarSlot(sourceSlot)) {
            setHotbarSlot(sourceSlot, inventory);
            return true;
        }
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_valid_slots_configured");
            return false;
        }
        int hotbarSlot = sourceSlot;
        // 尝试寻找一个空的可拾取方块的热键栏槽位
        if (sourceSlot == -1 || !Inventory.isHotbarSlot(sourceSlot)) {
            hotbarSlot = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory);
        }
        // 如果没有空槽位，则寻找一个可拾取方块的热键栏槽位
        if (hotbarSlot == -1) {
            hotbarSlot = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
        }
        if (hotbarSlot != -1) {
            setHotbarSlot(hotbarSlot, inventory);
            if (EntityUtils.isCreativeMode(player)) {
                getMainStacks(inventory).set(hotbarSlot, stack.copy());
                client.gameMode.handleCreativeModeItemAdd(client.player.getMainHandItem(), 36 + hotbarSlot);
                return true;
            }
            EasyPlaceUtilsAccessor.callSetEasyPlaceLastPickBlockTime();
            return swapItemToMainHand(stack.copy(), mc);
        } else {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }
    }

    public static boolean swapItemToMainHand(ItemStack stackReference, Minecraft mc) {
        Player player = mc.player;
        if (player == null) return false;
        if (player.containerMenu != player.inventoryMenu) return false;

        boolean b = fi.dy.masa.malilib.util.InventoryUtils.areStacksEqualIgnoreNbt(stackReference, player.getMainHandItem());
        if (b) {
            return false;
        }

        int slot = fi.dy.masa.malilib.util.InventoryUtils.findSlotWithItem(player.inventoryMenu, stackReference, true);
        if (slot != -1) {
            ClientPacketListener connection = client.getConnection();
            if (connection == null) {
                return false;
            }
            int currentHotbarSlot = getSelectedSlot(player.getInventory());
            if (Configs.Placement.PRINT_USE_PACKET.getBooleanValue()
                    || ServuxHandItemConfirmation.isEnabled()) {
                NonNullList<Slot> slots = player.inventoryMenu.slots;
                int totalSlots = slots.size();
                List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
                for (Slot slotItem : slots) {
                    copies.add(slotItem.getItem().copy());
                }

                Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();

                for (int j = 0; j < totalSlots; j++) {
                    ItemStack original = copies.get(j);
                    ItemStack current = slots.get(j).getItem();
                    if (!ItemStack.isSameItem(original, current)) {
                        snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                    }
                }

                HashedStack hashedStack = HashedStack.create(player.inventoryMenu.getCarried(), connection.decoratedHashOpsGenenerator());
                ServerboundContainerClickPacket packet = new ServerboundContainerClickPacket(
                        player.inventoryMenu.containerId,
                        player.inventoryMenu.getStateId(),
                        Shorts.checkedCast(slot),
                        SignedBytes.checkedCast(currentHotbarSlot),
                        ContainerInput.SWAP,
                        snapshot,
                        hashedStack
                );
                ServuxHandItemConfirmation.recordInventorySwitchPacket(packet);
                connection.send(packet);

                player.inventoryMenu.clicked(slot, currentHotbarSlot, ContainerInput.SWAP, player);
            } else {
                if (client.gameMode != null) {
                    client.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, currentHotbarSlot, ContainerInput.SWAP, player);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 获取玩家副手的物品栈（全版本通用，极简实现）
     *
     * @param player 玩家实例
     * @return 副手物品栈
     */
    public static ItemStack getOffhandStack(Player player) {
        // 直接通过固定槽位40获取，无版本专属字段依赖
        return player.getInventory().getItem(OFFHAND_SLOT_INDEX);
    }

    /**
     * 将指定物品切换/设置到副手（核心方法，无选中格子逻辑）
     *
     * @param stack 要放到副手的物品栈
     * @param mc    Minecraft实例
     * @return 是否切换成功
     */
    public static boolean setItemToOffhand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;
        if (player.containerMenu != player.inventoryMenu) return false;

        // 1. 检查副手已有该物品，直接返回成功（避免重复操作）
        boolean isAlreadyInOffhand = fi.dy.masa.malilib.util.InventoryUtils.areStacksEqual(stack, getOffhandStack(player));
        if (isAlreadyInOffhand) {
            return true;
        }

        // 2. 创造模式：直接设置副手物品（无需交换）
        if (EntityUtils.isCreativeMode(player)) {
            player.getInventory().setItem(OFFHAND_SLOT_INDEX, stack.copy());
            client.gameMode.handleCreativeModeItemAdd(getOffhandStack(player), OFFHAND_SLOT_INDEX);
            return true;
        }

        // 3. 生存模式：找到物品所在槽位，交换到副手
        int sourceSlot = fi.dy.masa.malilib.util.InventoryUtils.findSlotWithItem(player.inventoryMenu, stack, true);
        if (sourceSlot == -1) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        // 4. 发送交换数据包（复用原有配置，目标槽位固定为40）
        if (Configs.Placement.PRINT_USE_PACKET.getBooleanValue()) {
            NonNullList<Slot> slots = player.inventoryMenu.slots;
            int totalSlots = slots.size();
            List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
            for (Slot slotItem : slots) {
                copies.add(slotItem.getItem().copy());
            }

            // 版本兼容的快照对象
            Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();

            // 构建库存快照
            for (int j = 0; j < totalSlots; j++) {
                ItemStack original = copies.get(j);
                ItemStack current = slots.get(j).getItem();
                if (!ItemStack.isSameItem(original, current)) {
                    snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                }
            }

            // 发送SWAP数据包到副手槽位40
            HashedStack hashedStack = HashedStack.create(player.inventoryMenu.getCarried(), connection.decoratedHashOpsGenenerator());
            connection.send(new ServerboundContainerClickPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(),
                    Shorts.checkedCast(sourceSlot),
                    SignedBytes.checkedCast(OFFHAND_SLOT_INDEX), // 目标：副手槽位40
                    ContainerInput.SWAP,
                    snapshot,
                    hashedStack
            ));

            // 本地同步交换操作
            player.inventoryMenu.clicked(sourceSlot, OFFHAND_SLOT_INDEX, ContainerInput.SWAP, player);
        } else {
            // 不使用数据包：本地直接交换到副手
            if (client.gameMode != null) {
                client.gameMode.handleContainerInput(
                        player.inventoryMenu.containerId,
                        sourceSlot,
                        OFFHAND_SLOT_INDEX, // 目标：副手槽位40
                        ContainerInput.SWAP,
                        player
                );
            }
        }

        return true;
    }


    // ========== 新增：副手核心方法（无选中格子逻辑） ==========

    private static void showMessageWithCooldown(Message.MessageType type, String messageKey) {
        long currentTime = System.currentTimeMillis();
        // 核心修改：通过消息Key获取最后发送时间，而非消息类型
        long lastSendTime = LAST_MESSAGE_SEND_TIME.getOrDefault(messageKey, 0L);

        // 未超过冷却时间，直接返回不发送
        if (currentTime - lastSendTime < MESSAGE_COOLDOWN_MS) {
            return;
        }

        // 超过冷却时间，发送消息并更新【该Key】的最后发送时间
        InfoUtils.showGuiOrInGameMessage(type, messageKey);
        LAST_MESSAGE_SEND_TIME.put(messageKey, currentTime);
    }

    public static ItemSwitchResult switchToItemsResult(LocalPlayer player, Item[] items) {
        // 包含潜影盒延迟关闭窗口在内，只要当前仍是非玩家背包容器，就必须保留作业等待。
        if (player.containerMenu != player.inventoryMenu) {
            return ItemSwitchResult.WAITING;
        }
        if (items == null || items.length == 0) {
            return ItemSwitchResult.READY;
        }

        // 已经手持任一允许物品时无需制造额外的等待 tick。
        ItemStack currentMainHand = player.getMainHandItem();
        for (Item item : items) {
            if (currentMainHand.is(item)) {
                HandConfirmationGate.Status confirmation =
                        ServuxHandItemConfirmation.verify(player, items);
                if (confirmation == HandConfirmationGate.Status.MISMATCH) {
                    ServuxHandItemConfirmation.retrySwitch(player);
                    return ItemSwitchResult.WAITING;
                }
                if (confirmation != HandConfirmationGate.Status.CONFIRMED) {
                    return ItemSwitchResult.WAITING;
                }
                return ItemSwitchResult.READY;
            }
        }

        Inventory inventory = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            ServuxHandItemConfirmation.beginSwitch();
            ItemStack stack = new ItemStack(items[0]);
            boolean switched = InventoryUtils.setPickedItemToHand(stack, client);
            return switched || player.getMainHandItem().is(items[0])
                    ? ItemSwitchResult.WAITING
                    : ItemSwitchResult.UNAVAILABLE;
        }
        // 查找物品在背包中的槽位
        for (Item item : items) {
            int slot = findItemInInventory(inventory, item);
            if (slot != -1) {
                ServuxHandItemConfirmation.beginSwitch();
                ItemStack itemStack = inventory.getItem(slot);
                orderlyStoreItem = itemStack.copy();
                // 找到后只执行切换；实际打印留到下一 tick 重新验证主手后执行。
                boolean switched = InventoryUtils.setPickedItemToHand(slot, itemStack, client);
                return switched || player.getMainHandItem().is(item)
                        ? ItemSwitchResult.WAITING
                        : ItemSwitchResult.UNAVAILABLE;
            }
        }
        // 没找到，尝试使用快捷潜影盒
        boolean requestStarted = QuickShulkerCompat.requestShulkerItem(player, items);
        if (requestStarted
                || QuickShulkerCompat.isBusy()
                || (Configs.Print.USE_QUICK_SHULKER.getBooleanValue()
                    && QuickShulkerCompat.getCooldown() > 0)) {
            return ItemSwitchResult.WAITING;
        }
        return ItemSwitchResult.UNAVAILABLE;
    }

    public static boolean switchToItems(LocalPlayer player, Item[] items) {
        return switchToItemsResult(player, items) == ItemSwitchResult.READY;
    }

    private static int findItemInInventory(Inventory inventory, Item item) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).getItem().equals(item)) {
                return i;
            }
        }
        return -1;
    }

    public PickResult checkCanSwitchToItems(LocalPlayer player, Item[] items) {
        if (player == null) {
            return PickResult.FAIL;
        }
        Item[] targetItems = items;
        if (targetItems == null || targetItems.length == 0) {
            targetItems = new Item[]{Items.AIR};
        }
        Inventory inv = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            return InventoryUtils.checkPickSlotAvailable(-1, client);
        }
        for (Item item : targetItems) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack itemStack = inv.getItem(i);
                if (itemStack.getItem().equals(item)) {
                    return InventoryUtils.checkPickSlotAvailable(i, client);
                }
            }
        }
        return PickResult.FAIL;
    }

    public enum PickResult {
        SUCCESS,
        FAIL,
        FAIL_NO_PICK_SLOTS_CONFIGURED,
        FAIL_NO_SUITABLE_SLOT_FOUND;

        public boolean isNoPickSlotsConfigured() {
            return this == FAIL_NO_PICK_SLOTS_CONFIGURED;
        }

        public boolean isNoSuitableSlotFound() {
            return this == FAIL_NO_SUITABLE_SLOT_FOUND;
        }

        public boolean isNoAvailableSlot() {
            return isNoPickSlotsConfigured() || isNoSuitableSlotFound();
        }

        public boolean isAvailable() {
            return this == SUCCESS;
        }
    }
}
