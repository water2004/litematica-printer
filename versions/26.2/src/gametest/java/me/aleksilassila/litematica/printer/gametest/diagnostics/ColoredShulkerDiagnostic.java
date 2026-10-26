package me.aleksilassila.litematica.printer.gametest.diagnostics;

import me.aleksilassila.litematica.printer.gametest.PrinterIntegrationGameTest;
import me.aleksilassila.litematica.printer.interfaces.compat.QuickShulkerCompat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ColoredShulkerDiagnostic implements net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest {
    private static final BlockPos TARGET = new BlockPos(2, 64, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("litematica-printer.gametest.coloredShulker")) return;
        List<Item> boxes = new ArrayList<>();
        BuiltInRegistries.ITEM.forEach(item -> {
            if (Block.byItem(item) instanceof ShulkerBoxBlock) boxes.add(item);
        });
        if (boxes.size() != 17) throw new AssertionError("Expected 17 vanilla boxes, got " + boxes.size());
        String mode = System.getProperty("litematica-printer.gametest.quickshulker", "none");
        int passed = 0;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            fixture("prepareWorld", new Class<?>[]{TestSingleplayerContext.class, boolean.class, int.class},
                    singleplayer, true, 9);
            singleplayer.getServer().runCommand("gamemode survival @p");
            singleplayer.getServer().runCommand("tp @p 2.5 65 -3.5");
            context.waitTicks(5);
            singleplayer.getClientLevel().waitForChunksDownload();
            for (Item boxItem : boxes) {
                for (int boxSlot : new int[]{9, 0}) {
                    String label = BuiltInRegistries.ITEM.getKey(boxItem) + " slot=" + boxSlot;
                    System.out.println("[ColoredShulkerDiagnostic] START mode=" + mode + " " + label);
                    context.runOnClient(client -> {
                        fixture("disablePrinter", new Class<?>[0]);
                        QuickShulkerCompat.onDisconnect();
                        QuickShulkerCompat.onPlayJoin();
                        try {
                            var path = QuickShulkerCompat.class.getDeclaredField("selectedPath");
                            path.setAccessible(true);
                            String actual = String.valueOf(path.get(null));
                            String expected = mode.equals("direct") ? "DIRECT" : "LEGACY";
                            if (!actual.equals(expected)) throw new AssertionError("Expected " + expected + " got " + actual);
                        } catch (ReflectiveOperationException error) {
                            throw new AssertionError(error);
                        }
                    });
                    singleplayer.getServer().runOnServer(server -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        player.getInventory().clearContent();
                        ItemStack box = new ItemStack(boxItem);
                        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(
                                List.of(new ItemStack(Items.STONE, 4))));
                        player.getInventory().setItem(boxSlot, box);
                        server.overworld().setBlockAndUpdate(TARGET, Blocks.AIR.defaultBlockState());
                        player.inventoryMenu.sendAllDataToRemote();
                    });
                    context.waitFor(client -> client.player != null && client.level != null
                            && client.level.getBlockState(TARGET).isAir()
                            && client.player.getInventory().getItem(boxSlot).is(boxItem)
                            && storedStone(client.player.getInventory().getItem(boxSlot)) == 4, 100);
                    // The first fixture contains an identical plain box already.
                    // Drain its authoritative inventory content packet before opening a new menu.
                    context.waitTicks(5);
                    context.runOnClient(client -> fixture("configureQuickShulker", new Class<?>[0]));
                    boolean started = context.computeOnClient(client -> QuickShulkerCompat.requestShulkerItem(
                            client.player, new Item[]{Items.STONE}));
                    if (!started) throw new AssertionError("Extraction not started: " + mode + " " + label);
                    try {
                        context.waitFor(client -> looseStone(client.player.getInventory()) == 4
                                && !QuickShulkerCompat.isBusy()
                                && client.player.containerMenu == client.player.inventoryMenu, 100);
                    } catch (AssertionError error) {
                        String detail = context.computeOnClient(client -> "busy=" + QuickShulkerCompat.isBusy()
                                + " loose=" + looseStone(client.player.getInventory())
                                + " boxed=" + storedStone(client.player.getInventory().getItem(boxSlot))
                                + " menu=" + client.player.containerMenu.getClass().getName());
                        throw new AssertionError("Transfer failed: " + mode + " " + label + " " + detail, error);
                    }
                    context.runOnClient(client -> fixture("configureFillPrinter", new Class<?>[0]));
                    context.waitFor(client -> client.level.getBlockState(TARGET).is(Blocks.STONE), 100);
                    context.waitTicks(5);
                    boolean serverPassed = singleplayer.getServer().computeOnServer(server -> {
                        var inventory = server.getPlayerList().getPlayers().getFirst().getInventory();
                        return server.overworld().getBlockState(TARGET).is(Blocks.STONE)
                                && looseStone(inventory) == 3
                                && inventory.getItem(boxSlot).is(boxItem)
                                && storedStone(inventory.getItem(boxSlot)) == 0;
                    });
                    if (!serverPassed) throw new AssertionError("Server extraction/placement mismatch: " + label);
                    System.out.println("[ColoredShulkerDiagnostic] mode=" + mode + " " + label + " PASSED");
                    passed++;
                }
            }
            System.out.println("[ColoredShulkerDiagnostic] mode=" + mode + " passed=" + passed + "/34");
        } finally {
            context.runOnClient(client -> {
                fixture("disablePrinter", new Class<?>[0]);
                QuickShulkerCompat.onDisconnect();
            });
        }
    }

    private static int looseStone(net.minecraft.world.entity.player.Inventory inventory) {
        return inventory.getNonEquipmentItems().stream().filter(stack -> stack.is(Items.STONE))
                .mapToInt(ItemStack::getCount).sum();
    }

    private static int storedStone(ItemStack box) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        return contents == null ? 0 : contents.allItemsCopyStream()
                .filter(stack -> stack.is(Items.STONE)).mapToInt(ItemStack::getCount).sum();
    }

    private static void fixture(String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = PrinterIntegrationGameTest.class.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(null, arguments);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Fixture call failed: " + name, error);
        }
    }
}
