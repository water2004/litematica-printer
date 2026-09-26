package me.aleksilassila.litematica.printer.gametest;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import org.edtp.networkchaos.api.ChaosConfig;
import org.edtp.networkchaos.api.ExactDropRule;
import org.edtp.networkchaos.api.NetworkChaos;
import org.edtp.networkchaos.api.TrafficDirection;

import java.util.List;

import static me.aleksilassila.litematica.printer.gametest.PrinterIntegrationFixture.*;

/** Verifies that a finished transfer cannot strand material-return intentions. */
@SuppressWarnings("UnstableApiUsage")
public final class QuickShulkerReturnRecoveryGameTest implements FabricClientGameTest {
    private static final int SECOND_BOX_SLOT = 10;
    private static final int TRANSFER_LIMIT = 160;
    private static final int RECOVERY_LIMIT = 240;

    private enum Scenario {
        NO_LOSS(0, TrafficDirection.CLIENT_TO_SERVER),
        REQUEST_RETRY(1, TrafficDirection.CLIENT_TO_SERVER),
        REQUEST_TIMEOUT(6, TrafficDirection.CLIENT_TO_SERVER),
        RESPONSE_TIMEOUT(6, TrafficDirection.SERVER_TO_CLIENT),
        PARTIAL_RETURN(0, TrafficDirection.CLIENT_TO_SERVER),
        UNAVAILABLE_FIRST_RETURN(0, TrafficDirection.CLIENT_TO_SERVER);

        final int drops;
        final TrafficDirection direction;

        Scenario(int drops, TrafficDirection direction) {
            this.drops = drops;
            this.direction = direction;
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        if (GameTestMode.isAnyPerformance() || GameTestMode.isBedrockIntegration()
                || Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;
        String mode = System.getProperty("litematica-printer.gametest.quickshulker", "none");
        if (!usesDirectProtocol(mode)) return;
        for (Scenario scenario : Scenario.values()) runScenario(context, mode, scenario);
    }

    private static void runScenario(ClientGameTestContext context, String mode, Scenario scenario) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            prepareWorld(world, true, MAIN_INVENTORY_SHULKER_SLOT);
            prepareBoxes(world, scenario);
            world.getServer().runCommand("gamemode survival @p");
            world.getServer().runCommand("tp @p 2.5 65 -3.5");
            world.getConnection().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && !client.player.getAbilities().instabuild
                    && inventoryArrived(client.player.getInventory().getItem(
                            MAIN_INVENTORY_SHULKER_SLOT), true));
            context.runOnClient(client -> {
                disablePrinter();
                assertQuickStorageCapability(mode);
                configureQuickShulker();
            });
            extract(context, Items.STONE);
            if (scenario == Scenario.UNAVAILABLE_FIRST_RETURN) extract(context, Items.OAK_PLANKS);
            fillInventory(world, scenario);
            context.waitFor(client -> isInventoryFull(client.player.getInventory()));

