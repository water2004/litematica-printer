package me.aleksilassila.litematica.printer.gametest;

import org.edtp.networkchaos.api.ChaosConfig;
import org.edtp.networkchaos.api.ChaosStats;
import org.edtp.networkchaos.api.LinkProfile;
import org.edtp.networkchaos.api.NetworkChaos;
import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.ChainVeinCompat;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public final class PrinterIntegrationGameTest implements FabricClientGameTest {
    private static final BlockPos PLACE_TARGET = new BlockPos(2, 64, 0);
    private static final BlockPos BREAK_TARGET = new BlockPos(3, 64, 0);
    private static final int MATERIAL_COUNT = 4;
    private static final int MAIN_INVENTORY_SHULKER_SLOT = 9;
    private static final int HOTBAR_SHULKER_SLOT = 0;
    private static final int DIRECT_TRANSFER_TICK_LIMIT = 40;
    private static final int DIRECT_LOSS_TRANSFER_TICK_LIMIT = 80;
    private static final int LEGACY_TRANSFER_TICK_LIMIT = 80;
    private static final int QUICK_SHULKER_LOSS_PERCENT = 15;
    private static final long DIRECT_LOSS_SEED = 0L;
    private static final long LEGACY_LOSS_SEED = 9L;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (GameTestMode.isAnyPerformance()) return;
        if (GameTestMode.isBedrockIntegration()) return;
        if (Boolean.getBoolean("litematica-printer.gametest.quickshulkerStress")) return;
        boolean expectChainVein = Boolean.parseBoolean(
                System.getProperty("litematica-printer.gametest.chainvein", "false"));
        String expectedQuickShulker = System.getProperty(
                "litematica-printer.gametest.quickshulker", "current");
        boolean expectQuickShulker = !expectedQuickShulker.equals("none");
        boolean directPacketLoss = Boolean.getBoolean(
                "litematica-printer.gametest.quickshulkerPacketLoss");
        boolean legacyPacketLoss = Boolean.getBoolean(
                "litematica-printer.gametest.quickshulkerLegacyPacketLoss");
        if (directPacketLoss && !usesDirectProtocol(expectedQuickShulker)) {
            throw new AssertionError(
                    "QuickShulker direct packet loss requires direct mode");
        }
        if (legacyPacketLoss && !expectedQuickShulker.equals("legacy")) {
            throw new AssertionError(
                    "QuickShulker legacy packet loss requires legacy mode");
        }
        boolean quickShulkerPacketLoss = directPacketLoss || legacyPacketLoss;

        context.runOnClient(client ->
                assertOptionalMods(expectChainVein, expectedQuickShulker, expectQuickShulker));

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer, expectQuickShulker, MAIN_INVENTORY_SHULKER_SLOT);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 2.5 65 -3.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && !client.player.getAbilities().instabuild
                    && client.level != null
                    && client.level.getBlockState(PLACE_TARGET).isAir()
                    && client.level.getBlockState(PLACE_TARGET.below()).is(Blocks.COBBLESTONE)
                    && inventoryArrived(client.player.getInventory().getItem(
                            MAIN_INVENTORY_SHULKER_SLOT), expectQuickShulker));
            context.runOnClient(client ->
                    assertQuickStorageCapability(expectedQuickShulker));

            context.runOnClient(client -> configureQuickShulker());
            if (expectQuickShulker) {
                testMaterialExtraction(context, expectedQuickShulker,
                        quickShulkerPacketLoss, MAIN_INVENTORY_SHULKER_SLOT);
            }

            context.runOnClient(client -> configureFillPrinter());
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(PLACE_TARGET).is(Blocks.STONE));
            context.waitTicks(5);

            PlacementResult placement = readPlacementResult(
                    singleplayer, MAIN_INVENTORY_SHULKER_SLOT);

            if (!placement.placed()
                    || placement.directStone() != MATERIAL_COUNT - 1
                    || (expectQuickShulker && (placement.boxedStone() != 0
                        || !placement.boxInOriginalSlot()))) {
                throw new AssertionError("Printer did not complete material retrieval and placement: "
                        + placement + ", QuickShulker=" + expectedQuickShulker);
            }

            context.runOnClient(client -> disablePrinter());
            testReturnToOriginalShulker(context, singleplayer, expectedQuickShulker,
                    quickShulkerPacketLoss, MAIN_INVENTORY_SHULKER_SLOT);
            if (expectQuickShulker) {
                testHotbarShulker(context, singleplayer, expectedQuickShulker,
                        quickShulkerPacketLoss);
            }
            testChainVein(context, singleplayer, expectChainVein);
        } finally {
            context.runOnClient(client -> disablePrinter());
        }
    }

    private static void assertOptionalMods(boolean expectChainVein,
                                           String expectedQuickShulker,
                                           boolean expectQuickShulker) {
        if (ModUtils.isChainVeinLoaded() != expectChainVein
                || ChainVeinCompat.isAvailable() != expectChainVein) {
            throw new AssertionError("ChainVeinFabric presence does not match the GameTest matrix");
        }
        if (ModUtils.isQuickShulkerLoaded() != expectQuickShulker) {
            throw new AssertionError("QuickShulker presence does not match the GameTest matrix");
        }
        if (!expectQuickShulker) return;

        String actualVersion = FabricLoader.getInstance()
                .getModContainer("quickshulker")
                .orElseThrow(() -> new AssertionError("QuickShulker container is missing"))
                .getMetadata().getVersion().getFriendlyString();
        boolean versionMatches = switch (expectedQuickShulker) {
            case "current", "direct", "direct-fallback" ->
                    actualVersion.equals("4.0.0-alpha.1-26.2");
            case "legacy" -> actualVersion.equals("3.0.4-26.2");
            default -> false;
        };
        if (!versionMatches) {
            throw new AssertionError("Expected QuickShulker " + expectedQuickShulker
                    + " but Fabric loaded " + actualVersion);
        }
    }

    private static void assertQuickStorageCapability(String expectedQuickShulker) {
        boolean expectApi = expectedQuickShulker.equals("current")
                || expectedQuickShulker.equals("direct")
                || expectedQuickShulker.equals("direct-fallback");
        boolean apiPresent;
        try {
            Class.forName(
                    "net.kyrptonaught.quickshulker.api.shulker.client.ShulkerTransferClient");
            apiPresent = true;
        } catch (ClassNotFoundException error) {
            apiPresent = false;
        }
        if (apiPresent != expectApi) {
            throw new AssertionError("QuickStorage API presence does not match mode "
                    + expectedQuickShulker);
        }

        boolean available = false;
        if (apiPresent) {
            try {
                Class<?> bridge = Class.forName(
                        "me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerDirectBridge");
                var method = bridge.getDeclaredMethod("isAvailable");
                method.setAccessible(true);
                available = Boolean.TRUE.equals(method.invoke(null));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Could not inspect QuickStorage capability", error);
            }
        }

        boolean expectAvailable = usesDirectProtocol(expectedQuickShulker);
        if (available != expectAvailable) {
            throw new AssertionError("QuickStorage live capability was " + available
                    + " in mode " + expectedQuickShulker);
        }

        try {
            var field = QuickShulkerCompat.class.getDeclaredField("selectedPath");
            field.setAccessible(true);
            String selectedPath = field.get(null).toString();
            String expectedPath = expectAvailable ? "DIRECT" : "LEGACY";
            if (!selectedPath.equals(expectedPath)) {
                throw new AssertionError("Printer selected QuickShulker path "
                        + selectedPath + " instead of " + expectedPath);
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inspect the selected QuickShulker path", error);
        }
    }

    private static void testMaterialExtraction(ClientGameTestContext context,
                                               String quickShulkerMode,
                                               boolean packetLoss,
                                               int shulkerSlot) {
        if (packetLoss) startQuickShulkerPacketLoss(context, quickShulkerMode);
        try {
            boolean started = context.computeOnClient(client ->
                    QuickShulkerCompat.requestShulkerItem(
                            client.player, new net.minecraft.world.item.Item[]{Items.STONE}));
            if (!started) {
                throw new AssertionError("QuickShulker did not start material extraction in mode "
                        + quickShulkerMode);
            }
            TransferObservation observation = waitForTransfer(
                    context, quickShulkerMode, packetLoss, MATERIAL_COUNT,
                    usesDirectProtocol(quickShulkerMode) ? 0 : -1,
                    shulkerSlot, "material extraction");
            if (packetLoss) {
                assertQuickShulkerPacketLoss(
                        context, quickShulkerMode, "material extraction", observation);
            }
        } finally {
            if (packetLoss) stopQuickShulkerPacketLoss(context);
        }
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer,
                                     boolean useQuickShulker,
                                     int materialSlot) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();

            for (int x = -4; x <= 8; x++) {
                for (int z = -7; z <= 4; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(PLACE_TARGET.below(), Blocks.COBBLESTONE.defaultBlockState());
            level.setBlockAndUpdate(PLACE_TARGET, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(PLACE_TARGET.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(BREAK_TARGET, Blocks.DIRT.defaultBlockState());

            if (useQuickShulker) {
                ItemStack box = new ItemStack(Items.SHULKER_BOX);
                box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                        List.of(new ItemStack(Items.STONE, MATERIAL_COUNT))));
                player.getInventory().setItem(materialSlot, box);
            } else {
                player.getInventory().setItem(materialSlot,
                        new ItemStack(Items.STONE, MATERIAL_COUNT));
            }
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static boolean inventoryArrived(ItemStack materialStack, boolean useQuickShulker) {
        if (useQuickShulker) {
            return materialStack.is(Items.SHULKER_BOX)
                    && countStoredStone(materialStack) == MATERIAL_COUNT;
        }
        return materialStack.is(Items.STONE) && materialStack.getCount() == MATERIAL_COUNT;
    }

    private static void testHotbarShulker(ClientGameTestContext context,
                                          TestSingleplayerContext singleplayer,
                                          String quickShulkerMode,
                                          boolean packetLoss) {
        prepareHotbarShulker(singleplayer);
        context.waitFor(client -> client.player != null
                && client.level != null
                && client.level.getBlockState(PLACE_TARGET).isAir()
                && inventoryArrived(client.player.getInventory().getItem(
                        HOTBAR_SHULKER_SLOT), true));

        context.runOnClient(client -> configureQuickShulker());
        testMaterialExtraction(context, quickShulkerMode, packetLoss, HOTBAR_SHULKER_SLOT);
        context.runOnClient(client -> configureFillPrinter());
        context.waitFor(client -> client.level != null
                && client.level.getBlockState(PLACE_TARGET).is(Blocks.STONE));
        context.waitTicks(5);

        PlacementResult placement = readPlacementResult(singleplayer, HOTBAR_SHULKER_SLOT);
        if (!placement.placed() || placement.directStone() != MATERIAL_COUNT - 1
                || placement.boxedStone() != 0 || !placement.boxInOriginalSlot()) {
            throw new AssertionError("Printer did not use the in-place hotbar shulker: "
                    + placement + ", QuickShulker=" + quickShulkerMode);
        }

        context.runOnClient(client -> disablePrinter());
        testReturnToOriginalShulker(context, singleplayer, quickShulkerMode,
                packetLoss, HOTBAR_SHULKER_SLOT);
    }

    private static void prepareHotbarShulker(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.getInventory().clearContent();
            server.overworld().setBlockAndUpdate(
                    PLACE_TARGET, Blocks.AIR.defaultBlockState());
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                    List.of(new ItemStack(Items.STONE, MATERIAL_COUNT))));
            player.getInventory().setItem(HOTBAR_SHULKER_SLOT, box);
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static int countStoredStone(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return 0;
        return contents.nonEmptyItemCopyStream()
                .filter(item -> item.is(Items.STONE))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void configureQuickShulker() {
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true);
        Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.MOD);
        Configs.Print.SHULKER_COOLDOWN.setIntegerValue(0);
        Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);
    }

    private static void configureFillPrinter() {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        var selectionManager = DataManager.getSelectionManager();
        if (selectionManager.getSelectionMode() != SelectionMode.SIMPLE) {
            selectionManager.switchSelectionMode();
        }
        AreaSelection selection = DataManager.getSimpleArea();
        Box box = selection.getSubRegionBox(selection.getName());
        if (box == null) box = selection.getSelectedSubRegionBox();
        if (box == null) {
            throw new AssertionError("Litematica simple selection has no box");
        }
        box.setPos1(PLACE_TARGET);
        box.setPos2(PLACE_TARGET);

        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(6.0D);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        configureQuickShulker();
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        Configs.Fill.FILL_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Fill.FILL_BLOCK_MODE.setOptionListValue(FillBlockModeType.BLOCKLIST);
        Configs.Fill.FILL_BLOCK_LIST.setStrings(List.of("minecraft:stone"));
        Configs.Fill.FILL_BLOCK_FACING.setOptionListValue(FillModeFacingType.DOWN);
        Configs.Fill.ENABLED.setBooleanValue(true);
        ModuleManager.FILL.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void disablePrinter() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        ModuleManager.FILL.resetScanState();
    }

    private static void testReturnToOriginalShulker(ClientGameTestContext context,
                                                     TestSingleplayerContext singleplayer,
                                                     String quickShulkerMode,
                                                     boolean packetLoss,
                                                     int shulkerSlot) {
        if (quickShulkerMode.equals("none")) return;

        singleplayer.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
                if (inventory.getItem(slot).isEmpty()) {
                    inventory.setItem(slot, new ItemStack(Items.DIRT, 64));
                }
            }
            player.inventoryMenu.sendAllDataToRemote();
        });

        context.waitFor(client -> client.player != null
                && isInventoryFull(client.player.getInventory()));
        if (packetLoss) startQuickShulkerPacketLoss(context, quickShulkerMode);
        try {
            boolean started = context.computeOnClient(client ->
                    QuickShulkerCompat.requestShulkerItem(
                            client.player, new net.minecraft.world.item.Item[]{Items.COBBLESTONE}));
            if (!started) {
                throw new AssertionError("QuickShulker did not start the queued return operation");
            }
            TransferObservation observation = waitForTransfer(
                    context, quickShulkerMode, packetLoss, 0,
                    usesDirectProtocol(quickShulkerMode) ? MATERIAL_COUNT - 1 : -1,
                    shulkerSlot, "return to original shulker");
            if (packetLoss) {
                assertQuickShulkerPacketLoss(context, quickShulkerMode,
                        "return to original shulker", observation);
            }
        } finally {
            if (packetLoss) stopQuickShulkerPacketLoss(context);
        }
        PlacementResult returned = readPlacementResult(singleplayer, shulkerSlot);
        for (int tick = 0; tick < LEGACY_TRANSFER_TICK_LIMIT
                && (returned.directStone() != 0
                    || returned.boxedStone() != MATERIAL_COUNT - 1); tick++) {
            context.waitTicks(1);
            returned = readPlacementResult(singleplayer, shulkerSlot);
        }
        ReturnClientState clientState = context.computeOnClient(client ->
                new ReturnClientState(
                        QuickShulkerCompat.isBusy(),
                        countLooseStone(client.player.getInventory()),
                        countStoredStone(client.player.getInventory().getItem(shulkerSlot)),
                        client.player.getInventory().getItem(shulkerSlot).is(Items.SHULKER_BOX)));
        if (!returned.placed() || returned.directStone() != 0
                || returned.boxedStone() != MATERIAL_COUNT - 1
                || !returned.boxInOriginalSlot() || clientState.busy()
                || !clientState.boxInOriginalSlot()
                || (usesDirectProtocol(quickShulkerMode)
                    && (clientState.directStone() != 0
                        || clientState.boxedStone() != MATERIAL_COUNT - 1))) {
            throw new AssertionError("QuickShulker did not return material to the original box: "
                    + returned + ", client=" + clientState);
        }
    }

    private static TransferObservation waitForTransfer(ClientGameTestContext context,
                                                       String quickShulkerMode,
                                                       boolean packetLoss,
                                                       int expectedLooseStone,
                                                       int expectedBoxedStone,
                                                       int shulkerSlot,
                                                       String operation) {
        boolean direct = usesDirectProtocol(quickShulkerMode);
        int tickLimit = direct
                ? (packetLoss ? DIRECT_LOSS_TRANSFER_TICK_LIMIT : DIRECT_TRANSFER_TICK_LIMIT)
                : LEGACY_TRANSFER_TICK_LIMIT;
        boolean sawContainer = false;
        TransferClientState last = null;

        for (int tick = 0; tick <= tickLimit; tick++) {
            last = context.computeOnClient(client -> new TransferClientState(
                    QuickShulkerCompat.isBusy(),
                    client.player.containerMenu != client.player.inventoryMenu,
                    countLooseStone(client.player.getInventory()),
                    countStoredStone(client.player.getInventory().getItem(shulkerSlot)),
                    client.player.getInventory().getItem(shulkerSlot).is(Items.SHULKER_BOX)));
            sawContainer |= last.foreignContainer();
            if (!last.boxInOriginalSlot()) {
                throw new AssertionError("QuickShulker moved the box out of player slot "
                        + shulkerSlot + " during " + operation + ": " + last);
            }
            if (direct && sawContainer) {
                throw new AssertionError("Direct QuickStorage unexpectedly opened a container during "
                        + operation);
            }
            if (!last.busy()
                    && last.looseStone() == expectedLooseStone
                    && (expectedBoxedStone < 0
                        || last.boxedStone() == expectedBoxedStone)) {
                if (!direct && !sawContainer && operation.equals("material extraction")) {
                    throw new AssertionError("Legacy QuickShulker no longer used its Screen path during "
                            + operation);
                }
                return new TransferObservation(tick, sawContainer);
            }
            context.waitTicks(1);
        }
        throw new AssertionError("QuickShulker " + operation + " did not finish within "
                + tickLimit + " ticks in mode " + quickShulkerMode + ": " + last);
    }

    private static void startQuickShulkerPacketLoss(ClientGameTestContext context,
                                                     String quickShulkerMode) {
        long seed = usesDirectProtocol(quickShulkerMode)
                ? DIRECT_LOSS_SEED
                : LEGACY_LOSS_SEED;
        context.runOnClient(client -> {
            NetworkChaos.reset();
            double lossRate = QUICK_SHULKER_LOSS_PERCENT / 100.0D;
            LinkProfile lossy = new LinkProfile(
                    lossRate, 0L, 0L, 0.0D, 0.0D, 0L);
            NetworkChaos.enable(new ChaosConfig(
                    lossy,
                    lossy,
                    seed,
                    true,
                    ChaosConfig.ALL_PACKETS,
                    ChaosConfig.NO_PACKETS));
        });
    }

    private static void assertQuickShulkerPacketLoss(ClientGameTestContext context,
                                                      String quickShulkerMode,
                                                      String operation,
                                                      TransferObservation observation) {
        ChaosStats loss = context.computeOnClient(client -> {
            NetworkChaos.disable();
            return NetworkChaos.stats();
        });
        boolean direct = usesDirectProtocol(quickShulkerMode);
        boolean extraction = operation.equals("material extraction");
        if (droppedPackets(loss) == 0 && (direct || extraction)) {
            throw new AssertionError("QuickShulker " + operation
                    + " completed without exercising packet loss: " + loss);
        }
        if (direct && observation.ticks() < 20) {
            throw new AssertionError("Direct QuickStorage did not exercise its retry path during "
                    + operation + ": ticks=" + observation.ticks() + ", loss=" + loss);
        }
        System.out.println("[Litematica Printer GameTest] QuickShulker "
                + quickShulkerMode + ' ' + operation + " under "
                + QUICK_SHULKER_LOSS_PERCENT + "% packet loss: ticks="
                + observation.ticks() + ", loss=" + loss);
    }

    private static void stopQuickShulkerPacketLoss(ClientGameTestContext context) {
        context.runOnClient(client -> NetworkChaos.reset());
    }

    private static long droppedPackets(ChaosStats stats) {
        return stats.clientToServer().dropped()
                + stats.serverToClient().dropped();
    }

    private static boolean usesDirectProtocol(String quickShulkerMode) {
        return quickShulkerMode.equals("current")
                || quickShulkerMode.equals("direct");
    }

    private static PlacementResult readPlacementResult(TestSingleplayerContext singleplayer,
                                                       int shulkerSlot) {
        return singleplayer.getServer().computeOnServer(server -> {
            var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
            return new PlacementResult(
                    server.overworld().getBlockState(PLACE_TARGET).is(Blocks.STONE),
                    countLooseStone(inventory),
                    countStoredStone(inventory.getItem(shulkerSlot)),
                    inventory.getItem(shulkerSlot).is(Items.SHULKER_BOX));
        });
    }

    private static boolean isInventoryFull(net.minecraft.world.entity.player.Inventory inventory) {
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            if (inventory.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    private static int countLooseStone(net.minecraft.world.entity.player.Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.STONE)) count += stack.getCount();
        }
        return count;
    }

    private static void testChainVein(ClientGameTestContext context,
                                      TestSingleplayerContext singleplayer,
                                      boolean expectChainVein) {
        int accepted = context.computeOnClient(client ->
                ChainVeinCompat.queueBreaks(List.of(BREAK_TARGET)));
        if (!expectChainVein) {
            if (accepted != 0) {
                throw new AssertionError("ChainVein break was accepted without ChainVeinFabric");
            }
            boolean stillPresent = singleplayer.getServer().computeOnServer(server ->
                    server.overworld().getBlockState(BREAK_TARGET).is(Blocks.DIRT));
            if (!stillPresent) {
                throw new AssertionError("Break target changed while ChainVeinFabric was absent");
            }
            return;
        }

        if (accepted != 1) {
            throw new AssertionError("ChainVeinFabric rejected the printer break job");
        }
        context.waitFor(client -> client.level != null
                && client.level.getBlockState(BREAK_TARGET).isAir());
        boolean broken = singleplayer.getServer().computeOnServer(server ->
                server.overworld().getBlockState(BREAK_TARGET).isAir());
        if (!broken) {
            throw new AssertionError("ChainVeinFabric accepted the job but did not break the block");
        }
    }

    private record PlacementResult(boolean placed, int directStone, int boxedStone,
                                   boolean boxInOriginalSlot) {
    }

    private record ReturnClientState(boolean busy, int directStone, int boxedStone,
                                     boolean boxInOriginalSlot) {
    }

    private record TransferClientState(boolean busy, boolean foreignContainer,
                                       int looseStone, int boxedStone,
                                       boolean boxInOriginalSlot) {
    }

    private record TransferObservation(int ticks, boolean openedContainer) {
    }
}
