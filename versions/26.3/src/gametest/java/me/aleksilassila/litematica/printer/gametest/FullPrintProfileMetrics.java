package me.aleksilassila.litematica.printer.gametest;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only counters delimiting the actual end-to-end print interval. */
public final class FullPrintProfileMetrics {
    public static final String PLACEMENT_EVENT_NAME =
            "litematica.PrinterBlockPlacement";

    private static final AtomicLong USE_ITEM_PACKETS = new AtomicLong();
    private static final AtomicLong CARRIED_ITEM_PACKETS = new AtomicLong();
    private static final AtomicLong CONTAINER_CLICK_PACKETS = new AtomicLong();
    private static final AtomicLong CLIENT_PLACEMENT_CALLS = new AtomicLong();
    private static final AtomicLong CLIENT_PLACEMENT_SUCCESSES = new AtomicLong();
    private static final AtomicLong CLIENT_PLACEMENT_NANOS = new AtomicLong();
    private static final AtomicLong CLIENT_SUCCESSFUL_PLACEMENT_NANOS = new AtomicLong();
    private static final AtomicLong SERVER_PLACEMENT_CALLS = new AtomicLong();
    private static final AtomicLong SERVER_PLACEMENT_SUCCESSES = new AtomicLong();
    private static final AtomicLong SERVER_PLACEMENT_NANOS = new AtomicLong();
    private static final AtomicLong SERVER_SUCCESSFUL_PLACEMENT_NANOS = new AtomicLong();
    private static final AtomicLong CONSUMER_PHASE_CALLS = new AtomicLong();
    private static final AtomicLong CONSUMER_PHASE_NANOS = new AtomicLong();
    private static final AtomicLong CONSUMER_VALIDATION_CALLS = new AtomicLong();
    private static final AtomicLong CONSUMER_VALIDATION_MATCHES = new AtomicLong();
    private static final AtomicLong CONSUMER_VALIDATION_NANOS = new AtomicLong();
    private static final AtomicLong CONSUMER_EXECUTION_CALLS = new AtomicLong();
    private static final AtomicLong CONSUMER_EXECUTION_NANOS = new AtomicLong();
    private static final AtomicLong FIRST_USE_ITEM_NANOS = new AtomicLong();
    private static final AtomicLong LAST_SERVER_SUCCESS_NANOS = new AtomicLong();
    private static final ThreadLocal<ArrayDeque<PlacementCall>> PLACEMENT_CALLS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ConsumerAttempt> CONSUMER_ATTEMPT = new ThreadLocal<>();
    private static final Object CONSUMER_TIMELINE_LOCK = new Object();
    private static final TreeMap<Long, MutableConsumerTick> CONSUMER_TIMELINE = new TreeMap<>();

    private static volatile boolean active;

    private FullPrintProfileMetrics() {
    }

    public static void start() {
        active = false;
        USE_ITEM_PACKETS.set(0L);
        CARRIED_ITEM_PACKETS.set(0L);
        CONTAINER_CLICK_PACKETS.set(0L);
        CLIENT_PLACEMENT_CALLS.set(0L);
        CLIENT_PLACEMENT_SUCCESSES.set(0L);
        CLIENT_PLACEMENT_NANOS.set(0L);
        CLIENT_SUCCESSFUL_PLACEMENT_NANOS.set(0L);
        SERVER_PLACEMENT_CALLS.set(0L);
        SERVER_PLACEMENT_SUCCESSES.set(0L);
        SERVER_PLACEMENT_NANOS.set(0L);
        SERVER_SUCCESSFUL_PLACEMENT_NANOS.set(0L);
        CONSUMER_PHASE_CALLS.set(0L);
        CONSUMER_PHASE_NANOS.set(0L);
        CONSUMER_VALIDATION_CALLS.set(0L);
        CONSUMER_VALIDATION_MATCHES.set(0L);
        CONSUMER_VALIDATION_NANOS.set(0L);
        CONSUMER_EXECUTION_CALLS.set(0L);
        CONSUMER_EXECUTION_NANOS.set(0L);
        FIRST_USE_ITEM_NANOS.set(0L);
        LAST_SERVER_SUCCESS_NANOS.set(0L);
        CONSUMER_ATTEMPT.remove();
        synchronized (CONSUMER_TIMELINE_LOCK) {
            CONSUMER_TIMELINE.clear();
        }
        active = true;
    }

