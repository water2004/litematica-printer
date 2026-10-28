package me.aleksilassila.litematica.printer.audit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
public final class AuditServuxCounters {
    public static final AtomicInteger METADATA = new AtomicInteger();
    public static final AtomicInteger ENTITY_REQUESTS = new AtomicInteger();
    public static final AtomicInteger ENTITY_RESPONSES = new AtomicInteger();
    public static final AtomicInteger CONFIRMED = new AtomicInteger();
    public static Map<String, Integer> snapshot() {
        return Map.of("metadataRequests", METADATA.get(), "entityRequests", ENTITY_REQUESTS.get(),
                "entityResponses", ENTITY_RESPONSES.get(), "confirmedChecks", CONFIRMED.get());
    }
}
