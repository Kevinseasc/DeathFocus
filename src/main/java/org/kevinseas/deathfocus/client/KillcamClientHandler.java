package org.kevinseas.deathfocus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.kevinseas.deathfocus.killcam.HitRecord;
import org.kevinseas.deathfocus.network.KillcamFacePacket;
import org.kevinseas.deathfocus.network.KillcamHitLogPacket;

public class KillcamClientHandler {

    private static boolean locking = false;
    private static HitRecord fatalHit = null;

    private static int killerId = -1;
    private static int turnTicks;
    private static int turnTicksElapsed;

    public static void onLock() {
        locking = true;
        killerId = -1;
        fatalHit = null;
    }

    public static void onEnd() {
        locking = false;
        fatalHit = null; // 复活瞬间面板一起消失
    }

    public static void onHitLog(KillcamHitLogPacket packet) {
        fatalHit = packet.fatal();
    }

    public static HitRecord getFatalHit() { return fatalHit; }
    public static boolean isLocking() { return locking; }
    public static boolean shouldShowPanel() { return locking && fatalHit != null; }

    public static void onFace(KillcamFacePacket packet) {
        killerId = packet.killerId();
        turnTicks = Math.max(1, packet.turnTicks());
        turnTicksElapsed = 0;
    }

    public static void reset() {
        locking = false;
        fatalHit = null;
        killerId = -1;
    }

    public static boolean isFacingKiller(Entity entity) {
        return locking && killerId >= 0 && entity.getId() == killerId;
    }

    /** 每 tick 把观察者摄像机缓动转向杀手——服务端只能瞬间转向，动画在客户端做。 */
    public static void clientTick() {
        if (!locking || killerId < 0) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        Entity killer = player.level().getEntity(killerId);
        if (killer == null) return;

        Vec3 from = player.getEyePosition();
        Vec3 to = killer.getEyePosition();
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float targetPitch = Mth.wrapDegrees((float) (-(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI))));
        float targetYaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F);

        if (turnTicksElapsed < turnTicks) {
            turnTicksElapsed++;
            float delta = 1f / (turnTicks - turnTicksElapsed + 1);
            player.setYRot(Mth.rotLerp(delta, player.getYRot(), targetYaw));
            player.setXRot(Mth.rotLerp(delta, player.getXRot(), targetPitch));
        } else {
            player.setYRot(targetYaw);
            player.setXRot(targetPitch);
        }
        player.setYHeadRot(player.getYRot());
    }
}