    public static Snapshot stop() {
        active = false;
        return snapshot();
    }

    public static boolean isActive() {
        return active;
    }

    public static long serverPlacementSuccesses() {
        return SERVER_PLACEMENT_SUCCESSES.get();
    }

    public static void recordPacket(Packet<?> packet) {
        if (!active) return;
        if (packet instanceof ServerboundUseItemOnPacket) {
            long now = System.nanoTime();
            USE_ITEM_PACKETS.incrementAndGet();
            FIRST_USE_ITEM_NANOS.compareAndSet(0L, now);
            ConsumerAttempt attempt = CONSUMER_ATTEMPT.get();
            if (attempt != null) attempt.useItemPacket = true;
            mutateConsumerTick(currentClientTick(), tick -> tick.useItemPackets++);
        } else if (packet instanceof ServerboundSetCarriedItemPacket) {
            CARRIED_ITEM_PACKETS.incrementAndGet();
        } else if (packet instanceof ServerboundContainerClickPacket) {
            CONTAINER_CLICK_PACKETS.incrementAndGet();
        }
    }

    public static void recordConsumerPhase(long elapsedNanos) {
        if (!active) return;
        CONSUMER_PHASE_CALLS.incrementAndGet();
        CONSUMER_PHASE_NANOS.addAndGet(elapsedNanos);
        mutateConsumerTick(currentClientTick(), tick -> tick.phases++);
    }

    public static void recordConsumerValidation(
            long elapsedNanos, boolean matched) {
        if (!active) return;
        CONSUMER_VALIDATION_CALLS.incrementAndGet();
        CONSUMER_VALIDATION_NANOS.addAndGet(elapsedNanos);
        if (matched) CONSUMER_VALIDATION_MATCHES.incrementAndGet();
    }

    public static void recordConsumerExecution(long elapsedNanos) {
        if (!active) return;
        CONSUMER_EXECUTION_CALLS.incrementAndGet();
        CONSUMER_EXECUTION_NANOS.addAndGet(elapsedNanos);
    }

    public static void beginConsumerAttempt() {
        if (!active) return;
        CONSUMER_ATTEMPT.set(new ConsumerAttempt(currentClientTick()));
    }

    public static void recordNoValidSide() {
        recordAttemptOutcome(ConsumerOutcome.NO_VALID_SIDE);
    }

    public static void recordItemSwitchWaiting() {
        recordAttemptOutcome(ConsumerOutcome.ITEM_SWITCH_WAITING);
    }

    public static void recordItemUnavailable() {
        recordAttemptOutcome(ConsumerOutcome.ITEM_UNAVAILABLE);
    }

    public static void finishConsumerAttempt(boolean waitingForWorld, boolean waitingForLook) {
        if (!active) return;
        ConsumerAttempt attempt = CONSUMER_ATTEMPT.get();
        CONSUMER_ATTEMPT.remove();
        if (attempt == null) return;

        ConsumerOutcome outcome;
        if (attempt.useItemPacket) {
            outcome = ConsumerOutcome.ACTION_PACKET;
        } else if (attempt.outcome != null) {
            outcome = attempt.outcome;
        } else if (waitingForWorld) {
            outcome = ConsumerOutcome.WAITING_FOR_WORLD;
        } else if (waitingForLook) {
            outcome = ConsumerOutcome.WAITING_FOR_LOOK;
        } else {
            outcome = ConsumerOutcome.NO_PACKET_OTHER;
        }
        mutateConsumerTick(attempt.tick, tick -> {
            tick.attempts++;
            tick.outcomes.merge(outcome, 1L, Long::sum);
        });
    }

