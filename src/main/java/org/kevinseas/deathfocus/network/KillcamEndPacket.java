package org.kevinseas.deathfocus.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.kevinseas.deathfocus.client.KillcamClientHandler;

import java.util.function.Supplier;

/** 聚焦结束+已复活通知（空包）。 */
public record KillcamEndPacket() {
    public static void encode(KillcamEndPacket packet, FriendlyByteBuf buf) {}

    public static KillcamEndPacket decode(FriendlyByteBuf buf) {
        return new KillcamEndPacket();
    }

    public static void handle(KillcamEndPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(KillcamClientHandler::onEnd);
        context.get().setPacketHandled(true);
    }
}
