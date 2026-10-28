package me.aleksilassila.litematica.printer.audit;

import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import org.edtp.networkchaos.api.ChaosConfig;
import org.edtp.networkchaos.api.ExactDropRule;
import org.edtp.networkchaos.api.LinkProfile;
import org.edtp.networkchaos.api.TrafficDirection;

/** Explicit diagnostic variant; does not replace the 15% acceptance profile. */
public final class AuditFaultProfile {
    public static final boolean SINGLE_PLACEMENT = Boolean.getBoolean("printer.audit.singlePlacementDrop");
    public static final String HAND_PACKET = System.getProperty("printer.audit.singleHandDrop", "none");
    public static final boolean CONTROLLED = SINGLE_PLACEMENT || !HAND_PACKET.equals("none");
    public static final int DROP_COUNT = Integer.getInteger("printer.audit.dropCount", 1);

    public static String description() {
        if (SINGLE_PLACEMENT) return "initial " + DROP_COUNT + " C2S placement drops, no random loss or hand-packet loss";
        if (!HAND_PACKET.equals("none")) return "initial " + DROP_COUNT + " C2S " + HAND_PACKET + " hand-switch packet drops, no random loss";
        return "upstream seeded bidirectional 15% random loss";
    }

    public static ChaosConfig create() {
        if (CONTROLLED) return ChaosConfig.clear().withSeed(0x515549434B53484CL)
                .withExactDropRules(ExactDropRule.forPacketClass(
                        TrafficDirection.CLIENT_TO_SERVER, SINGLE_PLACEMENT ? ServerboundUseItemOnPacket.class
                                : switch (HAND_PACKET) {
                                    case "selected" -> ServerboundSetCarriedItemPacket.class;
                                    case "inventory" -> ServerboundContainerClickPacket.class;
                                    default -> throw new IllegalArgumentException("Unknown hand-drop diagnostic: " + HAND_PACKET);
                                }, DROP_COUNT));
        LinkProfile lossy = new LinkProfile(0.15, 0, 0, 0, 0, 0);
        return new ChaosConfig(lossy, lossy, 0x515549434B53484CL, true,
                ChaosConfig.ALL_PACKETS, ChaosConfig.NO_PACKETS);
    }
}
