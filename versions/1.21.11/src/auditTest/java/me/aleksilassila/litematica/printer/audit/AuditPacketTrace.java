package me.aleksilassila.litematica.printer.audit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Player;
import org.slf4j.LoggerFactory;

/** Passive diagnostics only: never cancels, retries, schedules or edits gameplay. */
public final class AuditPacketTrace {
    public static final boolean ENABLED = Boolean.getBoolean("printer.audit.packetTrace");
    private static final boolean BUFFERED = Boolean.getBoolean("printer.audit.traceBuffered");
    private static final java.util.ArrayDeque<String> EVENTS = new java.util.ArrayDeque<>();

    public static void log(String event, String detail) {
        if (!ENABLED) return;
        String line = "AUDIT_PACKET " + event + " ts=" + System.currentTimeMillis() + " " + detail;
        if (BUFFERED) {
            synchronized (EVENTS) {
                if (EVENTS.size() == 4096) EVENTS.removeFirst();
                EVENTS.addLast(line);
            }
        } else LoggerFactory.getLogger("PrinterPacketTrace").info(line);
    }

    public static void dump(java.nio.file.Path directory, String role) {
        if (!ENABLED || !BUFFERED) return;
        java.util.List<String> lines;
        synchronized (EVENTS) { lines = java.util.List.copyOf(EVENTS); }
        try {
            java.nio.file.Files.write(directory.resolve(role + "-packet-trace.log"), lines,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    public static String packet(Packet<?> packet) {
        if (packet instanceof ServerboundSetCarriedItemPacket carried)
            return "carried slot=" + carried.getSlot();
        if (packet instanceof ServerboundContainerClickPacket click)
            return "click container=" + click.containerId() + " state=" + click.stateId()
                    + " slot=" + click.slotNum() + " button=" + click.buttonNum() + " type=" + click.clickType();
        if (packet instanceof ServerboundUseItemOnPacket use)
            return "use sequence=" + use.getSequence() + " hand=" + use.getHand()
                    + " hit=" + use.getHitResult().getBlockPos().toShortString()
                    + " face=" + use.getHitResult().getDirection();
        return null;
    }

    public static String hand(Player player) {
        return "selected=" + player.getInventory().getSelectedSlot()
                + " hand=" + BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem())
                + " count=" + player.getMainHandItem().getCount();
    }

    public static String authoritativeHand(CompoundTag data) {
        int selected = data.getIntOr("SelectedItemSlot", -1);
        var inventory = data.getListOrEmpty("Inventory");
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getCompoundOrEmpty(i);
            if (stack.getIntOr("Slot", -1) == selected)
                return "selected=" + selected + " hand=" + stack.getStringOr("id", "minecraft:air");
        }
        return "selected=" + selected + " hand=minecraft:air";
    }
}
