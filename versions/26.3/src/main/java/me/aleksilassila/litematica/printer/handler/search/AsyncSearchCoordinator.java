package me.aleksilassila.litematica.printer.handler.search;

import fi.dy.masa.litematica.world.WorldSchematic;
import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.concurrent.AsyncRoundCoordinator;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.SearchBounds;
import me.aleksilassila.litematica.printer.handler.search.SearchGeometry.WorkspaceFilter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Entry point for bounded asynchronous scan rounds; implementation lives in this package. */
public final class AsyncSearchCoordinator {
    public static final AsyncSearchCoordinator INSTANCE = new AsyncSearchCoordinator();
    private final AsyncRoundCoordinator rounds = new AsyncRoundCoordinator("Printer-Search");
    private AsyncSearchCoordinator() {}

    public boolean tryStartRound(List<SearchRequest> requests) {
        if (requests == null || requests.isEmpty()) return false;
        List<SearchRequest> immutableRequests = List.copyOf(requests);
        int configuredThreads = Math.max(
                1, Configs.Core.SEARCH_THREADS.getIntegerValue());
        return rounds.tryStartRound(
                configuredThreads,
                new SearchRound(immutableRequests));
    }

    public boolean isBusy() {
        return rounds.isBusy();
    }

    public record SearchRequest(
            Module owner,
            ClientLevel level,
            @Nullable WorldSchematic schematic,
            SearchBounds bounds,
            WorkspaceFilter workspaceFilter,
            List<SearchBounds> selectionBoxes,
            Vec3 eyePos,
            double range,
            RadiusShapeType shape,
            boolean includeSchematic,
            int snapshotHalo,
            Object context,
            long moduleGeneration,
            long poolGeneration) {
    }
}
