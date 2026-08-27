package me.aleksilassila.litematica.printer.core.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandConfirmationGateTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";

    @Test
    void matchingServerResponseIsRequiredBeforeConfirmation() {
        HandConfirmationGate gate = new HandConfirmationGate(500L);

        HandConfirmationGate.Decision first =
                gate.evaluate(7, List.of(STONE), STONE, true, 1_000L);
        assertEquals(HandConfirmationGate.Status.WAITING, first.status());
        assertTrue(first.requestRequired());

        assertEquals(
                HandConfirmationGate.Status.CONFIRMED,
                gate.acceptResponse(7, STONE));
        HandConfirmationGate.Decision confirmed =
                gate.evaluate(7, List.of(STONE), STONE, true, 1_001L);
        assertEquals(HandConfirmationGate.Status.CONFIRMED, confirmed.status());
        assertFalse(confirmed.requestRequired());
    }

    @Test
    void mismatchingServerHandNeverConfirmsAndRequiresSwitchRetry() {
        HandConfirmationGate gate = new HandConfirmationGate(500L);
        gate.evaluate(7, List.of(STONE), STONE, true, 1_000L);

        assertEquals(
                HandConfirmationGate.Status.MISMATCH,
                gate.acceptResponse(7, DIRT));
        assertEquals(
                HandConfirmationGate.Status.MISMATCH,
                gate.evaluate(7, List.of(STONE), STONE, true, 1_001L).status());

        gate.markSwitchRetried();
        HandConfirmationGate.Decision retried =
                gate.evaluate(7, List.of(STONE), STONE, true, 1_002L);
        assertEquals(HandConfirmationGate.Status.WAITING, retried.status());
        assertTrue(retried.requestRequired());
    }

    @Test
    void droppedResponseKeepsActionsBlockedAndRetriesQuery() {
        HandConfirmationGate gate = new HandConfirmationGate(500L);
        assertTrue(gate.evaluate(7, List.of(STONE), STONE, true, 1_000L)
                .requestRequired());

        HandConfirmationGate.Decision beforeRetry =
                gate.evaluate(7, List.of(STONE), STONE, true, 1_499L);
        assertEquals(HandConfirmationGate.Status.WAITING, beforeRetry.status());
        assertFalse(beforeRetry.requestRequired());

        HandConfirmationGate.Decision retry =
                gate.evaluate(7, List.of(STONE), STONE, true, 1_500L);
        assertEquals(HandConfirmationGate.Status.WAITING, retry.status());
        assertTrue(retry.requestRequired());
    }

    @Test
    void changingMaterialInvalidatesPreviousConfirmation() {
        HandConfirmationGate gate = new HandConfirmationGate(500L);
        gate.evaluate(7, List.of(STONE), STONE, true, 1_000L);
        gate.acceptResponse(7, STONE);

        HandConfirmationGate.Decision changed =
                gate.evaluate(7, List.of(DIRT), DIRT, true, 1_001L);
        assertEquals(HandConfirmationGate.Status.WAITING, changed.status());
        assertTrue(changed.requestRequired());
    }

    @Test
    void unavailableServerCannotProduceFalseConfirmation() {
        HandConfirmationGate gate = new HandConfirmationGate(500L);
        HandConfirmationGate.Decision decision =
                gate.evaluate(7, List.of(STONE), STONE, false, 1_000L);

        assertEquals(HandConfirmationGate.Status.WAITING, decision.status());
        assertFalse(decision.requestRequired());
        assertEquals(
                HandConfirmationGate.Status.WAITING,
                gate.acceptResponse(7, STONE));
    }
}
