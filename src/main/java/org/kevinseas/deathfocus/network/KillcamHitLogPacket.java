package org.kevinseas.deathfocus.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import org.kevinseas.deathfocus.client.KillcamClientHandler;
import org.kevinseas.deathfocus.killcam.HitRecord;

import java.util.UUID;
import java.util.function.Supplier;

/** 淘汰面板数据：攻击者名字/血量/血量上限 + 武器信息（TACZ 枪械 id 二元组，或普通 ItemStack）。 */
public record KillcamHitLogPacket(HitRecord fatal) {

    public static void encode(KillcamHitLogPacket packet, FriendlyByteBuf buf) {
        HitRecord fatal = packet.fatal();
        buf.writeBoolean(fatal != null);
        if (fatal != null) {
            buf.writeUUID(fatal.attackerId());
            buf.writeUtf(fatal.attackerName(), 32767);
            buf.writeVarInt(fatal.attackerRemainingHealth());
            buf.writeFloat(fatal.attackerMaxHealth());
            boolean taczWeapon = fatal.isTaczWeapon();
            buf.writeBoolean(taczWeapon);
            if (taczWeapon) {
                buf.writeResourceLocation(fatal.gunId());
                buf.writeResourceLocation(fatal.gunDisplayId());
            } else {
                buf.writeItem(fatal.weaponStack());
            }
        }
    }

    public static KillcamHitLogPacket decode(FriendlyByteBuf buf) {
        HitRecord fatal = null;
        if (buf.readBoolean()) {
            UUID attackerId = buf.readUUID();
            String attackerName = buf.readUtf(32767);
            int health = buf.readVarInt();
            float maxHealth = buf.readFloat();
            boolean taczWeapon = buf.readBoolean();
            ResourceLocation gunId = null;
            ResourceLocation gunDisplayId = null;
            ItemStack weaponStack = ItemStack.EMPTY;
            if (taczWeapon) {
                gunId = buf.readResourceLocation();
                gunDisplayId = buf.readResourceLocation();
            } else {
                weaponStack = buf.readItem();
            }
            fatal = new HitRecord(attackerId, attackerName, health, maxHealth, gunId, gunDisplayId, weaponStack);
        }
        return new KillcamHitLogPacket(fatal);
    }

    public static void handle(KillcamHitLogPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> KillcamClientHandler.onHitLog(packet));
        context.get().setPacketHandled(true);
    }
}
