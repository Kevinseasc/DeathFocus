package org.kevinseas.deathfocus.killcam;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.kevinseas.deathfocus.Deathfocus;
import org.kevinseas.deathfocus.network.KillcamEndPacket;
import org.kevinseas.deathfocus.network.KillcamFacePacket;
import org.kevinseas.deathfocus.network.KillcamHitLogPacket;
import org.kevinseas.deathfocus.network.KillcamLockPacket;
import org.kevinseas.deathfocus.network.NetworkHandler;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡 -> 观察者模式聚焦杀手 SPECTATING_TICKS 这段时间 -> 直接复活（teleportTo 到 vanilla
 * 已经算好的重生点 + 恢复游戏模式），不再有黑屏/倒计时/配装传送。死亡本身完整走完原版流程
 * （掉落、死亡消息都正常触发），只在 vanilla 自己的重生（PlayerRespawnEvent）把玩家放回
 * 重生点的那一刻，把他们"拉回"死亡位置、切成观察者、面朝杀手，停留一段时间后再放回重生点。
 */
@Mod.EventBusSubscriber(modid = Deathfocus.MODID)
public class KillcamManager {
    public static final int SPECTATING_TICKS = 80;  // 聚焦时长，4秒
    public static final int TURN_TICKS = 6;         // 转向杀手的缓动时长，0.3秒

    private static final Map<UUID, PendingDeath> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, KillcamState> ACTIVE = new ConcurrentHashMap<>();

    // TaczCombatListener/TaczSupport/MeleeCombatListener 调用，记录这次死亡的凶器信息，供 respawn 时取用。
    // 单独一个 map 而不是塞进 PendingDeath：LivingDeathEvent（建 PendingDeath）和 TACZ 自己的击杀
    // 事件谁先触发没有文档保证的顺序，这样不管谁先到都能找到对方需要的数据。
    private static final Map<UUID, HitRecord> PENDING_FATAL = new ConcurrentHashMap<>();

    public static void setFatalHit(UUID victimId, HitRecord fatal) {
        PENDING_FATAL.put(victimId, fatal);
    }

    /**
     * 死亡瞬间（LivingDeathEvent，vanilla 还没把玩家挪到重生点）记录的信息：
     * 杀手的实体 id、死亡发生的位置/朝向/维度，respawn 时要把玩家"拉回"这里。
     */
    private record PendingDeath(int killerId, Vec3 deathPos, Vec3 deathEyePos, float deathYaw, float deathPitch,
                                 ResourceKey<Level> deathDimension) {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int killerId = player.getKillCredit() != null ? player.getKillCredit().getId() : -1;
        PENDING.put(player.getUUID(), new PendingDeath(killerId, player.position(), player.getEyePosition(),
                player.getYRot(), player.getXRot(), player.level().dimension()));
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return;
        ServerPlayer player = (ServerPlayer) event.getEntity();
        UUID id = player.getUUID();
        PendingDeath pending = PENDING.remove(id);
        if (pending == null) return; // 没有死亡记录（比如 /kill 又被其它逻辑拦了）就什么都不做

        GameType previousGameType = player.gameMode.getGameModeForPlayer();
        // respawn 事件触发时 vanilla 已经把玩家放到重生点了，这里记下来，供聚焦结束后放回。
        Vec3 respawnPos = player.position();
        float respawnYaw = player.getYRot();
        float respawnPitch = player.getXRot();
        ResourceKey<Level> respawnDimension = player.level().dimension();

        KillcamState state = new KillcamState(previousGameType, pending.killerId(),
                respawnPos, respawnYaw, respawnPitch, respawnDimension);
        ACTIVE.put(id, state);

        // 把玩家"拉回"刚死的地方，切观察者，面朝杀手。
        player.setGameMode(GameType.SPECTATOR);

        MinecraftServer server = player.getServer();
        var deathLevel = server != null ? server.getLevel(pending.deathDimension()) : null;
        if (deathLevel != null) {
            float facingYaw = pending.deathYaw();
            float facingPitch = pending.deathPitch();
            Entity killer = pending.killerId() >= 0 ? deathLevel.getEntity(pending.killerId()) : null;
            if (killer != null) {
                float[] yawPitch = lookAt(pending.deathEyePos(), killer.getEyePosition());
                facingYaw = yawPitch[0];
                facingPitch = yawPitch[1];
            }
            player.teleportTo(deathLevel, pending.deathPos().x, pending.deathPos().y, pending.deathPos().z,
                    Set.of(), facingYaw, facingPitch);
        }

        sendTo(player, new KillcamLockPacket());
        sendTo(player, new KillcamFacePacket(pending.killerId(), TURN_TICKS));

        HitRecord fatal = PENDING_FATAL.remove(id);
        sendTo(player, new KillcamHitLogPacket(fatal));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (var it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            KillcamState state = entry.getValue();
            state.ticksInPhase++;
            if (state.ticksInPhase < SPECTATING_TICKS) continue;

            // 聚焦结束，直接复活。
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                player.setGameMode(state.previousGameType);
                var level = server.getLevel(state.spawnDimension);
                if (level != null) {
                    player.teleportTo(level, state.spawnPos.x, state.spawnPos.y, state.spawnPos.z,
                            Set.of(), state.spawnYaw, state.spawnPitch);
                }
                sendTo(player, new KillcamEndPacket());
            }
            it.remove();
        }
    }

    /** 计算从 from 看向 to 需要的 yaw/pitch，和客户端 KillcamClientHandler.clientTick 用的是同一套公式。 */
    private static float[] lookAt(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float pitch = Mth.wrapDegrees((float) (-(Mth.atan2(dy, horizontalDistance) * (180F / Math.PI))));
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90.0F);
        return new float[]{yaw, pitch};
    }

    private static void sendTo(ServerPlayer player, Object packet) {
        NetworkHandler.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
