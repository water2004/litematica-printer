package me.aleksilassila.litematica.printer.handler.search;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/** Opt-in scan diagnostics, separate from scheduling. */
public final class SearchProfile {
    private SearchProfile() {}

    static final boolean PROFILE_SCAN = Boolean.getBoolean(
            "litematica-printer.gametest.scanPerformance")
            || Boolean.getBoolean(
                    "litematica-printer.gametest.fullPrintPerformance");
    static final AtomicLong PROFILE_SEQUENCE = new AtomicLong();
    static final ConcurrentLinkedQueue<RoundProfile> ROUND_PROFILES =
            new ConcurrentLinkedQueue<>();
    @Nullable
    static volatile RoundProfile lastRoundProfile;

    public record RoundProfile(
            long sequence,
            long scanNanos,
            long planNanos,
            long captureNanos,
            long searchNanos,
            long completionNanos,
            long publishNanos,
            long capturedTileSnapshots,
            List<RequestProfile> requests) {

        public long activeWorkNanos() {
            return planNanos + captureNanos + searchNanos
                    + completionNanos + publishNanos;
        }
    }

    public record RequestProfile(
            String moduleId,
            long boundsVolume,
            int tileCount,
            long capturedPositions,
            long stateReads,
            long acceptedPositions,
            long jobCount,
            long jobXor,
            long jobSum,
            long chainBreakJobs) {
    }

    public static void resetRoundProfileForTesting() {
        lastRoundProfile = null;
        ROUND_PROFILES.clear();
    }

    public static RoundProfile getLastRoundProfileForTesting() {
        return lastRoundProfile;
    }

    public static List<RoundProfile> drainRoundProfilesForTesting() {
        List<RoundProfile> profiles = new ArrayList<>();
        RoundProfile profile;
        while ((profile = ROUND_PROFILES.poll()) != null) {
            profiles.add(profile);
        }
        return List.copyOf(profiles);
    }
}
