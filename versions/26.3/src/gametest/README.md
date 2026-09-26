# Direct QuickShulker return-timeout regression

Run the recovery regression against the released QuickShulker and NetworkChaos jars
configured in `versions/26.3/gradle.properties`:

```powershell
$env:JAVA_HOME='C:\Users\think book\.jdks\ms-25.0.2'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
./gradlew.bat :26.3:runClientGameTest -PprinterGameTest=true -PprinterGameTestQuickShulker=direct -PprinterGameTestQuickShulkerIntegrationOnly=true --no-daemon --console=plain
```

`QuickShulkerReturnRecoveryGameTest` uses six named scenarios in fresh integrated-server worlds. Each successfully extracts
stone via the printer's direct bridge, fills the remaining inventory slots, then
requests cobblestone, causing the printer to return the extracted stone first.
NetworkChaos drops 0, 1, or 6 outgoing custom-payload packets during this return.
Additional cases drop all six incoming receipts, or allow only a partial return
into a nearly full matching stack. A sixth case removes the first material's
original box and verifies another return candidate still frees a slot.
Its released API filters packet classes, not payload IDs; the isolated fixture
starts injection after login and extraction, with no other outgoing custom-payload
activity. The test verifies the actual drop count, authoritative inventory,
and total stone/cobblestone conservation after placement.

After the return handle finishes, all faults are disabled. The **real fill-mode
consumer** gets 240 ticks to extract cobblestone and place it in the server world.
If it cannot, the test fails without manual recovery. The original pre-fix
diagnostic additionally verified that manually freeing one slot restored progress.

Before the fix, observed on 2026-09-26, printer 1.1.1+26.3, QuickShulker 4.0.1+26.3,
NetworkChaos 1.0.0-alpha.3+26.3:

| Dropped return packets | Return wait | Placement after network recovery |
| --- | --- | --- |
| 0 | 3 ticks | 6 ticks |
| 1 | 22 ticks | 5 ticks |
| 6 | 121 ticks | Not placed after 240 ticks |

In the failing case the server still held all 4 extracted stone outside the box,
but the printer's return list had become empty (`busy=false`, `full=true`,
`returnRecords=0`). Freeing one inventory slot resulted in server-confirmed
cobblestone placement after 6 ticks.

After the fix, all five cases passed on the same released dependencies:

| Fault | Return wait | Placement after clearing faults |
| --- | --- | --- |
| No loss | 3 ticks | 6 ticks |
| One C2S request lost | 22 ticks | 5 ticks |
| All six C2S requests lost | 121 ticks | 7 ticks |
| All six S2C receipts lost | 121 ticks | 5 ticks |
| Partial return (1 of 4 stone) | 2 ticks | 7 ticks |

After extracting the recovery test into its own class for 1.1.2, all six named
scenarios passed again. `UNAVAILABLE_FIRST_RETURN` completed the eligible return
in 3 ticks and placed cobblestone after another 6 ticks, with all materials conserved.

The bridge now retains a return intent while loose material remains, re-resolves
endpoints on the next request, and rotates candidates instead of allowing one
unusable return to block all others. It does not equate a completed request handle
with a completed material return. Legacy and the public API are unchanged.

The recovery class runs automatically in the existing 26.3 Direct CI matrix.
`PrinterIntegrationFixture` contains the shared setup/assertions, while the normal
integration test and recovery test own their respective scenarios. No reflection
into the bridge's private return list is needed; assertions use observable behavior.
These runs
do not claim coverage of every packet-loss scenario or runtime coverage of other
Minecraft versions.

The existing 26.3 integration tests also passed after the fix with
`-PprinterGameTestQuickShulker=direct -PprinterGameTestQuickShulkerPacketLoss=true`
(15% bidirectional loss) and separately with
`-PprinterGameTestQuickShulker=legacy` (QuickShulker 3.1.0-26.3, no faults).
Both cover extraction, actual placement, and return to the original box for
main-inventory and hotbar shulkers.