    public static String describeConsumerTimeline() {
        synchronized (CONSUMER_TIMELINE_LOCK) {
            if (CONSUMER_TIMELINE.isEmpty()) return "empty";

            long firstTick = CONSUMER_TIMELINE.firstKey();
            long phaseTicks = 0L;
            long attemptTicks = 0L;
            long packetTicks = 0L;
            long emptyPhaseTicks = 0L;
            long noPacketAttemptTicks = 0L;
            long currentNoPacketStreak = 0L;
            long maxNoPacketStreak = 0L;
            EnumMap<ConsumerOutcome, Long> outcomes = new EnumMap<>(ConsumerOutcome.class);
            Map<Long, Long> packetHistogram = new LinkedHashMap<>();
            List<String> idleRanges = new ArrayList<>();
            long rangeStart = Long.MIN_VALUE;
            long rangeEnd = Long.MIN_VALUE;
            EnumMap<ConsumerOutcome, Long> rangeOutcomes = new EnumMap<>(ConsumerOutcome.class);

            for (Map.Entry<Long, MutableConsumerTick> entry : CONSUMER_TIMELINE.entrySet()) {
                long tickNumber = entry.getKey();
                MutableConsumerTick tick = entry.getValue();
                if (tick.phases > 0L) phaseTicks++;
                if (tick.attempts > 0L) attemptTicks++;
                if (tick.useItemPackets > 0L) packetTicks++;
                if (tick.phases > 0L && tick.attempts == 0L) emptyPhaseTicks++;
                packetHistogram.merge(tick.useItemPackets, 1L, Long::sum);
                tick.outcomes.forEach((outcome, count) ->
                        outcomes.merge(outcome, count, Long::sum));

                boolean idleAttemptTick = tick.attempts > 0L && tick.useItemPackets == 0L;
                if (idleAttemptTick) {
                    noPacketAttemptTicks++;
                    currentNoPacketStreak++;
                    maxNoPacketStreak = Math.max(maxNoPacketStreak, currentNoPacketStreak);
                    if (rangeStart == Long.MIN_VALUE) rangeStart = tickNumber;
                    rangeEnd = tickNumber;
                    for (Map.Entry<ConsumerOutcome, Long> outcomeEntry
                            : tick.outcomes.entrySet()) {
                        rangeOutcomes.merge(
                                outcomeEntry.getKey(), outcomeEntry.getValue(), Long::sum);
                    }
                } else {
                    appendIdleRange(idleRanges, firstTick, rangeStart, rangeEnd, rangeOutcomes);
                    rangeStart = Long.MIN_VALUE;
                    rangeEnd = Long.MIN_VALUE;
                    rangeOutcomes = new EnumMap<>(ConsumerOutcome.class);
                    currentNoPacketStreak = 0L;
                }
            }
            appendIdleRange(idleRanges, firstTick, rangeStart, rangeEnd, rangeOutcomes);

            return "phaseTicks=" + phaseTicks
                    + " attemptTicks=" + attemptTicks
                    + " packetTicks=" + packetTicks
                    + " emptyPhaseTicks=" + emptyPhaseTicks
                    + " noPacketAttemptTicks=" + noPacketAttemptTicks
                    + " maxNoPacketAttemptStreak=" + maxNoPacketStreak
                    + " outcomes=" + outcomes
                    + " packetHistogram=" + packetHistogram
                    + " idleRanges=" + idleRanges;
        }
    }

    private static void appendIdleRange(
            List<String> ranges,
            long firstTick,
            long start,
            long end,
            EnumMap<ConsumerOutcome, Long> outcomes) {
        if (start == Long.MIN_VALUE || ranges.size() >= 32) return;
        ranges.add((start - firstTick) + "-" + (end - firstTick) + outcomes);
    }

    private static void recordAttemptOutcome(ConsumerOutcome outcome) {
        if (!active) return;
        ConsumerAttempt attempt = CONSUMER_ATTEMPT.get();
        if (attempt != null && attempt.outcome == null) attempt.outcome = outcome;
    }

