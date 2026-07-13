package me.aleksilassila.litematica.printer.utils;

import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import me.aleksilassila.litematica.printer.interfaces.compat.TakeItOutCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

//#if MC >= 12105
import net.minecraft.network.HashedStack;
//#endif

import java.util.*;

public class QuickShulkerUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final boolean QUICK_SHULKER_LOADED = FabricLoader.getInstance().isModLoaded("quickshulker");

    @Getter @Setter
    private static boolean isOpenHandler;
    @Setter
    @Getter
    private static int shulkerCooldown;
    @Getter @Setter
    private static int shulkerBoxSlot = -1;
    @Getter
    private static final Set<Item> lastNeedItemList = new HashSet<>();
    /** 之前从潜影盒取出、背包满时需要归还的物品 */
    private static final LinkedList<Item> itemsToReturn = new LinkedList<>();

    private QuickShulkerUtils() {}

    public static void tick() {
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
    }

    public static void addLastNeedItem(Item item) {
        lastNeedItemList.add(item);
    }

    public static void clearLastNeedItems() {
        lastNeedItemList.clear();
    }

    // ========== 统一取物入口 ==========

    /**
     * 根据 ShulkerSource 配置分发潜影盒取物请求。
     * @return true 已发起请求（调用方应等待），false 无法处理
     */
    public static boolean requestShulkerItem(LocalPlayer player, Item[] items) {
        if (!Configs.Print.USE_QUICK_SHULKER.getBooleanValue()) return false;

        ShulkerSource source = (ShulkerSource) Configs.Print.SHULKER_SOURCE.getOptionListValue();
        return switch (source) {
            //#if MC >= 260102
            case TAKE_IT_OUT -> TakeItOutCompat.tryExtract(player, items);
            //#endif
            case MOD -> {
                if (!QUICK_SHULKER_LOADED) yield false;
                yield requestViaOpenShulker(player, items, source);
            }
            case PLUGIN -> requestViaOpenShulker(player, items, source);
            default -> false;
        };
    }

    /** MOD/PLUGIN 模式：找到含目标物品的潜影盒并打开 */
    private static boolean requestViaOpenShulker(LocalPlayer player, Item[] items, ShulkerSource source) {
        if (isOpenHandler || shulkerCooldown > 0) return false;

        Inventory inventory = player.getInventory();
        for (Item item : items) {
            int shulkerSlot = findShulkerWithItem(player, item);
            if (shulkerSlot != -1) {
                ItemStack shulkerStack = inventory.getItem(shulkerSlot);
                setShulkerBoxSlot(shulkerSlot);
                clearLastNeedItems();
                addLastNeedItem(item);
                ModUtils.closeScreen++;
                setOpenHandler(true);
                setShulkerCooldown(Configs.Print.SHULKER_COOLDOWN.getIntegerValue());
                openShulker(shulkerStack, shulkerSlot, source);
                return true;
            }
        }
        return false;
    }

    // ========== Open Shulker ==========

    /** 按已选择的来源打开潜影盒：MOD 走 QuickShulker API，PLUGIN 走右键模拟 */
    private static void openShulker(ItemStack stack, int inventorySlot, ShulkerSource source) {
        if (source == ShulkerSource.PLUGIN) {
            openShulkerByRightClick(inventorySlot);
            return;
        }

        openShulkerViaMod(stack, inventorySlot);
    }

    private static void openShulkerViaMod(ItemStack stack, int inventorySlot) {
        QuickShulkerCompat.openShulker(stack, inventorySlot);
    }

    // ========== 容器槽位点击 ==========

    public static void clickSlot(AbstractContainerMenu container, int slotIndex, int button, ClickType type) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) return;

        NonNullList<Slot> slots = container.slots;
        int totalSlots = slots.size();
        List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
        for (Slot slotItem : slots) {
            copies.add(slotItem.getItem().copy());
        }

        //#if MC >= 12105
        Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();
        //#else
        //$$ Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
        //#endif

        for (int j = 0; j < totalSlots; j++) {
            ItemStack original = copies.get(j);
            ItemStack current = slots.get(j).getItem();
            if (!ItemStack.isSameItem(original, current)) {
                //#if MC >= 12105
                snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                //#else
                //$$ snapshot.put(j, current.copy());
                //#endif
            }
        }

        //#if MC >= 12105
        HashedStack carried = HashedStack.create(container.getCarried(), connection.decoratedHashOpsGenenerator());
        connection.send(new ServerboundContainerClickPacket(
                container.containerId,
                container.getStateId(),
                Shorts.checkedCast(slotIndex),
                SignedBytes.checkedCast(button),
                type,
                snapshot,
                carried
        ));
        //#else
        //$$ connection.send(new ServerboundContainerClickPacket(
        //$$         container.containerId,
        //$$         container.getStateId(),
        //$$         slotIndex,
        //$$         button,
        //$$         type,
        //$$         container.getCarried().copy(),
        //$$         snapshot
        //$$ ));
        //#endif

        container.clicked(slotIndex, button, type, mc.player);
    }

    public static void pickupSlot(AbstractContainerMenu container, int slotIndex) {
        clickSlot(container, slotIndex, 0, ClickType.PICKUP);
    }

    /** button 即目标快捷栏槽位 0-8 */
    public static void swapWithHotbar(AbstractContainerMenu container, int slotIndex, int hotbarSlot) {
        clickSlot(container, slotIndex, hotbarSlot, ClickType.SWAP);
    }

    // ========== 插件服右键开箱 ==========

    public static void openShulkerByRightClick(int inventorySlot) {
        if (mc.player == null || mc.gameMode == null) return;
        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                inventorySlot,
                1, // 右键
                ClickType.PICKUP,
                mc.player);
    }

    // ========== 潜影盒取物逻辑 ==========
    // 阶段1（背包满时）：把之前取出的物品放回潜影盒
    // 阶段2：单向取出所需物品

    /**
     * 收到 ContainerSetContent 包时调用。
     * 背包满且开启归还配置时，先把 itemsToReturn 里的东西放回潜影盒；
     * 然后从潜影盒中找到所需物品，交换到背包空位（单向取出）。
     */
    public static void switchFromShulker() {
        LocalPlayer player = mc.player;
        if (player == null || player.containerMenu.equals(player.inventoryMenu)) {
            isOpenHandler = false;
            return;
        }

        AbstractContainerMenu container = player.containerMenu;
        Inventory inventory = player.getInventory();

        // ── 阶段1：背包满时归还物品 ──
        if (Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.getBooleanValue()
                && !hasEmptySlot(inventory)) {
            Iterator<Item> it = itemsToReturn.iterator();
            while (it.hasNext()) {
                Item returnItem = it.next();
                for (int i = 0; i < Math.min(inventory.getContainerSize(), 36); i++) {
                    if (inventory.getItem(i).is(returnItem)) {
                        // 找潜影盒空槽放回去
                        for (Slot s : container.slots) {
                            if (!s.hasItem()) {
                                int ownSlots = container.slots.size() - 36;
                                int containerSource;
                                if (i < 9) {
                                    containerSource = ownSlots + 27 + i;
                                } else {
                                    containerSource = ownSlots + (i - 9);
                                }
                                mc.gameMode.handleInventoryMouseClick(container.containerId, containerSource, 0, ClickType.PICKUP, player);
                                mc.gameMode.handleInventoryMouseClick(container.containerId, s.index, 0, ClickType.PICKUP, player);
                                it.remove();
                                break;
                            }
                        }
                        break;
                    }
                }
            }
        }

        // ── 阶段2：从潜影盒取出所需物品 ──
        for (Slot slot : container.slots) {
            if (!slot.hasItem()) continue;
            for (Item item : lastNeedItemList) {
                if (slot.getItem().getItem().equals(item)) {
                    itemsToReturn.addLast(slot.getItem().getItem());
                    // 找背包空位（优先快捷栏，再主背包）
                    int emptyInvSlot = -1;
                    for (int i = 0; i < inventory.getContainerSize(); i++) {
                        if (inventory.getItem(i).isEmpty()) {
                            emptyInvSlot = i;
                            break;
                        }
                    }
                    if (emptyInvSlot != -1 && mc.gameMode != null) {
                        // 背包索引 → 容器槽位索引的转换
                        int ownSlots = container.slots.size() - 36; // 容器自身槽数
                        int containerTarget;
                        if (emptyInvSlot < 9) {
                            // 快捷栏在容器末尾
                            containerTarget = ownSlots + 27 + emptyInvSlot;
                        } else {
                            // 主背包紧接容器自身槽位
                            containerTarget = ownSlots + (emptyInvSlot - 9);
                        }
                        // 先拾取潜影盒槽位，再放到目标槽位
                        mc.gameMode.handleInventoryMouseClick(container.containerId, slot.index, 0, ClickType.PICKUP, player);
                        mc.gameMode.handleInventoryMouseClick(container.containerId, containerTarget, 0, ClickType.PICKUP, player);
                    }
                    player.closeContainer();
                    shulkerBoxSlot = -1;
                    isOpenHandler = false;
                    lastNeedItemList.clear();
                    return;
                }
            }
        }

        player.closeContainer();
        isOpenHandler = false;
        lastNeedItemList.clear();
    }

    /** 在玩家背包（跳过快捷栏）中找到包含目标物品的潜影盒，返回背包槽位索引，未找到返回 -1 */
    public static int findShulkerWithItem(LocalPlayer player, Item target) {
        for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
            if (id.contains("shulker_box") && stack.getCount() == 1) {
                NonNullList<ItemStack> contents = fi.dy.masa.malilib.util.InventoryUtils
                        .getStoredItems(stack, -1);
                if (contents.stream().anyMatch(s -> s.getItem().equals(target))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean hasEmptySlot(Inventory inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!Inventory.isHotbarSlot(i) && inventory.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
