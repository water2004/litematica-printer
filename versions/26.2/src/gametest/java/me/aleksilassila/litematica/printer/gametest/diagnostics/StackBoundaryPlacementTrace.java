package me.aleksilassila.litematica.printer.gametest.diagnostics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Traces real server requests that place material outside the test schematic. */
public final class StackBoundaryPlacementTrace {
    private static volatile Set<BlockPos> targets = Set.of();
    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Job> CLIENT_JOB = new ThreadLocal<>();
    private static final ConcurrentHashMap<Key, ConcurrentLinkedQueue<ClientSend>> SENT =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Request> LAST_PLACEMENT =
            new ConcurrentHashMap<>();
    private static final AtomicLong ORDINAL = new AtomicLong();
    // All packet-mode sequences are currently zero. FIFO correlation is exact
    // only in the no-loss diagnostic runs; do not mispair dropped packets.
    private static final boolean CORRELATE = Integer.getInteger(
            "litematica-printer.gametest.quickshulkerStressLossPercent", 15) == 0;

    private StackBoundaryPlacementTrace() { }

    public static void start(Set<BlockPos> positions) {
        SENT.clear();
        LAST_PLACEMENT.clear();
        ORDINAL.set(0L);
        targets = positions;
    }

    public static void stop() {
        targets = Set.of();
        CURRENT.remove();
        CLIENT_JOB.remove();
        SENT.clear();
        LAST_PLACEMENT.clear();
    }

    public static void beginJob(BlockPos pos) {
        if (targets.isEmpty()) return;
        var client = Minecraft.getInstance();
        CLIENT_JOB.set(new Job(pos, client.level.getBlockState(pos).toString(),
                LitematicaUtils.getBlockState(pos).toString()));
    }

    public static void endJob() {
        CLIENT_JOB.remove();
    }

    public static void clientSent(Packet<?> packet) {
        if (targets.isEmpty() || !CORRELATE
                || !(packet instanceof ServerboundUseItemOnPacket placement)) return;
        var client = Minecraft.getInstance();
        var hit = placement.getHitResult();
        var key = new Key(placement.getSequence(), hit.getBlockPos(), hit.getDirection());
        var send = new ClientSend(ORDINAL.incrementAndGet(), client.level.getGameTime(),
                CLIENT_JOB.get(), client.level.getBlockState(hit.getBlockPos()).toString(),
                client.player.getItemInHand(placement.getHand()).toString());
        SENT.computeIfAbsent(key, ignored -> new ConcurrentLinkedQueue<>()).add(send);
    }

    public static void begin(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        if (targets.isEmpty() || !player.level().getServer().isSameThread()) return;
        var hit = packet.getHitResult();
        var queue = SENT.get(new Key(packet.getSequence(), hit.getBlockPos(), hit.getDirection()));
        CURRENT.set(new Request(packet.getSequence(), hit.getBlockPos(), hit.getDirection(),
                player.level().getBlockState(hit.getBlockPos()).toString(),
                player.getItemInHand(packet.getHand()).toString(),
                queue == null ? null : queue.poll()));
    }

    public static void endRequest() {
        CURRENT.remove();
    }

    public static void placed(BlockPlaceContext context, InteractionResult result) {
        Request request = CURRENT.get();
        if (request == null || context.getLevel().isClientSide()
                || result == null || !result.consumesAction()) return;
        BlockPos placed = context.getClickedPos();
        if (!targets.contains(placed)) {
            System.out.println("[StackBoundaryPlacement] request=" + request
                    + " placed=" + placed.toShortString()
                    + " state=" + context.getLevel().getBlockState(placed)
                    + " remainingHand=" + context.getItemInHand()
                    + " previousPlacementAtClicked=" + LAST_PLACEMENT.get(request.clicked()));
        }
        LAST_PLACEMENT.put(placed.immutable(), request);
    }

    private record Request(int sequence, BlockPos clicked, Direction face,
                           String clickedStateBefore, String handBefore, ClientSend clientView) { }
    private record Key(int sequence, BlockPos clicked, Direction face) { }
    private record Job(BlockPos intended, String currentAtExecution, String required) { }
    private record ClientSend(long ordinal, long tick, Job job,
                              String clickedStateAtSend, String handAtSend) { }
}
