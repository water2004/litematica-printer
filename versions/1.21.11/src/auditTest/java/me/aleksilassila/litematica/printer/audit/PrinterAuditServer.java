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


/** Dedicated-server fixture. Commands only construct fixtures, sample or stop loss.
 * Gameplay after setup is exclusively driven by the real production client. */
public final class PrinterAuditServer implements net.fabricmc.api.ModInitializer {
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

    private long lastRequest = 0;
    public static String describeTarget(net.minecraft.world.level.Level level, BlockPos pos) {
        int index = TARGETS.indexOf(pos);
        if (index < 0) return "outside";
        var actual = level.getBlockState(pos);
        return pos.toShortString() + ":expected="
                + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(expected(index))
                + ",actual=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual.getBlock())
                + ",wrong=" + (!actual.isAir() && !actual.is(expected(index)));
    }
    @Override public void onInitialize() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(this::tick);
    }
    private void tick(MinecraftServer server) {
        Path bridge = Path.of(System.getProperty("printer.audit.bridge"));
        try {
            if (Files.exists(bridge.resolve("stop"))) { server.halt(false); return; }
            Path request = bridge.resolve("request-" + (lastRequest + 1) + ".json");
            if (!Files.exists(request)) return;
            var command = com.google.gson.JsonParser.parseString(Files.readString(request)).getAsJsonObject();
            long id = command.get("id").getAsLong();
            if (id == lastRequest) return;
            lastRequest = id;
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                switch (command.get("action").getAsString()) {
                    case "prepare" -> {
                        server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SURVIVAL);
                        NetworkChaos.reset(); prepareStress(server);
                    }
                    case "clear" -> {                 TARGETS.forEach(pos -> server.overworld().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
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
 }
                    case "enableLoss" -> {
                        NetworkChaos.enable(AuditFaultProfile.create());
                    }
                    case "stopLoss" -> {
                        NetworkChaos.disable(); result.put("loss", NetworkChaos.stats());
                        AuditPacketTrace.dump(bridge.getParent(), "server");
                    }
                    case "snapshot" -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        int materialBlocks = 0;
                        for (BlockPos pos : TARGETS)
                            if (MATERIALS.contains(server.overworld().getBlockState(pos).getBlock())) materialBlocks++;
                        int wrong = 0;
                        for (int index = 0; index < TARGETS.size(); index++) {
                            var state = server.overworld().getBlockState(TARGETS.get(index));
                            if (!state.isAir() && !state.is(expected(index))) wrong++;
                        }
                        result.put("wrong", wrong);
                        result.put("servuxProtocol", AuditServuxCounters.snapshot());
                        result.put("correct", countCorrect(server.overworld()));
                        result.put("materialBlocks", materialBlocks);
                        result.put("inventoryMaterials", countInventory(player.getInventory(), player.containerMenu.getCarried()));
                    }
                    default -> throw new AssertionError("Unknown fixture command");
                }
                result.put("status", "PASS");
            } catch (Throwable error) { result.put("status", "FAIL"); result.put("error", error.toString()); }
            Path temporary = bridge.resolve("response-" + id + ".tmp");
            Files.writeString(temporary, new GsonBuilder().create().toJson(result));
            Files.move(temporary, bridge.resolve("response-" + id + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new RuntimeException("Dedicated audit bridge failed", error); }
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

    private static int countInventory(Inventory inventory, ItemStack cursor) {
        int count = countStack(cursor);
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i); count += countStack(stack);
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) count += contents.stream().mapToInt(PrinterAuditServer::countStack).sum();
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
        int rowShift = Boolean.getBoolean("printer.audit.rotatedMaterials") ? index / MATERIALS.size() : 0;
        return MATERIALS.get((index + rowShift) % MATERIALS.size());
    }
    private static List<BlockPos> targets() {
        List<BlockPos> result = new ArrayList<>();
        for (int z = 2; z <= 9; z++) for (int x = -8; x <= 9; x++) result.add(new BlockPos(x, 64, z));
        return List.copyOf(result);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