    private static long currentClientTick() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level == null ? Long.MIN_VALUE : level.getGameTime();
    }

    private static void mutateConsumerTick(long tickNumber, java.util.function.Consumer<MutableConsumerTick> mutation) {
        if (tickNumber == Long.MIN_VALUE) return;
        synchronized (CONSUMER_TIMELINE_LOCK) {
            mutation.accept(CONSUMER_TIMELINE.computeIfAbsent(
                    tickNumber, ignored -> new MutableConsumerTick()));
        }
    }

    public static void beginBlockItemPlacement(Level level) {
        if (!active) return;
        PlacementEvent event = new PlacementEvent();
        event.clientSide = level.isClientSide();
        event.begin();
        PLACEMENT_CALLS.get().push(new PlacementCall(
                event.clientSide, System.nanoTime(), event));
    }

    public static void endBlockItemPlacement(InteractionResult result) {
        ArrayDeque<PlacementCall> calls = PLACEMENT_CALLS.get();
        if (calls.isEmpty()) return;
        PlacementCall call = calls.pop();
        long elapsed = System.nanoTime() - call.startedNanos();
        boolean success = result != null && result.consumesAction();
        call.event().success = success;
        call.event().commit();

        if (call.clientSide()) {
            CLIENT_PLACEMENT_CALLS.incrementAndGet();
            CLIENT_PLACEMENT_NANOS.addAndGet(elapsed);
            if (success) {
                CLIENT_PLACEMENT_SUCCESSES.incrementAndGet();
                CLIENT_SUCCESSFUL_PLACEMENT_NANOS.addAndGet(elapsed);
            }
        } else {
            SERVER_PLACEMENT_CALLS.incrementAndGet();
            SERVER_PLACEMENT_NANOS.addAndGet(elapsed);
            if (success) {
                SERVER_PLACEMENT_SUCCESSES.incrementAndGet();
                SERVER_SUCCESSFUL_PLACEMENT_NANOS.addAndGet(elapsed);
                LAST_SERVER_SUCCESS_NANOS.set(System.nanoTime());
            }
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                USE_ITEM_PACKETS.get(),
                CARRIED_ITEM_PACKETS.get(),
                CONTAINER_CLICK_PACKETS.get(),
                CLIENT_PLACEMENT_CALLS.get(),
                CLIENT_PLACEMENT_SUCCESSES.get(),
                CLIENT_PLACEMENT_NANOS.get(),
                CLIENT_SUCCESSFUL_PLACEMENT_NANOS.get(),
                SERVER_PLACEMENT_CALLS.get(),
                SERVER_PLACEMENT_SUCCESSES.get(),
                SERVER_PLACEMENT_NANOS.get(),
                SERVER_SUCCESSFUL_PLACEMENT_NANOS.get(),
                CONSUMER_PHASE_CALLS.get(),
                CONSUMER_PHASE_NANOS.get(),
                CONSUMER_VALIDATION_CALLS.get(),
                CONSUMER_VALIDATION_MATCHES.get(),
                CONSUMER_VALIDATION_NANOS.get(),
                CONSUMER_EXECUTION_CALLS.get(),
                CONSUMER_EXECUTION_NANOS.get(),
                FIRST_USE_ITEM_NANOS.get(),
                LAST_SERVER_SUCCESS_NANOS.get());
    }

    private enum ConsumerOutcome {
        ACTION_PACKET,
        NO_VALID_SIDE,
        ITEM_SWITCH_WAITING,
        ITEM_UNAVAILABLE,
        WAITING_FOR_WORLD,
        WAITING_FOR_LOOK,
        NO_PACKET_OTHER
    }

    private static final class ConsumerAttempt {
        private final long tick;
        private boolean useItemPacket;
        private ConsumerOutcome outcome;

        private ConsumerAttempt(long tick) {
            this.tick = tick;
        }
    }

    private static final class MutableConsumerTick {
        private long phases;
        private long attempts;
        private long useItemPackets;
        private final EnumMap<ConsumerOutcome, Long> outcomes =
                new EnumMap<>(ConsumerOutcome.class);
    }

    private record PlacementCall(
            boolean clientSide,
            long startedNanos,
            PlacementEvent event) {
    }

    @Name(PLACEMENT_EVENT_NAME)
    @Label("Printer BlockItem placement")
    @Category({"Litematica Printer", "GameTest"})
    @StackTrace(false)
    public static final class PlacementEvent extends Event {
        @Label("Client side")
        public boolean clientSide;

        @Label("Successful")
        public boolean success;
    }

    public record Snapshot(
            long useItemPackets,
            long carriedItemPackets,
            long containerClickPackets,
            long clientPlacementCalls,
            long clientPlacementSuccesses,
            long clientPlacementNanos,
            long clientSuccessfulPlacementNanos,
            long serverPlacementCalls,
            long serverPlacementSuccesses,
            long serverPlacementNanos,
            long serverSuccessfulPlacementNanos,
            long consumerPhaseCalls,
            long consumerPhaseNanos,
            long consumerValidationCalls,
            long consumerValidationMatches,
            long consumerValidationNanos,
            long consumerExecutionCalls,
            long consumerExecutionNanos,
            long firstUseItemNanos,
            long lastServerSuccessNanos) {
    }
}
