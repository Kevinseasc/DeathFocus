package org.kevinseas.deathfocus.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.kevinseas.deathfocus.client.KillcamClientHandler;

import java.util.function.Supplier;

/** 死亡瞬间通知客户端进入聚焦状态（空包）。 */
public record KillcamLockPacket() {
    public static void encode(KillcamLockPacket packet, FriendlyByteBuf buf) {}

    public static KillcamLockPacket decode(FriendlyByteBuf buf) {
        return new KillcamLockPacket();
    }

    public static void handle(KillcamLockPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(KillcamClientHandler::onLock);
        context.get().setPacketHandled(true);
    }
}
