package me.aleksilassila.litematica.printer.core.dependency;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static me.aleksilassila.litematica.printer.core.dependency.DependencyCheck.*;
import static org.junit.jupiter.api.Assertions.*;

class DependencyCheckTest {
    @Test void cycleIsReportedEvenWhenItsNodesAreMissing() {
        var result = evaluate(0, 10, n -> new Node<>(Reason.MISMATCH, List.of(1 - n)));
        assertEquals(Reason.CYCLE, result.reason());
    }

    @Test void deepestUnfinishedDependencyIsReported() {
        var result = evaluate(0, 10, n -> new Node<>(Reason.MISMATCH,
                n < 3 ? List.of(n + 1) : List.of()));
        assertEquals(new Result<>(Reason.MISMATCH, 3), result);
    }

    @Test void sharedDependencyIsNotACycleAndIsReadOnce() {
        Map<Integer, List<Integer>> graph = Map.of(0, List.of(1, 2),
                1, List.of(3), 2, List.of(3), 3, List.of());
        AtomicInteger reads = new AtomicInteger();
        assertTrue(evaluate(0, 4, n -> {
            reads.incrementAndGet();
            return new Node<>(Reason.READY, graph.get(n));
        }).allowed());
        assertEquals(4, reads.get());
    }

    @Test void longChainDoesNotUseTheCallStack() {
        assertTrue(evaluate(0, 20001, n -> new Node<>(Reason.READY,
                n < 20000 ? List.of(n + 1) : List.of())).allowed());
    }

    @Test void limitIsNotATimeoutOrPermissionToPlace() {
        assertEquals(Reason.LIMIT, evaluate(0, 10,
                n -> new Node<>(Reason.READY, List.of(n + 1))).reason());
    }

    @Test void freshEvaluationDoesNotRetainAnEarlierFailure() {
        assertEquals(Reason.UNKNOWN, evaluate(0, 1,
                n -> new Node<Integer>(Reason.UNKNOWN, List.of())).reason());
        assertTrue(evaluate(0, 1,
                n -> new Node<Integer>(Reason.READY, List.of())).allowed());
    }
}
