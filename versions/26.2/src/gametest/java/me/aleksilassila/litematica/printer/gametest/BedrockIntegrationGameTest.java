package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.malilib.util.LayerMode;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ModuleManager;
import me.aleksilassila.litematica.printer.interfaces.compat.BedrockCompat;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;

/**
 * End-to-end compatibility test for each advertised bedrock-breaking mod.
 * A passing test requires the integrated vanilla server to observe the target
 * bedrock as air; merely resolving the reflective adapter is not sufficient.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BedrockIntegrationGameTest implements FabricClientGameTest {
    private static final BlockPos TARGET = new BlockPos(2, 64, 0);
    private static final int BREAK_TIMEOUT_TICKS = 1_800;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (GameTestMode.isAnyPerformance()) return;
        String miner = GameTestMode.bedrockMiner();
        if (miner.equals("none")) return;
        if (!miner.equals("bedrockminer") && !miner.equals("blockminer")) {
            throw new AssertionError("Unsupported bedrock GameTest mode: " + miner);
        }

        context.runOnClient(client -> assertModMatrix(miner));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            prepareWorld(singleplayer);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 2.5 65 -3.5");

            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitFor(client -> client.player != null
                    && !client.player.getAbilities().instabuild
                    && client.level != null
                    && client.level.getBlockState(TARGET).is(Blocks.BEDROCK)
                    && client.player.getInventory().countItem(Items.PISTON) >= 2
                    && client.player.getInventory().countItem(Items.REDSTONE_TORCH) >= 1
                    && hasEfficiencyPickaxe(client.player.getInventory()), 200);

            context.runOnClient(client -> configureBedrockBreaker());
            context.waitFor(client -> client.level != null
                    && client.level.getBlockState(TARGET).isAir(), BREAK_TIMEOUT_TICKS);
            context.waitTicks(10);

            BedrockResult result = singleplayer.getServer().computeOnServer(server -> {
                var player = server.getPlayerList().getPlayers().getFirst();
                return new BedrockResult(
                        server.overworld().getBlockState(TARGET).isAir(),
                        player.getInventory().countItem(Items.PISTON),
                        player.getInventory().countItem(Items.REDSTONE_TORCH),
                        player.getInventory().countItem(Items.LEVER));
            });
            if (!result.targetIsAir()) {
                throw new AssertionError(miner
                        + " completed on the client without breaking server bedrock: " + result);
            }
        } finally {
            context.runOnClient(client -> disableBedrockBreaker());
        }
    }

    private static void assertModMatrix(String miner) {
        boolean bedrockMinerLoaded = FabricLoader.getInstance().isModLoaded("bedrockminer");
        boolean blockMinerLoaded = FabricLoader.getInstance().isModLoaded("blockminer");
        if (bedrockMinerLoaded != miner.equals("bedrockminer")
                || blockMinerLoaded != miner.equals("blockminer")) {
            throw new AssertionError("Bedrock GameTest must install exactly one miner; mode="
                    + miner + ", bedrockminer=" + bedrockMinerLoaded
                    + ", blockminer=" + blockMinerLoaded);
        }

        String expectedVersion = miner.equals("bedrockminer")
                ? "1.6.1-mc26.2"
                : "1.1.1";
        String actualVersion = FabricLoader.getInstance()
                .getModContainer(miner)
                .orElseThrow(() -> new AssertionError(miner + " mod container is missing"))
                .getMetadata().getVersion().getFriendlyString();
        if (!actualVersion.equals(expectedVersion)) {
            throw new AssertionError("Expected " + miner + " " + expectedVersion
                    + " but Fabric loaded " + actualVersion);
        }
        if (!BedrockCompat.isAvailable()) {
            throw new AssertionError("Printer could not resolve the " + miner
                    + " compatibility API");
        }
    }

    private static void prepareWorld(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().getFirst();
            var inventory = player.getInventory();
            inventory.clearContent();

            for (int x = -5; x <= 9; x++) {
                for (int z = -7; z <= 5; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 63, z),
                            Blocks.COBBLESTONE.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, 64, z),
                            Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, 65, z),
                            Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, 66, z),
                            Blocks.AIR.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(TARGET, Blocks.BEDROCK.defaultBlockState());

            inventory.setItem(0, new ItemStack(Items.PISTON, 32));
            inventory.setItem(1, new ItemStack(Items.REDSTONE_TORCH, 32));
            inventory.setItem(2, new ItemStack(Items.LEVER, 32));
            inventory.setItem(3, new ItemStack(Items.SLIME_BLOCK, 32));
            ItemStack pickaxe = new ItemStack(Items.NETHERITE_PICKAXE);
            var enchantments = server.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT);
            pickaxe.enchant(enchantments.getOrThrow(Enchantments.EFFICIENCY), 5);
            inventory.setItem(4, pickaxe);
            inventory.setSelectedSlot(0);
            player.addEffect(new MobEffectInstance(
                    MobEffects.HASTE, 20 * 120, 1, false, false));
            player.inventoryMenu.sendAllDataToRemote();
        });
    }

    private static boolean hasEfficiencyPickaxe(
            net.minecraft.world.entity.player.Inventory inventory) {
        return inventory.getNonEquipmentItems().stream()
                .anyMatch(stack -> stack.is(Items.NETHERITE_PICKAXE)
                        && stack.getEnchantments().size() > 0);
    }

    private static void configureBedrockBreaker() {
        disableBedrockBreaker();

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
        box.setPos1(TARGET);
        box.setPos2(TARGET);
        DataManager.getRenderLayerRange().setLayerMode(LayerMode.ALL);

        Configs.Core.LAG_CHECK.setBooleanValue(false);
        Configs.Core.WORK_RANGE.setDoubleValue(8.0D);
        Configs.Print.ENABLED.setBooleanValue(false);
        Configs.Fill.ENABLED.setBooleanValue(false);
        Configs.Fluid.ENABLED.setBooleanValue(false);
        Configs.Bedrock.BREAK_INTERVAL.setIntegerValue(0);
        Configs.Bedrock.BREAK_BLOCKS_PER_TICK.setIntegerValue(1);
        Configs.Bedrock.ENABLED.setBooleanValue(true);
        ModuleManager.BEDROCK.resetScanState();
        Configs.Core.WORK_SWITCH.setBooleanValue(true);
    }

    private static void disableBedrockBreaker() {
        Configs.Core.WORK_SWITCH.setBooleanValue(false);
        Configs.Bedrock.ENABLED.setBooleanValue(false);
        ModuleManager.BEDROCK.resetScanState();
        BedrockCompat.setWorking(false, false);
        BedrockCompat.clearTasks();
    }

    private record BedrockResult(boolean targetIsAir,
                                 int pistons,
                                 int redstoneTorches,
                                 int levers) {
    }
}
