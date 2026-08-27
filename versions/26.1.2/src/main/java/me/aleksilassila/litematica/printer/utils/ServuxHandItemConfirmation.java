package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.network.ServuxLitematicaHandler;
import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.network.HandConfirmationGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridges the version-independent confirmation gate to Litematica's Servux litematics channel.
 */
public final class ServuxHandItemConfirmation {
    private static final long QUERY_RETRY_MILLIS = 500L;
    private static final long WARNING_COOLDOWN_MILLIS = 5_000L;
    private static final String AIR_ID = "minecraft:air";
    private static final String UNAVAILABLE_MESSAGE =
            "litematica-printer.message.servuxHandConfirmationUnavailable";

    private static final HandConfirmationGate GATE =
            new HandConfirmationGate(QUERY_RETRY_MILLIS);
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final List<Packet<?>> SWITCH_PACKETS = new ArrayList<>();

    private static Object sessionLevel;
    private static int sessionEntityId = Integer.MIN_VALUE;
    private static int desiredHotbarSlot = -1;
    private static int mismatchServerSelectedSlot = -1;
    private static String mismatchDesiredSlotItem = AIR_ID;
    private static Set<String> expectedItemIds = Set.of();
    private static long lastUnavailableWarning;

    private ServuxHandItemConfirmation() {
    }

    public static boolean isEnabled() {
        return Configs.Print.SERVUX_HAND_CONFIRMATION.getBooleanValue();
    }

    public static synchronized HandConfirmationGate.Status verify(
            LocalPlayer player, Item[] acceptedItems) {
        if (!isEnabled()) {
            reset();
            return HandConfirmationGate.Status.CONFIRMED;
        }
        ensureSession(player);

        if (DataManager.getInstance().hasIntegratedServer()) {
            return HandConfirmationGate.Status.CONFIRMED;
        }

        Set<String> acceptedIds = itemIds(acceptedItems);
        String localItemId = itemId(player.getMainHandItem());
        if (desiredHotbarSlot < 0) {
            desiredHotbarSlot = player.getInventory().getSelectedSlot();
        }
        expectedItemIds = acceptedIds;

        EntityDataManager dataManager = EntityDataManager.getInstance();
        boolean servuxAvailable = dataManager.hasServuxServer();
        HandConfirmationGate.Decision decision = GATE.evaluate(
                player.getId(),
                acceptedIds,
                localItemId,
                servuxAvailable,
                System.currentTimeMillis());

        if (decision.requestRequired()) {
            ServuxLitematicaHandler.getInstance().encodeClientData(
                    ServuxLitematicaPacket.EntityRequest(player.getId()));
        } else if (!servuxAvailable) {
            warnUnavailable();
        }
        return decision.status();
    }

    public static synchronized void handleEntityData(int entityId, CompoundData data) {
        if (!isEnabled()
                || data == null
                || entityId != sessionEntityId
                || DataManager.getInstance().hasIntegratedServer()) {
            return;
        }

        int serverSelectedSlot = data.getIntOrDefault("SelectedItemSlot", -1);
        String serverHandItem = inventoryItemAt(data, serverSelectedSlot);
        HandConfirmationGate.Status status =
                GATE.acceptResponse(entityId, serverHandItem);
        if (status == HandConfirmationGate.Status.MISMATCH) {
            mismatchServerSelectedSlot = serverSelectedSlot;
            mismatchDesiredSlotItem = inventoryItemAt(data, desiredHotbarSlot);
        }
    }

    public static synchronized void beginSwitch() {
        GATE.invalidate();
        SWITCH_PACKETS.clear();
        desiredHotbarSlot = -1;
        mismatchServerSelectedSlot = -1;
        mismatchDesiredSlotItem = AIR_ID;
    }

    public static synchronized void recordSelectedSlotPacket(
            int hotbarSlot, Packet<?> packet) {
        desiredHotbarSlot = hotbarSlot;
        if (packet != null) {
            SWITCH_PACKETS.add(packet);
        }
    }

    public static synchronized void recordInventorySwitchPacket(Packet<?> packet) {
        if (packet != null) {
            SWITCH_PACKETS.add(packet);
        }
    }

    /**
     * Replays only the portions that the authoritative response shows are still missing.
     */
    public static synchronized void retrySwitch(LocalPlayer player) {
        if (CLIENT.getConnection() == null) {
            GATE.markSwitchRetried();
            return;
        }

        boolean selectedSlotWrong = desiredHotbarSlot >= 0
                && mismatchServerSelectedSlot != desiredHotbarSlot;
        boolean targetAlreadyInDesiredSlot =
                expectedItemIds.contains(mismatchDesiredSlotItem);

        if (SWITCH_PACKETS.isEmpty()) {
            CLIENT.getConnection().send(new ServerboundSetCarriedItemPacket(
                    player.getInventory().getSelectedSlot()));
        } else {
            for (Packet<?> packet : SWITCH_PACKETS) {
                if (packet instanceof ServerboundSetCarriedItemPacket) {
                    if (selectedSlotWrong) {
                        CLIENT.getConnection().send(packet);
                    }
                } else if (!targetAlreadyInDesiredSlot) {
                    CLIENT.getConnection().send(packet);
                }
            }
        }

        mismatchServerSelectedSlot = -1;
        mismatchDesiredSlotItem = AIR_ID;
        GATE.markSwitchRetried();
    }

    public static synchronized void invalidate() {
        GATE.invalidate();
        mismatchServerSelectedSlot = -1;
        mismatchDesiredSlotItem = AIR_ID;
    }

    public static synchronized void reset() {
        GATE.reset();
        SWITCH_PACKETS.clear();
        sessionLevel = null;
        sessionEntityId = Integer.MIN_VALUE;
        desiredHotbarSlot = -1;
        mismatchServerSelectedSlot = -1;
        mismatchDesiredSlotItem = AIR_ID;
        expectedItemIds = Set.of();
    }

    private static void ensureSession(LocalPlayer player) {
        if (sessionLevel != player.level() || sessionEntityId != player.getId()) {
            reset();
            sessionLevel = player.level();
            sessionEntityId = player.getId();
        }
    }

    private static Set<String> itemIds(Item[] items) {
        if (items == null || items.length == 0) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Item item : items) {
            if (item != null) {
                ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
        }
        return Set.copyOf(ids);
    }

    private static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return AIR_ID;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String inventoryItemAt(CompoundData data, int slot) {
        if (slot < 0 || !data.containsLenient("Inventory")) {
            return AIR_ID;
        }
        ListData inventory = data.getList("Inventory");
        for (int index = 0; index < inventory.size(); index++) {
            CompoundData entry = inventory.getCompoundAt(index);
            if (entry.getIntOrDefault("Slot", -1) == slot) {
                return entry.getStringOrDefault("id", AIR_ID);
            }
        }
        return AIR_ID;
    }

    private static void warnUnavailable() {
        long now = System.currentTimeMillis();
        if (now - lastUnavailableWarning < WARNING_COOLDOWN_MILLIS) {
            return;
        }
        lastUnavailableWarning = now;
        InfoUtils.showGuiOrInGameMessage(
                Message.MessageType.WARNING, UNAVAILABLE_MESSAGE);
    }
}
