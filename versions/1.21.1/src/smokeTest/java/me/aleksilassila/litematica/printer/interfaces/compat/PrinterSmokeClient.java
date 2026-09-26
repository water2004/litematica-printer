package me.aleksilassila.litematica.printer.interfaces.compat;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Runs only from the separate smoke-test artifact, never the release mod. */
public final class PrinterSmokeClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PrinterSmoke");
    private static final BlockPos PLACE = new BlockPos(1, 64, 0);
    private static final BlockPos BREAK = new BlockPos(2, 64, 0);
    private final List<String> passed = new ArrayList<>();
    private int stage;
    private int ticks;
    private long deadline;
    private CompletableFuture<Void> serverWork;
    private boolean quick;
    private boolean chain;

    @Override
    public void onInitializeClient() {
        deadline = System.currentTimeMillis() + 240_000L;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("PRINTER_SMOKE_STARTED production={}", !FabricLoader.getInstance().isDevelopmentEnvironment());
    }

    private void tick(Minecraft client) {
        if (stage == 100) return;
        try {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Timeout in stage " + stage);
            ticks++;
            switch (stage) {
                case 0 -> {
                    if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
                    for (String id : System.getProperty("printer.smoke.requireMods", "").split(",")) {
                        if (!id.isBlank()) check(FabricLoader.getInstance().isModLoaded(id), "Required mod missing: " + id);
                    }
                    quick = FabricLoader.getInstance().isModLoaded("quickshulker");
                    chain = FabricLoader.getInstance().isModLoaded("chainveinfabric");
                    client.options.pauseOnLostFocus = false;
                    client.options.renderDistance().set(3);
                    client.options.simulationDistance().set(5);
                    Configs.Core.UPDATE_CHECK.setBooleanValue(false);
                    Configs.Core.AUTO_DISABLE_PRINTER.setBooleanValue(false);
                    ConfigUi screen = new ConfigUi(client.screen);
                    client.setScreen(screen);
                    check(!screen.getConfigs().isEmpty(), "Config UI has no entries");
                    mark("config-ui-and-mixins");
                    advance(1);
                }
                case 1 -> {
                    if (ticks < 10) return;
                    if (chain && ticks < 30) {
                        if (ticks % 5 == 0) {
                            Class<?> gui = Class.forName("org.edtp.chainveinfabric.client.gui.malilib.GuiChainVein");
                            if (ticks == 10) client.setScreen((Screen) gui.getConstructor().newInstance());
                            Class<?> tab = Class.forName(gui.getName() + "$Tab");
                            var select = gui.getDeclaredMethod("selectTab", tab);
                            select.setAccessible(true);
                            select.invoke(client.screen, tab.getEnumConstants()[(ticks - 10) / 5]);
                        }
                        return;
                    }
                    if (chain) mark("chainvein-four-config-tabs");
                    LevelSettings settings = new LevelSettings("Printer smoke", GameType.SURVIVAL, false,
                            Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                    client.createWorldOpenFlows().createFreshLevel("printer-smoke-" + System.currentTimeMillis(),
                            settings, new WorldOptions(1234L, false, false),
                            access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                    .value().createWorldDimensions(), new TitleScreen());
                    advance(2);
                }
                case 2 -> {
                    if (client.player == null || client.level == null || client.getSingleplayerServer() == null) return;
                    client.setScreen(null);
                    serverWork = onServer(client, server -> {
                        var level = server.overworld();
                        var player = server.getPlayerList().getPlayers().getFirst();
                        for (int x = -4; x <= 6; x++) {
                            for (int z = -4; z <= 5; z++) {
                                level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.COBBLESTONE.defaultBlockState());
                            }
                        }
                        level.setBlockAndUpdate(PLACE, Blocks.AIR.defaultBlockState());
                        player.teleportTo(0.5, 64, 2.5);
                        player.getInventory().clearContent();
                        player.getInventory().setItem(9, new ItemStack(Items.STONE, 64));
                        player.inventoryMenu.sendAllDataToRemote();
                    });
                    advance(3);
                }
                case 3 -> {
                    if (!done() || client.player.getY() < 63 || !client.player.getInventory().getItem(9).is(Items.STONE)) return;
                    configureFill(false);
                    advance(4);
                }
                case 4 -> {
                    if (!client.level.getBlockState(PLACE).is(Blocks.STONE)) return;
                    disable();
                    serverWork = onServer(client, server -> {
                        check(server.overworld().getBlockState(PLACE).is(Blocks.STONE), "Fill was only predicted locally");
                        check(countStone(server) == 63, "Survival fill must consume exactly one stone");
                    });
                    advance(5);
                }
                case 5 -> {
                    if (!done()) return;
                    mark("survival-fill-authoritative-inventory");
                    if (!quick) {
                        advance(9);
                        return;
                    }
                    QuickShulkerDirectApi api = QuickShulkerDirectApi.load();
                    check(api != null && api.isUsable(), "Quick Shulker reflection API could not load");
                    if (!api.probeServerCapability()) return;
                    mark("quickshulker-production-reflection-and-handshake");
                    serverWork = onServer(client, server -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        server.overworld().setBlockAndUpdate(PLACE, Blocks.AIR.defaultBlockState());
                        player.getInventory().clearContent();
                        ItemStack box = new ItemStack(Items.SHULKER_BOX);
                        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, 64))));
                        player.getInventory().setItem(9, box);
                        player.inventoryMenu.sendAllDataToRemote();
                    });
                    advance(6);
                }
                case 6 -> {
                    if (!done() || !client.level.getBlockState(PLACE).isAir()
                            || !client.player.getInventory().getItem(9).is(Items.SHULKER_BOX)) return;
                    configureFill(true);
                    advance(7);
                }
                case 7 -> {
                    if (!client.level.getBlockState(PLACE).is(Blocks.STONE)) return;
                    disable();
                    serverWork = onServer(client, server -> {
                        check(server.overworld().getBlockState(PLACE).is(Blocks.STONE), "Shulker fill was only predicted locally");
                        check(countStone(server) == 63, "Shulker replenishment lost or duplicated stone");
                        var box = server.getPlayerList().getPlayers().getFirst().getInventory().getItem(9);
                        check(box.is(Items.SHULKER_BOX), "Shulker did not remain in original slot");
                        check(box.get(DataComponents.CONTAINER).stream().noneMatch(s -> s.is(Items.STONE)), "Shulker was not extracted");
                    });
                    advance(8);
                }
                case 8 -> {
                    if (!done()) return;
                    mark("quickshulker-replenishment-and-survival-fill");
                    advance(9);
                }
                case 9 -> {
                    if (!chain) {
                        advance(12);
                        return;
                    }
                    serverWork = onServer(client, server -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        server.overworld().setBlockAndUpdate(BREAK, Blocks.STONE.defaultBlockState());
                        player.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
                        player.getInventory().selected = 0;
                        player.inventoryMenu.sendAllDataToRemote();
                    });
                    advance(10);
                }
                case 10 -> {
                    if (!done() || !client.level.getBlockState(BREAK).is(Blocks.STONE)
                            || !client.player.getInventory().getItem(0).is(Items.IRON_PICKAXE)) return;
                    client.player.getInventory().selected = 0;
                    check(ChainVeinCompat.isAvailable(), "ChainVein detection failed");
                    int queued = ChainVeinCompat.queueBreaks(List.of(BREAK));
                    if (queued == 0 && ticks < 100) return;
                    check(queued == 1, "ChainVein reflection did not accept mining job");
                    advance(11);
                }
                case 11 -> {
                    if (!client.level.getBlockState(BREAK).isAir()) return;
                    serverWork = onServer(client, server -> check(server.overworld().getBlockState(BREAK).isAir(), "ChainVein break not authoritative"));
                    advance(12);
                }
                case 12 -> {
                    if (!done()) return;
                    if (chain) mark("chainvein-production-reflection-and-mining");
                    mark("client-world-hud-and-all-loaded-mod-mixins");
                    AreaSelection area = DataManager.getSimpleArea();
                    LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(client.level, area,
                            new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                            "PrinterSmoke", message -> { throw new AssertionError(message); });
                    check(schematic != null, "Could not capture real schematic");
                    var placement = SchematicPlacement.createFor(schematic, area.getEffectiveOrigin(), "Smoke placement", true, true);
                    DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
                    serverWork = resetTarget(client, Items.STONE, Blocks.AIR);
                    advance(13);
                }
                case 13 -> {
                    if (!targetReady(client, Items.STONE)) return;
                    var world = SchematicWorldHandler.getSchematicWorld();
                    if (world == null || !world.getBlockState(PLACE).is(Blocks.STONE)) return;
                    configurePrint(false);
                    advance(14);
                }
                case 14 -> {
                    if (!client.level.getBlockState(PLACE).is(Blocks.STONE)) return;
                    disable();
                    serverWork = onServer(client, server -> {
                        check(server.overworld().getBlockState(PLACE).is(Blocks.STONE), "Schematic print not authoritative");
                        check(countStone(server) == 3, "Schematic print inventory mismatch");
                    });
                    advance(15);
                }
                case 15 -> {
                    if (!done()) return;
                    mark("real-litematica-schematic-print");
                    serverWork = resetTarget(client, Items.STONE, Blocks.AIR);
                    advance(16);
                }
                case 16 -> {
                    if (!targetReady(client, Items.STONE)) return;
                    configurePrint(true);
                    advance(17);
                }
                case 17 -> {
                    if (!client.level.getBlockState(PLACE).is(Blocks.STONE)) return;
                    disable();
                    serverWork = onServer(client, server -> {
                        check(server.overworld().getBlockState(PLACE).is(Blocks.STONE), "Packet schematic print not authoritative");
                        check(countStone(server) == 3, "Packet schematic print inventory mismatch");
                    });
                    advance(18);
                }
                case 18 -> {
                    if (!done()) return;
                    mark("packet-mode-schematic-print-and-inventory-swap");
                    serverWork = resetTarget(client, Items.SAND, Blocks.WATER);
                    advance(19);
                }
                case 19 -> {
                    if (!done() || !client.level.getBlockState(PLACE).is(Blocks.WATER)
                            || !client.player.getInventory().getItem(9).is(Items.SAND)) return;
                    configureFill(false);
                    Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
                    Configs.Fill.ENABLED.setBooleanValue(false);
                    Configs.Fluid.FLUID_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
                    Configs.Fluid.FILL_FLOWING_FLUID.setBooleanValue(true);
                    Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.setStrings(List.of("minecraft:sand"));
                    Configs.Fluid.FLUID_LIST.setStrings(List.of("minecraft:water"));
                    Configs.Fluid.ENABLED.setBooleanValue(true);
                    ModuleManager.FLUID_REMOVAL.resetScanState();
                    advance(20);
                }
                case 20 -> {
                    if (!client.level.getBlockState(PLACE).is(Blocks.SAND)) return;
                    disable();
                    serverWork = onServer(client, server -> {
                        check(server.overworld().getBlockState(PLACE).is(Blocks.SAND), "Fluid removal not authoritative");
                        check(server.overworld().getFluidState(PLACE).isEmpty(), "Fluid was not removed");
                        check(server.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.SAND) == 3,
                                "Fluid replacement inventory mismatch");
                    });
                    advance(21);
                }
                case 21 -> {
                    if (!done()) return;
                    mark("fluid-removal-with-sand-survival");
                    finish(client, null);
                }
            }
        } catch (Throwable failure) {
            finish(client, failure);
        }
    }

    private static void configureFill(boolean shulker) {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        var manager = DataManager.getSelectionManager();
        if (manager.getSelectionMode() != SelectionMode.SIMPLE) manager.switchSelectionMode();
        AreaSelection selection = DataManager.getSimpleArea();
        Box box = selection.getSubRegionBox(selection.getName());
        if (box == null) box = selection.getSelectedSubRegionBox();
        check(box != null, "Missing Litematica simple selection");
        box.setPos1(PLACE);
        box.setPos2(PLACE);
        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(6);
        Configs.Core.RENDER_HUD.setBooleanValue(true);
        Configs.Core.DEBUG_OUTPUT.setBooleanValue(true);
        Configs.Highlight.HIGHLIGHT_ENABLED.setBooleanValue(true);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0);
        Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(1);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(0);
        Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(shulker);
        Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.MOD);
        Configs.Print.SHULKER_COOLDOWN.setIntegerValue(0);
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

    private static CompletableFuture<Void> resetTarget(Minecraft client, Item item,
                                                        Block block) {
        return onServer(client, server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.overworld().setBlockAndUpdate(PLACE, block.defaultBlockState());
            player.getInventory().clearContent();
            player.getInventory().setItem(9, new ItemStack(item, 4));
            player.getInventory().selected = 0;
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private boolean targetReady(Minecraft client, Item item) {
        return done() && client.level.getBlockState(PLACE).isAir() && client.player.getInventory().getItem(9).is(item);
    }

    private static void configurePrint(boolean packet) {
        configureFill(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(packet);
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false);
        Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
        Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(true);
        ModuleManager.PRINT.resetScanState();
    }

    private static int countStone(MinecraftServer server) {
        var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++)
            if (inventory.getItem(i).is(Items.STONE)) count += inventory.getItem(i).getCount();
        return count;
    }

    private static void disable() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        ModuleManager.FILL.resetScanState();
        ModuleManager.PRINT.resetScanState();
        ModuleManager.FLUID_REMOVAL.resetScanState();
    }

    private void advance(int next) {
        stage = next;
        ticks = 0;
        deadline = System.currentTimeMillis() + 120_000L;
        LOG.info("PRINTER_SMOKE_STAGE {}", next);
    }
    private void mark(String name) {
        if (!passed.contains(name)) {
            passed.add(name);
            LOG.info("PRINTER_SMOKE_PASS {}", name);
        }
    }
    private boolean done() {
        if (serverWork == null) return true;
        if (!serverWork.isDone()) return false;
        serverWork.join();
        return true;
    }
    private static CompletableFuture<Void> onServer(Minecraft client, Consumer<MinecraftServer> task) {
        MinecraftServer server = client.getSingleplayerServer();
        return CompletableFuture.runAsync(() -> task.accept(server), server);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private void finish(Minecraft client, Throwable failure) {
        stage = 100;
        disable();
        String report = (failure == null ? "PASS\n" : "FAIL\n" + failure + "\n") + String.join("\n", passed) + "\n";
        try {
            Files.writeString(Path.of(System.getProperty("printer.smoke.result", "printer-smoke-result.txt")), report);
        } catch (Exception writeFailure) {
            LOG.error("Cannot write smoke result", writeFailure);
        }
        if (failure != null) {
            LOG.error("PRINTER_SMOKE_FAILED", failure);
        } else {
            LOG.info("PRINTER_SMOKE_COMPLETE {}", passed);
        }
        client.stop();
    }
}
