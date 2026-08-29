package me.aleksilassila.litematica.printer.gametest;

import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueOutput;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only minimal implementation of Servux's litematics protocol.
 *
 * <p>It deliberately supports only metadata registration and an authoritative entity NBT
 * response. The response is serialized from the real server player in the same way Servux does.</p>
 */
public final class TestServuxProtocol {
    private static final AtomicInteger METADATA_REQUESTS = new AtomicInteger();
    private static final AtomicInteger ENTITY_REQUESTS = new AtomicInteger();
    private static final AtomicInteger DIRT_HAND_RESPONSES = new AtomicInteger();
    private static final AtomicInteger STONE_HAND_RESPONSES = new AtomicInteger();

    public static void register() {
        if (!ServerPlayNetworking.getGlobalReceivers().contains(
                ServuxLitematicaPacket.Payload.ID.id())) {
            ServerPlayNetworking.registerGlobalReceiver(
                    ServuxLitematicaPacket.Payload.ID,
                    TestServuxProtocol::receive);
        }
    }

    private static void receive(
            ServuxLitematicaPacket.Payload payload,
            ServerPlayNetworking.Context context) {
        ServuxLitematicaPacket packet = payload.data();
        if (packet.getType()
                == ServuxLitematicaPacket.Type.PACKET_C2S_METADATA_REQUEST) {
            METADATA_REQUESTS.incrementAndGet();
            CompoundTag metadata = new CompoundTag();
            metadata.putString("name", "litematic_data");
            metadata.putString("id", "servux:litematics");
            metadata.putInt("version", ServuxLitematicaPacket.PROTOCOL_VERSION);
            metadata.putString("servux", "gametest-minimal");
            send(context, packetWithCompound("MetadataResponse", metadata));
            return;
        }

        if (packet.getType()
                != ServuxLitematicaPacket.Type.PACKET_C2S_ENTITY_REQUEST) {
            return;
        }

        ENTITY_REQUESTS.incrementAndGet();
        var player = context.player();
        if (player.getMainHandItem().is(Items.DIRT)) {
            DIRT_HAND_RESPONSES.incrementAndGet();
        } else if (player.getMainHandItem().is(Items.STONE)) {
            STONE_HAND_RESPONSES.incrementAndGet();
        }

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING,
                player.level().registryAccess());
        player.saveWithoutId(output);
        CompoundTag entityData = output.buildResult();
        entityData.putString("id", "minecraft:player");
        send(context, entityResponse(packet.getEntityId(), entityData));
    }

    /**
     * Litematica 0.28.5 migrated the Servux payload from vanilla CompoundTag to
     * MaLiLib's CompoundData. Keep the test server compatible with both packet
     * signatures so the compatibility matrix tests the printer instead of pinning
     * the test fixture to one Litematica release.
     */
    private static ServuxLitematicaPacket packetWithCompound(
            String factoryName, CompoundTag data) {
        Method factory = findFactory(factoryName, 1);
        return invokeFactory(factory, convertCompound(factory.getParameterTypes()[0], data));
    }

    private static ServuxLitematicaPacket entityResponse(
            int entityId, CompoundTag data) {
        Method factory = findFactory("SimpleEntityResponse", 2);
        Object converted = convertCompound(factory.getParameterTypes()[1], data);
        return invokeFactory(factory, entityId, converted);
    }

    private static Method findFactory(String name, int parameterCount) {
        for (Method method : ServuxLitematicaPacket.class.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new IllegalStateException("Missing Servux packet factory: " + name);
    }

    private static Object convertCompound(Class<?> targetType, CompoundTag data) {
        if (targetType.isInstance(data)) {
            return data;
        }
        try {
            Class<?> converter = Class.forName(
                    "fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt");
            Object converted = converter
                    .getMethod("fromVanillaCompound", CompoundTag.class)
                    .invoke(null, data);
            if (!targetType.isInstance(converted)) {
                throw new IllegalStateException(
                        "Unsupported Servux compound type: " + targetType.getName());
            }
            return converted;
        } catch (ClassNotFoundException | NoSuchMethodException
                 | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException(
                    "Unable to adapt Servux compound payload", exception);
        }
    }

    private static ServuxLitematicaPacket invokeFactory(
            Method factory, Object... arguments) {
        try {
            return (ServuxLitematicaPacket) factory.invoke(null, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException(
                    "Unable to create Servux packet via " + factory.getName(), exception);
        }
    }

    private static void send(
            ServerPlayNetworking.Context context,
            ServuxLitematicaPacket packet) {
        ServerPlayNetworking.send(
                context.player(), new ServuxLitematicaPacket.Payload(packet));
    }

    public static void resetCounters() {
        METADATA_REQUESTS.set(0);
        ENTITY_REQUESTS.set(0);
        DIRT_HAND_RESPONSES.set(0);
        STONE_HAND_RESPONSES.set(0);
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                METADATA_REQUESTS.get(),
                ENTITY_REQUESTS.get(),
                DIRT_HAND_RESPONSES.get(),
                STONE_HAND_RESPONSES.get());
    }

    public record Snapshot(
            int metadataRequests,
            int entityRequests,
            int dirtHandResponses,
            int stoneHandResponses) {
    }
}
