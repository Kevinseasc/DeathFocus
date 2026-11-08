package org.kevinseas.deathfocus.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.kevinseas.deathfocus.Deathfocus;

/** 注册 DeathFocus 用到的全部网络包：KillcamLock/Face/HitLog/End，共 4 个。 */
public final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Deathfocus.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private NetworkHandler() {}

    public static void register() {
        int id = 0;

        CHANNEL.registerMessage(id++,
                KillcamLockPacket.class,
                KillcamLockPacket::encode,
                KillcamLockPacket::decode,
                KillcamLockPacket::handle);

        CHANNEL.registerMessage(id++,
                KillcamFacePacket.class,
                KillcamFacePacket::encode,
                KillcamFacePacket::decode,
                KillcamFacePacket::handle);

        CHANNEL.registerMessage(id++,
                KillcamHitLogPacket.class,
                KillcamHitLogPacket::encode,
                KillcamHitLogPacket::decode,
                KillcamHitLogPacket::handle);

        CHANNEL.registerMessage(id++,
                KillcamEndPacket.class,
                KillcamEndPacket::encode,
                KillcamEndPacket::decode,
                KillcamEndPacket::handle);
    }
}
