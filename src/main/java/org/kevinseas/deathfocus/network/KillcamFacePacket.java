package org.kevinseas.deathfocus.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.kevinseas.deathfocus.client.KillcamClientHandler;

import java.util.function.Supplier;

/** 转向杀手：告诉客户端杀手的实体 id 和缓动转向要花多少 tick。 */
public record KillcamFacePacket(int killerId, int turnTicks) {
    public static void encode(KillcamFacePacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.killerId());
        buf.writeInt(packet.turnTicks());
    }

    public static KillcamFacePacket decode(FriendlyByteBuf buf) {
        return new KillcamFacePacket(buf.readInt(), buf.readInt());
    }

    public static void handle(KillcamFacePacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> KillcamClientHandler.onFace(packet));
        context.get().setPacketHandled(true);
    }
}
