package net.kyrptonaught.quickshulker.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.item.PlayerInventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.kyrptonaught.quickshulker.api.QuickOpenableRegistry;
import net.kyrptonaught.quickshulker.api.QuickShulkerData;
import net.kyrptonaught.quickshulker.api.shulker.CarriedShulkerSlotEndpoint;
import net.kyrptonaught.quickshulker.api.shulker.PlayerSlotEndpoint;
import net.kyrptonaught.quickshulker.api.shulker.ShulkerItemFilter;
import net.kyrptonaught.quickshulker.api.shulker.ShulkerTransferRequest;
import net.kyrptonaught.quickshulker.api.shulker.ShulkerTransferResult;
import net.kyrptonaught.quickshulker.api.shulker.ShulkerTransferStatus;
import net.kyrptonaught.quickshulker.api.shulker.server.ShulkerStorages;
import net.kyrptonaught.quickshulker.internal.shulker.server.ShulkerTransferTransactions;
import net.kyrptonaught.quickshulker.util.BundleHelper;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class QuickShulkerTransferGameTests {
    private static final int BOX_SLOT = 9;
    private static final int OUTPUT_SLOT = 10;

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void legacyAndPublicStorageUseTheSameInsertionSemantics(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack legacyBox = box(new ItemStack(Items.STONE, 60));
        ItemStack publicBox = legacyBox.copy();
        ItemStack legacyInput = new ItemStack(Items.STONE, 70);

        BundleHelper.bundleItemIntoStack(player, legacyBox, legacyInput, null);
        player.getInventory().setItem(BOX_SLOT, publicBox);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);
        long inserted;
        try (Transaction transaction = Transaction.openOuter()) {
            inserted = storage.insert(ItemVariant.of(Items.STONE), 70, transaction);
            transaction.commit();
        }

        helper.assertValueEqual(inserted, 70L,
                "Public storage should accept the whole source");
        helper.assertValueEqual(legacyInput.getCount(), 0,
                "Legacy insertion should consume the same amount");
        helper.assertTrue(sameContents(legacyBox, publicBox),
                "Legacy and public paths must write identical contents");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void abortedTransactionRestoresShulkerContents(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack carriedBox = box(new ItemStack(Items.STONE, 12));
        player.getInventory().setItem(BOX_SLOT, carriedBox);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    storage.extract(ItemVariant.of(Items.STONE), 5, transaction),
                    5L, "The tentative extraction should be visible");
        }

        helper.assertValueEqual(count(carriedBox, Items.STONE), 12,
                "Closing without commit must roll back the host component");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void exactSlotsComposeWithPlayerInventoryStorage(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack carriedBox = box(
                new ItemStack(Items.DIRT, 8),
                new ItemStack(Items.STONE, 12));
        player.getInventory().setItem(BOX_SLOT, carriedBox);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        long moved;
        try (Transaction transaction = Transaction.openOuter()) {
            moved = StorageUtil.move(
                    storage.getSlot(1),
                    PlayerInventoryStorage.of(player).getSlot(OUTPUT_SLOT),
                    variant -> variant.isOf(Items.STONE),
                    5,
                    transaction);
            transaction.commit();
        }

        helper.assertValueEqual(moved, 5L,
                "Fabric StorageUtil should move the requested amount");
        helper.assertValueEqual(player.getInventory().getItem(OUTPUT_SLOT).getCount(), 5,
                "The selected player slot should receive the items");
        helper.assertValueEqual(count(carriedBox, Items.STONE), 7,
                "Only the selected shulker slot should be debited");
        helper.assertValueEqual(count(carriedBox, Items.DIRT), 8,
                "Unselected contents must remain untouched");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void storageRejectsNestedShulkerBoxes(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box();
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        long inserted;
        try (Transaction transaction = Transaction.openOuter()) {
            inserted = storage.insert(ItemVariant.of(Items.SHULKER_BOX), 1, transaction);
            transaction.commit();
        }

        helper.assertValueEqual(inserted, 0L,
                "QuickShulker's nesting rule must constrain every storage slot");
        helper.assertValueEqual(count(host, Items.SHULKER_BOX), 0,
                "Rejected nesting must not mutate the box");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void resolvedStorageStopsWhenSlotIsUnsupportedAndFollowsLiveContents(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> movedStorage = storage(player, BOX_SLOT);

        player.getInventory().setItem(BOX_SLOT, ItemStack.EMPTY);
        player.getInventory().setItem(OUTPUT_SLOT, host);
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    movedStorage.extract(ItemVariant.of(Items.STONE), 1, transaction),
                    0L, "A location-bound storage must become inert after a move");
        }

        player.getInventory().setItem(BOX_SLOT, box(new ItemStack(Items.DIRT, 4)));
        SlottedStorage<ItemVariant> changedStorage = storage(player, BOX_SLOT);
        player.getInventory().getItem(BOX_SLOT).set(
                DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 2))));
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    changedStorage.extract(ItemVariant.of(Items.DIRT), 1, transaction),
                    1L, "A slot-bound storage must follow the authoritative contents");
            transaction.commit();
        }
        helper.assertValueEqual(
                count(player.getInventory().getItem(BOX_SLOT), Items.DIRT),
                1, "The extraction should apply to the current host contents");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void versionAndUnderlyingViewsFollowAuthoritativeSlotState(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> liveStorage = storage(player, BOX_SLOT);

        host.set(
                DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT, 4))));
        liveStorage.getVersion();
        helper.assertValueEqual(
                liveStorage.getSlot(0).getUnderlyingView().getResource(),
                ItemVariant.of(Items.DIRT),
                "The underlying view must expose the authoritative slot state");

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    liveStorage.extract(ItemVariant.of(Items.DIRT), 1, transaction),
                    1L, "getVersion() must not detach the live storage from its slot");
            transaction.commit();
        }
        ItemStack current = player.getInventory().getItem(BOX_SLOT);
        helper.assertValueEqual(count(current, Items.DIRT), 3,
                "The live storage must update the externally supplied contents");
        helper.assertValueEqual(count(current, Items.STONE), 0,
                "No obsolete container snapshot may be restored");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void independentlyResolvedHandlesRemainComposableAcrossCommits(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> first = storage(player, BOX_SLOT);
        SlottedStorage<ItemVariant> second = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    first.extract(ItemVariant.of(Items.STONE), 2, transaction),
                    2L, "The first storage should commit normally");
            transaction.commit();
        }
        helper.assertValueEqual(count(host, Items.STONE), 6,
                "The first storage should debit the authoritative host");

        second.getVersion();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    second.extract(ItemVariant.of(Items.STONE), 1, transaction),
                    1L, "A second handle must continue from the first commit");
            transaction.commit();
        }
        helper.assertValueEqual(
                count(player.getInventory().getItem(BOX_SLOT), Items.STONE),
                5, "The second handle must preserve and extend the first commit");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void resolvedStorageRemainsUsableAcrossItsOwnCommits(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box();
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    storage.insert(ItemVariant.of(Items.STONE), 3, transaction),
                    3L, "The first transaction should use the resolved storage");
            transaction.commit();
        }
        storage.getVersion();
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    storage.insert(ItemVariant.of(Items.STONE), 2, transaction),
                    2L, "The same handle should follow its committed slot state");
            transaction.commit();
        }

        helper.assertValueEqual(count(host, Items.STONE), 5,
                "One storage handle should remain reusable after its own commit");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void storageVersionAdvancesAfterCommit(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(BOX_SLOT, box());
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);
        long before = storage.getVersion();

        try (Transaction transaction = Transaction.openOuter()) {
            storage.insert(ItemVariant.of(Items.STONE), 3, transaction);
            transaction.commit();
        }

        helper.assertTrue(storage.getVersion() > before,
                "A committed storage mutation should advance its version");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void transactionMutationIsVisibleThroughTheAuthoritativeSlot(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    storage.insert(ItemVariant.of(Items.DIRT), 4, transaction),
                    4L, "The tentative insert should be accepted");
            helper.assertValueEqual(
                    count(player.getInventory().getItem(BOX_SLOT), Items.DIRT),
                    4, "Fabric transactions mutate participant state before commit");
            transaction.commit();
        }

        helper.assertValueEqual(
                count(player.getInventory().getItem(BOX_SLOT), Items.DIRT), 4,
                "The committed insert should be written to the host");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void finalCommitMustNotOverwriteOutOfBandHostChange(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    storage.insert(ItemVariant.of(Items.DIRT), 4, transaction),
                    4L, "The tentative insert should be accepted");
            player.getInventory().getItem(BOX_SLOT).set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(List.of(
                            new ItemStack(Items.GOLD_INGOT, 3))));
            transaction.commit();
        }

        ItemStack current = player.getInventory().getItem(BOX_SLOT);
        helper.assertValueEqual(count(current, Items.GOLD_INGOT), 3,
                "Final commit must not overwrite a later out-of-band host change; finalContents="
                        + contents(current));
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void rollbackRestoresTheWholeAuthoritativePlayerSlot(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    storage.insert(ItemVariant.of(Items.DIRT), 4, transaction),
                    4L, "The tentative insert should be accepted");
            player.getInventory().getItem(BOX_SLOT).set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(List.of(
                            new ItemStack(Items.GOLD_INGOT, 3))));
        }

        ItemStack current = player.getInventory().getItem(BOX_SLOT);
        helper.assertValueEqual(count(current, Items.GOLD_INGOT), 0,
                "Out-of-transaction mutation of a participant is part of its rollback scope");
        helper.assertValueEqual(count(current, Items.STONE), 8,
                "Rollback must restore the authoritative pre-transaction slot state");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void rolledBackMutationMustNotPermanentlyInvalidatePeerHandle(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> first = storage(player, BOX_SLOT);
        SlottedStorage<ItemVariant> peer = storage(player, BOX_SLOT);

        long peerDuringTentativeMutation;
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    first.insert(ItemVariant.of(Items.DIRT), 1, transaction),
                    1L, "The first handle should stage its mutation");
            peerDuringTentativeMutation =
                    peer.extract(ItemVariant.of(Items.STONE), 1, transaction);
        }
        helper.assertValueEqual(count(host, Items.STONE), 8,
                "The aborted outer transaction should restore the host");

        long extractedAfterRollback;
        try (Transaction transaction = Transaction.openOuter()) {
            extractedAfterRollback =
                    peer.extract(ItemVariant.of(Items.STONE), 1, transaction);
            transaction.commit();
        }

        helper.assertTrue(peerDuringTentativeMutation > 0 || extractedAfterRollback == 1,
                "A tentative change that was rolled back permanently invalidated the peer: "
                        + "during=" + peerDuringTentativeMutation
                        + ", afterRollback=" + extractedAfterRollback);
        helper.assertValueEqual(extractedAfterRollback, 1L,
                "The peer handle must be usable again after the authoritative host is restored");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void builtInHandlesMustComposeInOneOuterTransaction(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> first = storage(player, BOX_SLOT);
        SlottedStorage<ItemVariant> second = storage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    first.insert(ItemVariant.of(Items.DIRT), 1, transaction),
                    1L, "The first built-in handle should accept its mutation");
            helper.assertValueEqual(
                    second.insert(ItemVariant.of(Items.GOLD_INGOT), 1, transaction),
                    1L, "The second built-in handle should accept its mutation");
            transaction.commit();
        }

        helper.assertValueEqual(count(host, Items.DIRT), 1,
                "The first built-in handle's committed mutation must survive");
        helper.assertValueEqual(count(host, Items.GOLD_INGOT), 1,
                "The second built-in handle's committed mutation must survive");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void fabricContainerItemContextsComposeAcrossMultipleHandles(
            GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        SlottedStorage<ItemVariant> first = nativeItemStorage(player, BOX_SLOT);
        SlottedStorage<ItemVariant> second = nativeItemStorage(player, BOX_SLOT);

        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(
                    first.insert(ItemVariant.of(Items.DIRT), 1, transaction),
                    1L, "The first native item storage should accept its mutation");
            helper.assertValueEqual(
                    second.insert(ItemVariant.of(Items.GOLD_INGOT), 1, transaction),
                    1L, "The second native item storage should accept its mutation");
            transaction.commit();
        }

        ItemStack committedHost = player.getInventory().getItem(BOX_SLOT);
        helper.assertValueEqual(count(committedHost, Items.DIRT), 1,
                "The first native storage mutation should survive");
        helper.assertValueEqual(count(committedHost, Items.GOLD_INGOT), 1,
                "The second native storage mutation should survive");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void detachedHandlesMustComposeInOneOuterTransaction(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box(new ItemStack(Items.STONE, 8));
        player.getInventory().setItem(BOX_SLOT, host);
        QuickShulkerData original = QuickOpenableRegistry.getQuickie(host.getItem());

        int dirt;
        int gold;
        try {
            new QuickOpenableRegistry.Builder()
                    .setItem(ShulkerBoxBlock.class)
                    .supportsBundleing(true)
                    .getBundleInv((ignoredPlayer, stack) ->
                            new DelayedHostContainer(stack))
                    .register();

            SlottedStorage<ItemVariant> first = storage(player, BOX_SLOT);
            SlottedStorage<ItemVariant> second = storage(player, BOX_SLOT);
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(
                        first.insert(ItemVariant.of(Items.DIRT), 1, transaction),
                        1L, "The first detached handle should accept its mutation");
                helper.assertValueEqual(
                        second.insert(ItemVariant.of(Items.GOLD_INGOT), 1, transaction),
                        1L, "The second detached handle should accept its mutation");
                transaction.commit();
            }
            dirt = count(host, Items.DIRT);
            gold = count(host, Items.GOLD_INGOT);
        } finally {
            QuickOpenableRegistry.register(ShulkerBoxBlock.class, original);
        }

        helper.assertValueEqual(dirt, 1,
                "The first handle's committed mutation must survive");
        helper.assertValueEqual(gold, 1,
                "The second handle's committed mutation must survive");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void insertionPolicyMustReceiveAttemptedAmount(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack host = box();
        player.getInventory().setItem(BOX_SLOT, host);
        QuickShulkerData original = QuickOpenableRegistry.getQuickie(host.getItem());
        AtomicInteger observedCount = new AtomicInteger(-1);

        long inserted;
        long directInserted;
        int aggregateObserved;
        int directObserved;
        long emptyCapacity;
        int expectedContainerCapacity;
        try {
            new QuickOpenableRegistry.Builder()
                    .setItem(ShulkerBoxBlock.class)
                    .supportsBundleing(true)
                    .canBundleInsertItem((ignoredPlayer, ignoredContainer,
                                          ignoredHost, offeredStack) -> {
                        observedCount.set(offeredStack.getCount());
                        return true;
                    })
                    .register();

            SlottedStorage<ItemVariant> storage = storage(player, BOX_SLOT);
            emptyCapacity = storage.getSlot(0).getCapacity();
            expectedContainerCapacity = QuickOpenableRegistry
                    .getQuickie(host.getItem())
                    .getInventory(player, host)
                    .getMaxStackSize();
            try (Transaction transaction = Transaction.openOuter()) {
                inserted = storage.insert(
                        ItemVariant.of(Items.STONE), 64, transaction);
            }
            aggregateObserved = observedCount.get();

            observedCount.set(-1);
            try (Transaction transaction = Transaction.openOuter()) {
                directInserted = storage.getSlot(0).insert(
                        ItemVariant.of(Items.STONE), 37, transaction);
            }
            directObserved = observedCount.get();
        } finally {
            QuickOpenableRegistry.register(ShulkerBoxBlock.class, original);
        }

        helper.assertValueEqual(inserted, 64L,
                "The storage should be able to stage one full stack");
        helper.assertValueEqual(aggregateObserved, 64,
                "Aggregate insertion must preserve the offered amount");
        helper.assertValueEqual(directInserted, 37L,
                "A direct empty-slot insertion should accept the offered stack");
        helper.assertValueEqual(directObserved, 37,
                "Direct slot insertion must preserve the offered amount");
        helper.assertValueEqual(emptyCapacity, (long) expectedContainerCapacity,
                "Empty-slot capacity must reflect the container's extensible limit");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void legacyUnbundleAndStorageToStorageStillWork(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack source = box(
                new ItemStack(Items.DIRT, 8),
                new ItemStack(Items.STONE, 12));
        player.getInventory().setItem(OUTPUT_SLOT, new ItemStack(Items.DIRT, 60));

        ItemStack result = BundleHelper.unbundleItem(
                player, source, new Slot(player.getInventory(), OUTPUT_SLOT, 0, 0));
        helper.assertTrue(result != null, "Legacy unbundle should report success");
        helper.assertValueEqual(player.getInventory().getItem(OUTPUT_SLOT).getCount(), 64,
                "Unbundle should skip an incompatible later source slot");

        ItemStack destination = box(new ItemStack(Items.STONE, 60));
        BundleHelper.transferItemsToShulker(player, destination, source, null);
        helper.assertValueEqual(count(destination, Items.STONE), 72,
                "Storage-to-storage should merge and then use empty slots");
        helper.assertValueEqual(count(destination, Items.DIRT), 4,
                "Storage-to-storage should preserve item identities");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void duplicateSequenceMutatesStorageOnlyOnce(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack carriedBox = box(new ItemStack(Items.STONE, 16));
        player.getInventory().setItem(BOX_SLOT, carriedBox);
        ShulkerTransferRequest request = extractRequest(6);

        ShulkerTransferResult first = ShulkerTransferTransactions.executeOnce(player, 1, request);
        ShulkerTransferResult duplicate = ShulkerTransferTransactions.executeOnce(player, 1, request);

        helper.assertValueEqual(first.movedCount(), 6,
                "The first request should move six items");
        helper.assertValueEqual(duplicate, first,
                "A duplicate should return the same fixed-size receipt");
        helper.assertValueEqual(player.getInventory().getItem(OUTPUT_SLOT).getCount(), 6,
                "A duplicate must not apply the mutation twice");
        helper.assertValueEqual(count(carriedBox, Items.STONE), 10,
                "Storage must be debited exactly once");
        ShulkerTransferTransactions.clear(player);
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void lostSequenceDoesNotBlockLaterRequests(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(BOX_SLOT, box(new ItemStack(Items.STONE, 8)));
        ShulkerTransferRequest request = extractRequest(3);

        ShulkerTransferResult afterGap = ShulkerTransferTransactions.executeOnce(player, 2, request);
        ShulkerTransferResult stale = ShulkerTransferTransactions.executeOnce(player, 1, request);

        helper.assertValueEqual(afterGap.status(), ShulkerTransferStatus.SUCCESS,
                "A later request should recover after a lost sequence");
        helper.assertValueEqual(stale.status(), ShulkerTransferStatus.OUT_OF_ORDER,
                "A stale sequence must still be rejected");
        helper.assertValueEqual(player.getInventory().getItem(OUTPUT_SLOT).getCount(), 3,
                "Only the accepted request should execute");
        ShulkerTransferTransactions.clear(player);
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void protocolStateAndRateLimitsArePerPlayer(GameTestHelper helper) {
        ServerPlayer noisy = player(helper);
        ServerPlayer other = player(helper);
        noisy.getInventory().setItem(BOX_SLOT, box(new ItemStack(Items.STONE, 1)));
        other.getInventory().setItem(BOX_SLOT, box(new ItemStack(Items.STONE, 1)));
        ShulkerTransferRequest request = extractRequest(1);

        ShulkerTransferResult limited = null;
        for (long sequence = 1; sequence <= 9; sequence++) {
            limited = ShulkerTransferTransactions.executeOnce(noisy, sequence, request);
        }
        ShulkerTransferResult independent =
                ShulkerTransferTransactions.executeOnce(other, 1, request);

        helper.assertValueEqual(limited.status(), ShulkerTransferStatus.RATE_LIMITED,
                "A ninth new request in one tick should be rate limited");
        helper.assertValueEqual(independent.status(), ShulkerTransferStatus.SUCCESS,
                "Another player must have independent protocol state");
        ShulkerTransferTransactions.clear(noisy);
        ShulkerTransferTransactions.clear(other);
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void protocolRejectsNonShulkerHostsAndBundles(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        player.getInventory().setItem(0, new ItemStack(Items.STONE, 4));

        player.getInventory().setItem(BOX_SLOT, new ItemStack(Items.ENDER_CHEST));
        ShulkerTransferResult enderChest = ShulkerTransferTransactions.executeOnce(
                player, 1, insertRequest(4));
        player.getInventory().setItem(BOX_SLOT, new ItemStack(Items.BUNDLE));
        ShulkerTransferResult bundle = ShulkerTransferTransactions.executeOnce(
                player, 2, insertRequest(4));

        helper.assertValueEqual(enderChest.status(), ShulkerTransferStatus.UNSUPPORTED,
                "The shulker protocol must reject non-shulker storage");
        helper.assertValueEqual(bundle.status(), ShulkerTransferStatus.UNSUPPORTED,
                "Bundles belong to a future container-specific protocol");
        helper.assertValueEqual(player.getInventory().getItem(0).getCount(), 4,
                "Rejected hosts must not consume player items");
        ShulkerTransferTransactions.clear(player);
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void originalRegistryRemainsTheCapabilitySource(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack carriedBox = box();
        player.getInventory().setItem(BOX_SLOT, carriedBox);
        QuickShulkerData registration = QuickOpenableRegistry.getQuickie(carriedBox.getItem());

        helper.assertTrue(registration != null && registration.supportsBundleing,
                "The original public registry must remain available");
        helper.assertTrue(ShulkerStorages.findCarried(player, BOX_SLOT).isPresent(),
                "The new API must resolve the original registration directly");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        return player;
    }

    private static SlottedStorage<ItemVariant> storage(ServerPlayer player, int slot) {
        return ShulkerStorages.findCarried(player, slot).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static SlottedStorage<ItemVariant> nativeItemStorage(
            ServerPlayer player, int slot) {
        ContainerItemContext context = ContainerItemContext.ofPlayerSlot(
                player, PlayerInventoryStorage.of(player).getSlot(slot));
        Storage<ItemVariant> storage = new net.kyrptonaught.quickshulker.internal.storage.CarriedComponentStorage(context, 27);
        if (!(storage instanceof SlottedStorage<?> slotted)) {
            throw new AssertionError("Carried shulker did not expose slotted Fabric item storage");
        }
        return (SlottedStorage<ItemVariant>) slotted;
    }

    private static ShulkerTransferRequest extractRequest(int amount) {
        return new ShulkerTransferRequest(
                new CarriedShulkerSlotEndpoint(BOX_SLOT, 0),
                new PlayerSlotEndpoint(OUTPUT_SLOT),
                ShulkerItemFilter.sameItem(new ItemStack(Items.STONE)),
                amount);
    }

    private static ShulkerTransferRequest insertRequest(int amount) {
        return new ShulkerTransferRequest(
                new PlayerSlotEndpoint(0),
                new CarriedShulkerSlotEndpoint(BOX_SLOT, 0),
                ShulkerItemFilter.any(),
                amount);
    }

    private static ItemStack box(ItemStack... stacks) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(stacks)));
        return box;
    }

    private static List<ItemStack> contents(ItemStack box) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) return List.of();
        List<ItemStack> result = new ArrayList<>();
        contents.nonEmptyItems().forEach(stack -> result.add(stack.copy()));
        return result;
    }

    private static int count(ItemStack box, Item item) {
        return contents(box).stream()
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static boolean sameContents(ItemStack first, ItemStack second) {
        List<ItemStack> left = contents(first);
        List<ItemStack> right = contents(second);
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (!ItemStack.matches(left.get(index), right.get(index))) return false;
        }
        return true;
    }

    private static final class DelayedHostContainer extends SimpleContainer {
        private final ItemStack host;

        private DelayedHostContainer(ItemStack host) {
            super(readSlots(host));
            this.host = host;
        }

        @Override
        public void stopOpen(Player user) {
            List<ItemStack> stacks = new ArrayList<>(getContainerSize());
            for (int slot = 0; slot < getContainerSize(); slot++) {
                stacks.add(getItem(slot).copy());
            }
            host.set(DataComponents.CONTAINER,
                    ItemContainerContents.fromItems(stacks));
        }

        private static ItemStack[] readSlots(ItemStack host) {
            NonNullList<ItemStack> stacks =
                    NonNullList.withSize(27, ItemStack.EMPTY);
            ItemContainerContents contents = host.get(DataComponents.CONTAINER);
            if (contents != null) contents.copyInto(stacks);
            return stacks.toArray(ItemStack[]::new);
        }
    }
}
