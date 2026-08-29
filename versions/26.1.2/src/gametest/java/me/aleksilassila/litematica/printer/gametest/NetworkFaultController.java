package me.aleksilassila.litematica.printer.gametest;

import net.minecraft.network.protocol.Packet;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only deterministic packet fault injector. */
public final class NetworkFaultController {
    public enum Fault {
        NONE,
        DROP_CARRIED_ITEM_UNTIL_USE_ITEM_ON,
        DROP_USE_ITEM_ON_BURST,
        RANDOM_ALL_PACKETS
    }

    private static final AtomicReference<Fault> ARMED =
            new AtomicReference<>(Fault.NONE);
    private static final AtomicInteger DROPPED_CARRIED_ITEM = new AtomicInteger();
    private static final AtomicInteger DROPPED_USE_ITEM_ON = new AtomicInteger();
    private static final AtomicInteger REMAINING_USE_ITEM_ON_DROPS = new AtomicInteger();
    private static final AtomicLong RANDOM_SEEN = new AtomicLong();
    private static final AtomicLong RANDOM_DROPPED = new AtomicLong();
    private static final AtomicLong RANDOM_DROP_STREAK = new AtomicLong();
    private static final AtomicLong RANDOM_MAX_DROP_STREAK = new AtomicLong();
    private static final Map<String, AtomicLong> RANDOM_CLASS_OCCURRENCES =
            new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> RANDOM_DROPS_BY_CLASS =
            new ConcurrentHashMap<>();
    private static volatile long randomSeed;
    private static volatile int randomLossPercent;

    private NetworkFaultController() {
    }

    public static void reset() {
        ARMED.set(Fault.NONE);
        DROPPED_CARRIED_ITEM.set(0);
        DROPPED_USE_ITEM_ON.set(0);
        REMAINING_USE_ITEM_ON_DROPS.set(0);
        RANDOM_SEEN.set(0);
        RANDOM_DROPPED.set(0);
        RANDOM_DROP_STREAK.set(0);
        RANDOM_MAX_DROP_STREAK.set(0);
        RANDOM_CLASS_OCCURRENCES.clear();
        RANDOM_DROPS_BY_CLASS.clear();
    }

    public static void arm(Fault fault) {
        if (fault != Fault.DROP_CARRIED_ITEM_UNTIL_USE_ITEM_ON) {
            throw new IllegalArgumentException("Use armUseItemOnBurst for " + fault);
        }
        if (!ARMED.compareAndSet(Fault.NONE, fault)) {
            throw new IllegalStateException("Another network fault is already armed: " + ARMED.get());
        }
    }

    public static void armUseItemOnBurst(int packetCount) {
        if (packetCount <= 0) throw new IllegalArgumentException("packetCount must be positive");
        REMAINING_USE_ITEM_ON_DROPS.set(packetCount);
        if (!ARMED.compareAndSet(Fault.NONE, Fault.DROP_USE_ITEM_ON_BURST)) {
            REMAINING_USE_ITEM_ON_DROPS.set(0);
            throw new IllegalStateException("Another network fault is already armed: " + ARMED.get());
        }
    }

    public static void startRandomLoss(long seed, int lossPercent) {
        if (lossPercent <= 0 || lossPercent >= 100) {
            throw new IllegalArgumentException("lossPercent must be between 1 and 99");
        }
        randomSeed = seed;
        randomLossPercent = lossPercent;
        RANDOM_SEEN.set(0);
        RANDOM_DROPPED.set(0);
        RANDOM_DROP_STREAK.set(0);
        RANDOM_MAX_DROP_STREAK.set(0);
        RANDOM_CLASS_OCCURRENCES.clear();
        RANDOM_DROPS_BY_CLASS.clear();
        if (!ARMED.compareAndSet(Fault.NONE, Fault.RANDOM_ALL_PACKETS)) {
            throw new IllegalStateException("Another network fault is already armed: " + ARMED.get());
        }
    }

    public static RandomLossSnapshot stopRandomLoss() {
        ARMED.compareAndSet(Fault.RANDOM_ALL_PACKETS, Fault.NONE);
        Map<String, Long> seenByClass = new TreeMap<>();
        RANDOM_CLASS_OCCURRENCES.forEach((name, count) ->
                seenByClass.put(name, count.get()));
        Map<String, Long> droppedByClass = new TreeMap<>();
        RANDOM_DROPS_BY_CLASS.forEach((name, count) ->
                droppedByClass.put(name, count.get()));
        return new RandomLossSnapshot(
                RANDOM_SEEN.get(), RANDOM_DROPPED.get(),
                RANDOM_MAX_DROP_STREAK.get(), seenByClass, droppedByClass);
    }

    public static boolean dropRandomPacket(Packet<?> packet) {
        if (ARMED.get() != Fault.RANDOM_ALL_PACKETS) return false;
        String className = packet.getClass().getName();
        long occurrence = RANDOM_CLASS_OCCURRENCES
                .computeIfAbsent(className, ignored -> new AtomicLong())
                .incrementAndGet();
        RANDOM_SEEN.incrementAndGet();
        long sample = mix64(randomSeed
                ^ ((long) className.hashCode() << 32)
                ^ occurrence);
        if (Long.remainderUnsigned(sample, 100) >= randomLossPercent) {
            RANDOM_DROP_STREAK.set(0L);
            return false;
        }

        RANDOM_DROPPED.incrementAndGet();
        long streak = RANDOM_DROP_STREAK.incrementAndGet();
        RANDOM_MAX_DROP_STREAK.accumulateAndGet(streak, Math::max);
        RANDOM_DROPS_BY_CLASS
                .computeIfAbsent(className, ignored -> new AtomicLong())
                .incrementAndGet();
        return true;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public static boolean dropIfArmed(Fault fault) {
        if (ARMED.get() != fault) return false;
        if (fault == Fault.DROP_CARRIED_ITEM_UNTIL_USE_ITEM_ON) {
            DROPPED_CARRIED_ITEM.incrementAndGet();
        } else if (fault == Fault.DROP_USE_ITEM_ON_BURST) {
            int remaining = REMAINING_USE_ITEM_ON_DROPS.getAndDecrement();
            if (remaining <= 0) return false;
            DROPPED_USE_ITEM_ON.incrementAndGet();
            if (remaining == 1) ARMED.compareAndSet(fault, Fault.NONE);
        } else {
            throw new IllegalArgumentException("Cannot drop " + fault);
        }
        System.out.println("[Litematica Printer GameTest] Dropped packet: " + fault);
        return true;
    }

    public static void finishCarriedItemLossBurst() {
        if (ARMED.compareAndSet(Fault.DROP_CARRIED_ITEM_UNTIL_USE_ITEM_ON, Fault.NONE)) {
            System.out.println("[Litematica Printer GameTest] Ended carried-item loss burst after "
                    + DROPPED_CARRIED_ITEM.get() + " packet(s)");
        }
    }

    public static int droppedCarriedItemPackets() {
        return DROPPED_CARRIED_ITEM.get();
    }

    public static int droppedUseItemOnPackets() {
        return DROPPED_USE_ITEM_ON.get();
    }

    public record RandomLossSnapshot(
            long seenPackets,
            long droppedPackets,
            long maxConsecutiveDrops,
            Map<String, Long> seenByClass,
            Map<String, Long> droppedByClass) {
    }
}
