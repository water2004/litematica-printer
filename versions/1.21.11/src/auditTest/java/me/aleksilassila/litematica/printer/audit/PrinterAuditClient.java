package me.aleksilassila.litematica.printer.audit;

import com.google.gson.GsonBuilder;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.ShulkerSource;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.edtp.networkchaos.api.ChaosConfig;
import org.edtp.networkchaos.api.ChaosStats;
import org.edtp.networkchaos.api.LinkProfile;
import org.edtp.networkchaos.api.NetworkChaos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Production-client backport of the 26.2 BedrockIntegrationGameTest and
 * QuickShulkerStressGameTest fixtures. Fabric's newer client GameTest API does
 * not exist on this target. The gameplay, server assertions, loss injector,
 * material layout, loss seed, and observation window remain real.
 * This separate audit artifact is never included in the production mod JAR.
 */
public final class PrinterAuditClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PrinterAudit");
    private static final BlockPos BEDROCK = new BlockPos(2, 64, 0);
    private static final List<Block> MATERIALS = List.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS,
            Blocks.BIRCH_PLANKS, Blocks.BRICKS, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE,
            Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.POLISHED_ANDESITE,
            Blocks.TERRACOTTA, Blocks.GLASS, Blocks.OBSIDIAN);
    private static final List<Item> NOISE = List.of(
            Items.STICK, Items.APPLE, Items.COAL, Items.CHARCOAL, Items.FEATHER, Items.FLINT,
            Items.BONE, Items.STRING, Items.LEATHER, Items.PAPER, Items.BOOK, Items.CLAY_BALL,
            Items.BRICK, Items.SNOWBALL, Items.EGG, Items.WHEAT, Items.CARROT, Items.POTATO);
    private static final List<BlockPos> TARGETS = targets();
    private final boolean servux = Boolean.getBoolean("printer.audit.servux");
    private final String test = System.getProperty("printer.audit.case", "bedrock");
    private final String miner = System.getProperty("printer.audit.miner", "none");
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final boolean dedicated = Boolean.getBoolean("printer.audit.dedicated");
    private final java.util.concurrent.ExecutorService bridgeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
            runnable -> { Thread thread = new Thread(runnable, "audit-fixture-bridge"); thread.setDaemon(true); return thread; });
    private long requestId;
    private int phase;
    private int ticks;
    private int elapsed;
    private int busyStreak;
    private int maxBusyStreak;
    private int convergedStreak;
    private int firstServerComplete = -1;
    private boolean foreignContainer;
    private boolean directObserved;
    private long deadline;
    private CompletableFuture<Void> work;
    private CompletableFuture<Map<String, Object>> snapshot;
    private Map<String, Object> lastServer;

    @Override public void onInitializeClient() {
        report.put("servuxEnabled", servux);
        report.put("case", test);
        report.put("samePositionCooldown", Integer.getInteger("printer.audit.placeCooldown", 0));
        report.put("packetTraceBuffered", Boolean.getBoolean("printer.audit.traceBuffered"));
        report.put("faultProfile", AuditFaultProfile.description());
        report.put("targetLayout", Boolean.getBoolean("printer.audit.rotatedMaterials") ? "rotating material rows" : "upstream material columns");
        report.put("minecraft", FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString());
        report.put("miner", miner);
        report.put("harness", "production-client backport of upstream 26.2 client GameTest cases");
        report.put("fabricClientGameTestApi", false);
        report.put("transport", dedicated ? "separate JVMs, real loopback TCP" : "integrated server");
        report.put("production", !FabricLoader.getInstance().isDevelopmentEnvironment());
        report.put("mods", FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + "=" + mod.getMetadata().getVersion().getFriendlyString()).toList());
        deadline = System.currentTimeMillis() + 240_000;
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LOG.info("AUDIT_STARTED {} miner={}", test, miner);
    }

    private void tick(Minecraft client) {
        if (phase == 100) return;
        try {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Timeout phase=" + phase);
            ticks++;
            if (phase == 0) {
                if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
                client.options.pauseOnLostFocus = false;
                client.options.renderDistance().set(3);
                if (servux) fi.dy.masa.litematica.config.Configs.Generic.ENTITY_DATA_SYNC.setBooleanValue(false);
                Configs.Print.SERVUX_HAND_CONFIRMATION.setBooleanValue(servux);
                me.aleksilassila.litematica.printer.utils.ServuxHandItemConfirmation.reset();
                Configs.Core.UPDATE_CHECK.setBooleanValue(false);
                Configs.Core.AUTO_DISABLE_PRINTER.setBooleanValue(false);
                if (dedicated) {
                    String address = System.getProperty("printer.audit.address");
                    var data = new net.minecraft.client.multiplayer.ServerData("Printer audit", address,
                            net.minecraft.client.multiplayer.ServerData.Type.OTHER);
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(client.screen, client,
                            net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address), data, false, null);
                    advance(1); return;
                }
                LevelSettings settings = new LevelSettings("Printer audit", GameType.SURVIVAL, false,
                        Difficulty.PEACEFUL, true, new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
                client.createWorldOpenFlows().createFreshLevel("audit-" + System.currentTimeMillis(), settings,
                        new WorldOptions(1234L, false, false),
                        access -> access.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(), new TitleScreen());
                advance(1);
                return;
            }
            if (phase == 1) {
                if (client.player == null || client.level == null || (!dedicated && client.getSingleplayerServer() == null)
                        || client.screen != null) return;
                disable();
                if (servux) fi.dy.masa.litematica.data.EntityDataManager.getInstance().requestMetadata();
                if (test.equals("bedrock")) {
                    check(FabricLoader.getInstance().isModLoaded(miner), "Required real miner is missing: " + miner);
                    check(BedrockCompat.isAvailable(), "Printer cannot resolve the actual miner API");
                    work = server(client, this::prepareBedrock);
                } else if (test.equals("stress")) {
                    check(FabricLoader.getInstance().isModLoaded("quickshulker"), "Quick Shulker missing");
                    check(FabricLoader.getInstance().isModLoaded("network_chaos_fabric"), "Real loss injector missing");
                    NetworkChaos.reset();
                    work = dedicated ? rpc("prepare").thenAccept(ignored -> {}) : server(client, this::prepareStress);
                } else throw new AssertionError("Unknown audit case " + test);
                advance(2);
                return;
            }
            if (test.equals("bedrock")) bedrockTick(client);
            else stressTick(client);
        } catch (Throwable error) { finish(client, error); }
    }

    private void prepareBedrock(MinecraftServer server) {
        var level = server.overworld();
        var player = server.getPlayerList().getPlayers().getFirst();
        for (int x = -5; x <= 9; x++) for (int z = -7; z <= 5; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.COBBLESTONE.defaultBlockState());
            for (int y = 64; y <= 66; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(BEDROCK, Blocks.BEDROCK.defaultBlockState());
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        inventory.setItem(0, new ItemStack(Items.PISTON, 32));
        inventory.setItem(1, new ItemStack(Items.REDSTONE_TORCH, 32));
        inventory.setItem(2, new ItemStack(Items.LEVER, 32));
        inventory.setItem(3, new ItemStack(Items.SLIME_BLOCK, 32));
        ItemStack pickaxe = new ItemStack(Items.NETHERITE_PICKAXE);
        pickaxe.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.EFFICIENCY), 5);
        inventory.setItem(4, pickaxe);
        inventory.setSelectedSlot(0);
        player.addEffect(new MobEffectInstance(MobEffects.HASTE, 20 * 120, 1, false, false));
        player.teleportTo(2.5, 65, -3.5);
        player.inventoryMenu.sendAllDataToRemote();
    }

    private void bedrockTick(Minecraft client) {
        if (phase == 2) {
            if (!done() || !client.level.getBlockState(BEDROCK).is(Blocks.BEDROCK)
                    || client.player.getInventory().countItem(Items.PISTON) < 2
                    || client.player.getY() < 63) return;
            selection(BEDROCK, BEDROCK);
            Configs.Core.LAG_CHECK.setBooleanValue(false);
            Configs.Core.WORK_RANGE.setDoubleValue(8);
            Configs.Bedrock.BREAK_INTERVAL.setIntegerValue(0);
            Configs.Bedrock.BREAK_BLOCKS_PER_TICK.setIntegerValue(1);
            Configs.Bedrock.ENABLED.setBooleanValue(true);
            ModuleManager.BEDROCK.resetScanState();
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            advance(3);
            deadline = System.currentTimeMillis() + 110_000;
        } else if (phase == 3) {
            if (ticks > 1800) throw new AssertionError("Actual miner did not break server bedrock within 1800 ticks");
            if (snapshot == null && ticks % 5 == 0) snapshot = CompletableFuture.supplyAsync(() -> {
                var server = client.getSingleplayerServer();
                var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
                return Map.<String, Object>of("targetIsAir", server.overworld().getBlockState(BEDROCK).isAir(),
                        "pistons", inventory.countItem(Items.PISTON),
                        "torches", inventory.countItem(Items.REDSTONE_TORCH), "levers", inventory.countItem(Items.LEVER));
            }, client.getSingleplayerServer());
            if (snapshot != null && snapshot.isDone()) {
                lastServer = snapshot.join(); snapshot = null;
                report.put("server", lastServer);
                if (Boolean.TRUE.equals(lastServer.get("targetIsAir")) && client.level.getBlockState(BEDROCK).isAir()) {
                    report.put("elapsedTicks", ticks);
                    finish(client, null);
                }
            }
        }
    }

    private void prepareStress(MinecraftServer server) {
        var level = server.overworld();
        var player = server.getPlayerList().getPlayers().getFirst();
        var range = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        check(range != null, "Interaction range attribute missing");
        range.setBaseValue(32);
        for (int x = -9; x <= 10; x++) for (int z = -1; z <= 10; z++)
            level.setBlockAndUpdate(new BlockPos(x, 63, z), Blocks.COBBLESTONE.defaultBlockState());
        for (int i = 0; i < TARGETS.size(); i++) level.setBlockAndUpdate(TARGETS.get(i), expected(i).defaultBlockState());
        player.teleportTo(0.5, 65, 0.5);
        player.inventoryMenu.sendAllDataToRemote();
    }

    private void stressTick(Minecraft client) throws Exception {
        if (phase == 2) {
            if (!done() || client.player.getY() < 63 || countCorrect(client.level) != TARGETS.size()) return;
            AreaSelection area = selection(TARGETS.getFirst(), TARGETS.getLast());
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(client.level, area,
                    new LitematicaSchematic.SchematicSaveInfo(false, false, true, false),
                    "PrinterAudit", message -> { throw new AssertionError(message); });
            check(schematic != null, "Cannot capture real stress schematic");
            DataManager.getSchematicPlacementManager().addSchematicPlacement(
                    SchematicPlacement.createFor(schematic, area.getEffectiveOrigin(), "Stress audit", true, true), false);
            work = dedicated ? rpc("clear").thenAccept(ignored -> {}) : server(client, server -> {
                TARGETS.forEach(pos -> server.overworld().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
                var player = server.getPlayerList().getPlayers().getFirst();
                Inventory inventory = player.getInventory(); inventory.clearContent(); inventory.setSelectedSlot(0);
                for (int i = 0; i < MATERIALS.size(); i++) {
                    inventory.setItem(i, new ItemStack(MATERIALS.get(i).asItem(), 2));
                    ItemStack box = new ItemStack(Items.SHULKER_BOX);
                    box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                            new ItemStack(MATERIALS.get(i).asItem(), 10), new ItemStack(NOISE.get(i), i % 8 + 1))));
                    inventory.setItem(MATERIALS.size() + i, box);
                }
                player.inventoryMenu.sendAllDataToRemote();
            });
            advance(3);
        } else if (phase == 3) {
            if (!done() || TARGETS.stream().anyMatch(pos -> !client.level.getBlockState(pos).isAir())) return;
            var world = SchematicWorldHandler.getSchematicWorld();
            if (world == null || countCorrect(world) != TARGETS.size()) return;
            if (countInventory(client.player.getInventory(), ItemStack.EMPTY) != 216) return;
            if (servux && !fi.dy.masa.litematica.data.EntityDataManager.getInstance().hasServuxServer()) return;
            report.put("servuxServerDetected", fi.dy.masa.litematica.data.EntityDataManager.getInstance().hasServuxServer());
            work = dedicated ? rpc("enableLoss").thenAccept(ignored -> {}) : CompletableFuture.completedFuture(null);
            advance(30);
        } else if (phase == 30) {
            if (!done()) return;
            configureStress();
            NetworkChaos.enable(AuditFaultProfile.create());
            report.put("lossRate", AuditFaultProfile.CONTROLLED ? 0.0 : 0.15);
            report.put("targetPositions", TARGETS.size());
            report.put("initialMaterialItems", 216); report.put("seed", 0x515549434B53484CL);
            advance(4);
            deadline = System.currentTimeMillis() + 90_000;
        } else if (phase == 4) {
            elapsed++;
            foreignContainer |= client.player.containerMenu != client.player.inventoryMenu;
            busyStreak = QuickShulkerCompat.isBusy() ? busyStreak + 1 : 0;
            maxBusyStreak = Math.max(maxBusyStreak, busyStreak);
            var field = QuickShulkerCompat.class.getDeclaredField("selectedPath"); field.setAccessible(true);
            directObserved |= "DIRECT".equals(String.valueOf(field.get(null)));
            if (snapshot == null && ticks % 5 == 0) snapshot = serverSnapshot(client);
            if (snapshot != null && snapshot.isDone()) {
                lastServer = snapshot.join(); snapshot = null;
                boolean complete = ((Number) lastServer.get("correct")).intValue() == TARGETS.size();
                if (complete && firstServerComplete < 0) firstServerComplete = elapsed;
                boolean converged = complete && countCorrect(client.level) == TARGETS.size()
                        && !QuickShulkerCompat.isBusy() && !foreignContainer;
                convergedStreak = converged ? convergedStreak + 5 : 0;
            }
            if (elapsed >= 800 || convergedStreak >= 20) {
                disable(); advance(5);
            }
        } else if (phase == 5) {
            if (QuickShulkerCompat.isBusy() && ticks < 160) return;
            NetworkChaos.disable();
            report.put("loss", NetworkChaos.stats());
            work = dedicated ? rpc("stopLoss").thenAccept(result -> report.put("dedicatedServerLoss", result.get("loss")))
                    : CompletableFuture.completedFuture(null);
            advance(50);
        } else if (phase == 50) {
            if (!done()) return;
            snapshot = serverSnapshot(client); advance(6);
        } else if (phase == 6) {
            if (!snapshot.isDone()) return;
            lastServer = snapshot.join();
            report.put("server", lastServer);
            report.put("clientServux", AuditServuxCounters.snapshot());
            report.put("clientCorrect", countCorrect(client.level));
            report.put("clientBusy", QuickShulkerCompat.isBusy());
            report.put("elapsedTicks", elapsed); report.put("firstServerCompleteTick", firstServerComplete);
            report.put("maxBusyStreak", maxBusyStreak); report.put("sawForeignContainer", foreignContainer);
            report.put("directObserved", directObserved);
            report.put("materialConservationDelta", ((Number) lastServer.get("inventoryMaterials")).intValue()
                    + ((Number) lastServer.get("materialBlocks")).intValue() - 216);
            ChaosStats loss = NetworkChaos.stats();
            long dropped = loss.clientToServer().dropped() + loss.serverToClient().dropped();
            if (dedicated) {
                var serverLoss = (Map<?, ?>) report.get("dedicatedServerLoss");
                var serverDirection = (Map<?, ?>) serverLoss.get("serverToClient");
                long serverDropped = ((Number) serverDirection.get("dropped")).longValue();
                if (AuditFaultProfile.CONTROLLED)
                    check(serverDropped == 0 && loss.clientToServer().dropped() == AuditFaultProfile.DROP_COUNT,
                            "Controlled variant did not match selected C2S drop count or had S2C loss");
                else check(serverDropped > 0 && loss.clientToServer().dropped() > 0,
                            "Both directions must exercise genuine loss across TCP");
                dropped += serverDropped;
            }
            report.put("totalDroppedPackets", dropped);
            if (AuditFaultProfile.CONTROLLED)
                check(dropped == AuditFaultProfile.DROP_COUNT, "Controlled diagnostic did not exercise the selected drop count");
            else check(dropped >= 10, "Stress did not exercise meaningful real packet loss: " + dropped);
            check(directObserved, "Actual direct API path was never selected");
            check(!foreignContainer, "Direct transfer opened a foreign container");
            check(!QuickShulkerCompat.isBusy(), "Direct API remains busy after 800-tick window and upstream 160-tick drain");
            if (servux) {
                var protocol = (Map<?, ?>) lastServer.get("servuxProtocol");
                check(((Number) protocol.get("metadataRequests")).intValue() >= 1,
                        "Servux metadata exchange was not exercised");
                check(((Number) protocol.get("entityRequests")).intValue() >= 2,
                        "Servux authoritative entity queries were not exercised");
                check(AuditServuxCounters.ENTITY_RESPONSES.get() >= 2 && AuditServuxCounters.CONFIRMED.get() > 0,
                        "Client never consumed real authoritative hand confirmations");
                check(((Number) lastServer.get("materialBlocks")).intValue() > 0,
                        "No actual printing occurred while Servux confirmation was enabled");
                check(((Number) lastServer.get("wrong")).intValue() == 0,
                        "Wrong blocks reached the server while Servux hand confirmation was enabled: " + lastServer);
            }
            report.put("assertionScope", servux ? "direct no-deadlock plus real Servux confirmation exchanges and zero server wrong blocks"
                    : "direct no-deadlock observation; Servux-off wrong/missing blocks are recorded, not rejected");
            finish(client, null);
        }
    }

    private CompletableFuture<Map<String, Object>> rpc(String action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path bridge = Path.of(System.getProperty("printer.audit.bridge"));
                long id = ++requestId;
                Path temporary = bridge.resolve("request-" + id + ".tmp");
                Files.writeString(temporary, new GsonBuilder().create().toJson(Map.of("id", id, "action", action)));
                Files.move(temporary, bridge.resolve("request-" + id + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Path response = bridge.resolve("response-" + id + ".json");
                long timeout = System.currentTimeMillis() + 20_000;
                while (!Files.exists(response)) {
                    if (System.currentTimeMillis() > timeout) throw new AssertionError("Dedicated fixture timeout: " + action);
                    Thread.sleep(20);
                }
                @SuppressWarnings("unchecked") Map<String, Object> result = new GsonBuilder().create().fromJson(Files.readString(response), Map.class);
                check("PASS".equals(result.get("status")), "Dedicated fixture failed: " + result);
                return result;
            } catch (Exception error) { throw new RuntimeException(error); }
        }, bridgeExecutor);
    }
    private CompletableFuture<Map<String, Object>> serverSnapshot(Minecraft client) {
        if (dedicated) return rpc("snapshot");
        return CompletableFuture.supplyAsync(() -> {
            var server = client.getSingleplayerServer(); var player = server.getPlayerList().getPlayers().getFirst();
            int materialBlocks = 0;
            for (BlockPos pos : TARGETS) if (MATERIALS.contains(server.overworld().getBlockState(pos).getBlock())) materialBlocks++;
            return Map.<String, Object>of("correct", countCorrect(server.overworld()), "materialBlocks", materialBlocks,
                    "inventoryMaterials", countInventory(player.getInventory(), player.containerMenu.getCarried()));
        }, client.getSingleplayerServer());
    }

    private static int countInventory(Inventory inventory, ItemStack cursor) {
        int count = countStack(cursor);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i); count += countStack(stack);
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) count += contents.stream().mapToInt(PrinterAuditClient::countStack).sum();
        }
        return count;
    }
    private static int countStack(ItemStack stack) {
        return MATERIALS.stream().anyMatch(block -> stack.is(block.asItem())) ? stack.getCount() : 0;
    }
    private static int countCorrect(Level level) {
        int count = 0;
        for (int i = 0; i < TARGETS.size(); i++) if (level.getBlockState(TARGETS.get(i)).is(expected(i))) count++;
        return count;
    }
    private static Block expected(int index) {
        // Diagnostic layout keeps eight targets per material while making Z neighbors differ.
        int rowShift = Boolean.getBoolean("printer.audit.rotatedMaterials") ? index / MATERIALS.size() : 0;
        return MATERIALS.get((index + rowShift) % MATERIALS.size());
    }
    private static List<BlockPos> targets() {
        List<BlockPos> result = new ArrayList<>();
        for (int z = 2; z <= 9; z++) for (int x = -8; x <= 9; x++) result.add(new BlockPos(x, 64, z));
        return List.copyOf(result);
    }
    private static AreaSelection selection(BlockPos first, BlockPos last) {
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);
        var manager = DataManager.getSelectionManager();
        if (manager.getSelectionMode() != SelectionMode.SIMPLE) manager.switchSelectionMode();
        AreaSelection area = DataManager.getSimpleArea(); Box box = area.getSubRegionBox(area.getName());
        if (box == null) box = area.getSelectedSubRegionBox();
        check(box != null, "Missing real Litematica area selection");
        box.setPos1(first); box.setPos2(last); return area;
    }
    private static void configureStress() {
        selection(TARGETS.getFirst(), TARGETS.getLast());
        Configs.Core.LAG_CHECK.setBooleanValue(false); Configs.Core.WORK_RANGE.setDoubleValue(24);
        Configs.Placement.PRINT_USE_PACKET.setBooleanValue(false);
        Configs.Placement.PLACE_INTERVAL.setIntegerValue(0); Configs.Placement.PLACE_BLOCKS_PER_TICK.setIntegerValue(16);
        Configs.Placement.PLACE_COOLDOWN.setIntegerValue(Integer.getInteger("printer.audit.placeCooldown", 0));
        Configs.Print.PRINT_SELECTION_TYPE.setOptionListValue(SelectionType.LITEMATICA_SELECTION);
        Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false); Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
        Configs.Print.PRINT_SKIP.setBooleanValue(false); Configs.Print.PRINT_REPLACE.setBooleanValue(false);
        Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false); Configs.Print.BREAK_EXTRA_BLOCK.setBooleanValue(false);
        Configs.Print.USE_QUICK_SHULKER.setBooleanValue(true); Configs.Print.SHULKER_SOURCE.setOptionListValue(ShulkerSource.MOD);
        Configs.Print.SHULKER_COOLDOWN.setIntegerValue(0); Configs.Print.RETURN_TO_SHULKER_WHEN_FULL.setBooleanValue(true);
        Configs.Print.ENABLED.setBooleanValue(true); ModuleManager.PRINT.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }
    private static void disable() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Print.ENABLED.setBooleanValue(false); Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false); Configs.Bedrock.ENABLED.setBooleanValue(false);
        ModuleManager.PRINT.resetScanState(); ModuleManager.BEDROCK.resetScanState();
    }
    private void advance(int next) { phase = next; ticks = 0; LOG.info("AUDIT_PHASE {}", next); }
    private boolean done() { if (!work.isDone()) return false; work.join(); return true; }
    private static CompletableFuture<Void> server(Minecraft client, Consumer<MinecraftServer> action) {
        var server = client.getSingleplayerServer(); return CompletableFuture.runAsync(() -> action.accept(server), server);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void finish(Minecraft client, Throwable error) {
        phase = 100;
        try { NetworkChaos.disable(); } catch (Throwable ignored) { }
        disable();
        try { BedrockCompat.setWorking(false, false); BedrockCompat.clearTasks(); } catch (Throwable ignored) { }
        report.put("status", error == null ? "PASS" : "FAIL");
        if (error != null) { report.put("error", error.toString()); LOG.error("AUDIT_FAILED", error); }
        try { AuditPacketTrace.dump(Path.of(System.getProperty("printer.audit.result")).getParent(), "client"); }
        catch (Exception traceError) { LOG.error("Cannot save packet trace", traceError); }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        try { Files.writeString(Path.of(System.getProperty("printer.audit.result")), json); }
        catch (Exception writeError) { LOG.error("Cannot save audit report", writeError); }
        LOG.info("AUDIT_RESULT {}", json);
        client.stop();
    }
}