            int returnTicks = attemptReturn(context, scenario);
            assertReturnOutcome(world, scenario, returnTicks);
            context.runOnClient(client -> {
                configureFillPrinter();
                Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of("minecraft:cobblestone"));
            });
            int placementTicks = waitForPlacement(context, world);
            if (placementTicks == RECOVERY_LIMIT) {
                throw new AssertionError(scenario + ": printer did not recover after "
                        + RECOVERY_LIMIT + " clear-network ticks; "
                        + context.computeOnClient(client -> "busy=" + QuickShulkerCompat.isBusy()
                        + ", full=" + isInventoryFull(client.player.getInventory())));
            }
            assertConservation(world, scenario);
            System.out.println("[ReturnRecovery] " + scenario + ": returnTicks=" + returnTicks
                    + ", placementTicks=" + placementTicks + ", materials conserved");
        } finally {
            context.runOnClient(client -> {
                NetworkChaos.reset();
                disablePrinter();
            });
        }
    }

    private static void prepareBoxes(TestSingleplayerContext world, Scenario scenario) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            if (scenario == Scenario.UNAVAILABLE_FIRST_RETURN) {
                inventory.setItem(SECOND_BOX_SLOT, box(
                        new ItemStack(Items.COBBLESTONE, MATERIAL_COUNT),
                        new ItemStack(Items.OAK_PLANKS, MATERIAL_COUNT)));
            } else {
                inventory.setItem(MAIN_INVENTORY_SHULKER_SLOT, box(
                        new ItemStack(Items.COBBLESTONE, MATERIAL_COUNT),
                        new ItemStack(Items.STONE, MATERIAL_COUNT)));
            }
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static ItemStack box(ItemStack... stacks) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stacks)));
        return box;
    }

    private static void extract(ClientGameTestContext context, Item item) {
        context.runOnClient(client -> {
            if (!QuickShulkerCompat.requestShulkerItem(client.player, new Item[]{item})) {
                throw new AssertionError("Could not start extraction of " + item);
            }
        });
        for (int tick = 0; tick < 40; tick++) {
            if (context.computeOnClient(client -> !QuickShulkerCompat.isBusy()
                    && count(client.player.getInventory(), item, false) == MATERIAL_COUNT)) return;
            context.waitTicks(1);
        }
        throw new AssertionError("Extraction did not complete: " + item);
    }

    private static void fillInventory(TestSingleplayerContext world, Scenario scenario) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            if (scenario == Scenario.PARTIAL_RETURN) {
                inventory.setItem(MAIN_INVENTORY_SHULKER_SLOT, box(
                        new ItemStack(Items.COBBLESTONE, MATERIAL_COUNT),
                        new ItemStack(Items.STONE, 63)));
            } else if (scenario == Scenario.UNAVAILABLE_FIRST_RETURN) {
                // Remove the first, now-empty box. Its retained return cannot start,
                // but the second extracted material must still be returned.
                inventory.setItem(MAIN_INVENTORY_SHULKER_SLOT, new ItemStack(Items.DIRT, 64));
            }
            for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
                if (inventory.getItem(slot).isEmpty()) inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
            }
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static int attemptReturn(ClientGameTestContext context, Scenario scenario) {
        context.runOnClient(client -> {
            NetworkChaos.reset();
            if (scenario.drops > 0) {
                // The released chaos API filters packet classes, not payload IDs.
                // Inject only after login/extraction to isolate the transfer exchange.
                String packet = scenario.direction == TrafficDirection.CLIENT_TO_SERVER
                        ? "Serverbound" : "Clientbound";
                NetworkChaos.enable(ChaosConfig.clear().withExactDropRules(new ExactDropRule(
                        scenario.direction,
                        "net\\.minecraft\\.network\\.protocol\\.common\\." + packet + "CustomPayloadPacket",
                        scenario.drops)));
            }
            if (!QuickShulkerCompat.requestShulkerItem(client.player, new Item[]{Items.COBBLESTONE})) {
                throw new AssertionError(scenario + ": no eligible return started");
            }
        });
        int ticks = 0;
        while (ticks < TRANSFER_LIMIT && context.computeOnClient(client -> QuickShulkerCompat.isBusy())) {
            context.waitTicks(1);
            ticks++;
        }
        var stats = context.computeOnClient(client -> {
            NetworkChaos.disable();
            return NetworkChaos.stats();
        });
        long dropped = stats.exactDropRules().stream().mapToLong(rule -> rule.dropped()).sum();
        if (dropped != scenario.drops || ticks == TRANSFER_LIMIT
                || (scenario.drops == 6 && ticks < 120)) {
            throw new AssertionError(scenario + ": invalid fault fixture, ticks=" + ticks + ", " + stats);
        }
        return ticks;
    }

    private static void assertReturnOutcome(TestSingleplayerContext world, Scenario scenario, int ticks) {
        PlacementResult state = readPlacementResult(world, MAIN_INVENTORY_SHULKER_SLOT);
        int loose = switch (scenario) {
            case REQUEST_TIMEOUT, UNAVAILABLE_FIRST_RETURN -> MATERIAL_COUNT;
            case PARTIAL_RETURN -> 3;
            default -> 0;
        };
        int stored = switch (scenario) {
            case REQUEST_TIMEOUT, UNAVAILABLE_FIRST_RETURN -> 0;
            case PARTIAL_RETURN -> 64;
            default -> MATERIAL_COUNT;
        };
        if (state.directStone() != loose || state.boxedStone() != stored) {
            throw new AssertionError(scenario + ": unexpected server state after " + ticks + " ticks: " + state);
        }
    }

    private static int waitForPlacement(ClientGameTestContext context, TestSingleplayerContext world) {
        for (int tick = 0; tick < RECOVERY_LIMIT; tick++) {
            if (world.getServer().computeOnServer(server -> server.overworld()
                    .getBlockState(PLACE_TARGET).is(Blocks.COBBLESTONE))) return tick;
            context.waitTicks(1);
        }
        return RECOVERY_LIMIT;
    }

    private static void assertConservation(TestSingleplayerContext world, Scenario scenario) {
        world.getServer().runOnServer(server -> {
            var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
            int stone = count(inventory, Items.STONE, true);
            int cobblestone = count(inventory, Items.COBBLESTONE, true);
            int planks = count(inventory, Items.OAK_PLANKS, true);
            if (stone != MATERIAL_COUNT + (scenario == Scenario.PARTIAL_RETURN ? 63 : 0)
                    || cobblestone != MATERIAL_COUNT - 1
                    || planks != (scenario == Scenario.UNAVAILABLE_FIRST_RETURN ? MATERIAL_COUNT : 0)) {
                throw new AssertionError(scenario + ": material conservation failed: stone=" + stone
                        + ", cobblestone=" + cobblestone + ", planks=" + planks);
            }
        });
    }

    private static int count(Inventory inventory, Item item, boolean includeBoxes) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
            var contents = stack.get(DataComponents.CONTAINER);
            if (includeBoxes && contents != null) {
                count += contents.nonEmptyItemCopyStream().filter(stored -> stored.is(item))
                        .mapToInt(ItemStack::getCount).sum();
            }
        }
        return count;
    }
}
