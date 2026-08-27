package me.aleksilassila.litematica.printer.core.network;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Version-independent state machine for an authoritative server-hand check.
 *
 * <p>The transport deliberately stays outside core. A Minecraft adapter asks the gate whether
 * a request is required and feeds the server response back into {@link #acceptResponse}.</p>
 */
public final class HandConfirmationGate {
    public enum Status {
        CONFIRMED,
        WAITING,
        MISMATCH
    }

    public record Decision(Status status, boolean requestRequired) {
    }

    private final long retryDelayMillis;

    private int entityId = Integer.MIN_VALUE;
    private Set<String> expectedItemIds = Set.of();
    private String confirmedItemId;
    private boolean responsePending;
    private boolean mismatch;
    private long lastRequestMillis = Long.MIN_VALUE;

    public HandConfirmationGate(long retryDelayMillis) {
        if (retryDelayMillis < 0L) {
            throw new IllegalArgumentException("retryDelayMillis must not be negative");
        }
        this.retryDelayMillis = retryDelayMillis;
    }

    public synchronized Decision evaluate(
            int currentEntityId,
            Collection<String> acceptedItemIds,
            String localItemId,
            boolean serverAvailable,
            long nowMillis) {
        Set<String> normalized = normalize(acceptedItemIds);
        if (normalized.isEmpty()) {
            reset();
            return new Decision(Status.CONFIRMED, false);
        }

        if (this.entityId != currentEntityId
                || !this.expectedItemIds.equals(normalized)) {
            resetForTarget(currentEntityId, normalized);
        }

        if (this.confirmedItemId != null
                && this.confirmedItemId.equals(localItemId)
                && this.expectedItemIds.contains(this.confirmedItemId)) {
            return new Decision(Status.CONFIRMED, false);
        }

        if (this.mismatch) {
            return new Decision(Status.MISMATCH, false);
        }

        if (!serverAvailable) {
            return new Decision(Status.WAITING, false);
        }

        boolean retryDue = !this.responsePending
                || elapsedAtLeast(nowMillis, this.lastRequestMillis, this.retryDelayMillis);
        if (retryDue) {
            this.responsePending = true;
            this.lastRequestMillis = nowMillis;
        }
        return new Decision(Status.WAITING, retryDue);
    }

    /**
     * Accepts only a response for the currently awaited player. Unsolicited or stale-player
     * responses cannot confirm the hand.
     */
    public synchronized Status acceptResponse(int responseEntityId, String serverItemId) {
        if (!this.responsePending || responseEntityId != this.entityId) {
            return Status.WAITING;
        }

        this.responsePending = false;
        String normalizedItemId = Objects.requireNonNullElse(serverItemId, "minecraft:air");
        if (this.expectedItemIds.contains(normalizedItemId)) {
            this.confirmedItemId = normalizedItemId;
            this.mismatch = false;
            return Status.CONFIRMED;
        }

        this.confirmedItemId = null;
        this.mismatch = true;
        return Status.MISMATCH;
    }

    /** Called after the client has re-issued its last switch operation. */
    public synchronized void markSwitchRetried() {
        this.confirmedItemId = null;
        this.responsePending = false;
        this.mismatch = false;
        this.lastRequestMillis = Long.MIN_VALUE;
    }

    public synchronized void invalidate() {
        this.confirmedItemId = null;
        this.responsePending = false;
        this.mismatch = false;
        this.lastRequestMillis = Long.MIN_VALUE;
    }

    public synchronized void reset() {
        this.entityId = Integer.MIN_VALUE;
        this.expectedItemIds = Set.of();
        invalidate();
    }

    private void resetForTarget(int currentEntityId, Set<String> normalizedItemIds) {
        this.entityId = currentEntityId;
        this.expectedItemIds = normalizedItemIds;
        invalidate();
    }

    private static Set<String> normalize(Collection<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String itemId : itemIds) {
            if (itemId != null && !itemId.isBlank()) {
                result.add(itemId);
            }
        }
        return Set.copyOf(result);
    }

    private static boolean elapsedAtLeast(long now, long then, long duration) {
        if (then == Long.MIN_VALUE) {
            return true;
        }
        long elapsed = now - then;
        return elapsed < 0L || elapsed >= duration;
    }
}
