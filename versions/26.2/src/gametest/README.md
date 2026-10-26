# Cross-stack printing regression (26.2)

Diagnostic helpers live in `gametest.diagnostics`, not production sources.
Packet traces remain inactive unless the cross-stack fixture explicitly starts them.
Historical reproduction findings below are retained, not treated as passing assertions.

The optional 34-case colored-shulker diagnostic has its own entry point. Enable it
with `-PprinterGameTestColoredShulker=true` and
`-PprinterGameTestQuickShulker=direct` (or `legacy`); it no longer overloads the JFR filename.

The extended Quick Shulker stress fixture starts with a full 36-slot inventory:
18 stacks of 64 building items and 18 shulkers. Each shulker contains two more
64-item stacks of its corresponding material, plus an unrelated item. The
schematic requires 180 blocks of each material (3,240 positions), forcing both
the inventory stack and successive shulker slots to be consumed.

Run with the released Quick Shulker 4.0.1 direct protocol, no faults, and a
256-per-tick placement limit:

```powershell
./gradlew.bat :26.2:runClientGameTest -PprinterGameTest=true -PprinterGameTestQuickShulker=direct -PprinterGameTestQuickShulkerStress=true -PprinterGameTestStackBoundary=true -PprinterGameTestPlacementLimit=256 -PprinterGameTestQuickShulkerStressLossPercent=0 -PprinterGameTestFullPrintPacketMode=false '-Pquickshulker=https://github.com/water2004/quickshulker/releases/download/4.0.1-26.2/quickshulker-4.0.1-26.2.jar' --no-daemon --console=plain
```

- Use `-PprinterGameTestFullPrintPacketMode=true` for packet placement.
- Use `-PprinterGameTestQuickShulker=legacy` for the configured released 3.0.4
  screen-based protocol. No local mod checkout is required.
- No-fault cross-stack runs require every server/client target to be correct,
  inventories to agree, all 18 boxes to remain present, zero ghost blocks, zero
  material conservation delta, and no remaining busy/container state.
- With nonzero loss, the existing observational stress assertions still apply:
  a successful Gradle task does **not** imply complete printing. Inspect the
  structured `StressResult` for missing/wrong blocks and stalls.

The existing 1,026-block inventory-only fixture can also use the same limit:

```powershell
./gradlew.bat :26.2:runClientGameTest -PprinterGameTest=true -PprinterGameTestQuickShulker=none -PprinterGameTestFullPrintPerformance=true -PprinterGameTestPlacementLimit=256 -PprinterGameTestFullPrintPacketMode=false --no-daemon --console=plain
```

Default fixtures retain their previous counts and placement limits unless these
properties are explicitly supplied. Printer production code is unchanged.

## Findings (2026-10-08)

With no faults, both inventory-only 1,026-block runs completed at limit 256.
Quick Shulker direct 4.0.1 and legacy 3.0.4 also completed all 3,240 required
positions through successive stack refills. This does not mean packet placement
was always correct: strict no-fault direct/packet runs intermittently found extra
blocks outside the schematic. The original run left 208 items instead of 216;
its original surrounding-world positions were not recorded, so the exact eight
positions cannot be reconstructed from that log alone.

The expanded ledger then reproduced two extra emerald blocks at `(3,72,1)` and
`(3,73,1)`, with no drops and zero per-material total delta. Server request tracing
subsequently reproduced 12 extra obsidian blocks, again with zero total delta.
Paired client/server tracing established the duplicate-placement mechanism:

- Send #6185, client tick 260: intended/clicked `(9,70,15)`, east face; the client
  and server both saw air, and the server placed obsidian at the intended position.
- Send #6237, also client tick 260: the same intended/clicked position and face;
  the client's execution-time and send-time states were still air, while the
  server now saw obsidian. Minecraft placed the next obsidian at `(10,70,15)`.
- The request coordinates did not change in transit. Minecraft's placement
  context uses the clicked cell when replaceable, otherwise the adjacent cell
  on the clicked face. The second request was therefore a legal adjacent
  placement, not an automatically rejected duplicate.

These reproductions used packet placement, place cooldown zero, no Servux hand
confirmation, and **no packet loss**. Consumed jobs can be rediscovered while
their authoritative block updates have not yet reached the client. Live client
world revalidation alone cannot distinguish that in-flight placement from a
still-missing block. No production fix has been applied.

The `[StackBoundaryPlacement]` diagnostics are GameTest-only. FIFO client/server
correlation is enabled only in no-loss runs; it is not used to correlate dropped
packets. The ledger includes all player inventory slots, shulker contents,
cursor items, surrounding-world blocks and nearby dropped items.

With 15% bidirectional loss and the minimal test Servux responder, legacy stalled
in the known old opening path (1,216/3,240, busy for 1,445 sampled ticks). This
remains an observational reference, not a new legacy reliability requirement.
Direct's unfinished tail was observed in `WAITING_FOR_SERVUX_HAND`, not a busy
shulker transaction; keeping the printer enabled and clearing faults completed
the print within another 20 ticks. Two outside blocks were also observed in that
fault run, so recovery does not imply placement-location correctness.
