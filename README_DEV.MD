# 淘汰聚焦 + 淘汰信息面板 —— 独立 mod 抽取核心代码

本文档整理自 ShotMCHelper 的 killcam 功能，为抽取成一个独立精简 mod（候选名：`DeathFocus`）准备的核心代码。

抽取范围：**死亡瞬间聚焦杀手（观察者模式朝向杀手 4 秒）+ 淘汰信息面板（攻击者名/血条/血量/武器图标+名字），聚焦结束直接复活**。

明确不带走的部分：
- 黑屏淡入淡出、倒计时
- 按 L 键切换配装点传送
- `CombatLogManager` 持久化击杀计数（本次死亡/本局之外不保留历史）
- 淘汰计数显示（被淘汰/淘汰次数统计）——面板只展示本次死亡的武器/攻击者信息
- 无敌/警告相关监听器（`InvincibilityListener`/`AttackWarningListener`）

## 服务端核心

```java
// ===== HitRecord.java =====
package org.kevinseas.shotmchelper.killcam;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/**
 * 本次死亡的致命一击信息：攻击者是谁、攻击者当前血量/上限、用什么武器杀的。
 * 武器信息二选一：
 *  - gunId/gunDisplayId 非空：TACZ 枪械击杀，客户端用 TaczWeaponIconRenderer 解析图标和名字；
 *  - weaponStack 非空：近战/普通物品击杀，直接拿主手物品渲染。
 * 环境死亡（摔死/烧死/溺水/`/kill` 等，没有玩家攻击者）三者都为空/空物品。
 */
public record HitRecord(UUID attackerId, String attackerName,
                         int attackerRemainingHealth, float attackerMaxHealth,
                         ResourceLocation gunId, ResourceLocation gunDisplayId,
                         ItemStack weaponStack) {

    public HitRecord(UUID attackerId, String attackerName, int attackerRemainingHealth,
                      float attackerMaxHealth, ItemStack weaponStack) {
        this(attackerId, attackerName, attackerRemainingHealth, attackerMaxHealth, null, null, weaponStack);
    }

    public boolean isTaczWeapon() {
        return gunId != null && gunDisplayId != null;
    }

    public boolean hasWeapon() {
        return isTaczWeapon() || (weaponStack != null && !weaponStack.isEmpty());
    }
}
```

```java
// ===== TaczCombatListener.java =====
package org.kevinseas.shotmchelper.killcam;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import java.util.UUID;

/**
 * 全 mod 唯一允许引用 com.tacz.* 的类。必须在确认 TACZ 已加载后手动注册，
 * 绝不能用 @Mod.EventBusSubscriber 自动扫描——否则没装 TACZ 的服务端会在扫描阶段
 * 就因为解析不到 com.tacz.* 类型而抛 NoClassDefFoundError。
 */
public class TaczCombatListener {

    @SubscribeEvent
    public void onGunHurtPre(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        var hurt = event.getHurtEntity();
        if (hurt instanceof LivingEntity living) {
            // 必须在 Pre 阶段标记，TACZ 自己的 hurt() 调用（会触发 vanilla LivingHurtEvent，
            // 被 MeleeCombatListener 捕获）发生在 Pre 之后、Post 之前，Post 再标记就晚了。
            CombatLogManager.claimTaczHit(living.getUUID());
        }
    }

    @SubscribeEvent
    public void onGunKill(EntityKillByGunEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;

        LivingEntity attacker = event.getAttacker();
        LivingEntity victim = event.getKilledEntity();
        if (!(attacker instanceof ServerPlayer attackerPlayer) || !(victim instanceof ServerPlayer victimPlayer)) {
            return;
        }
        if (attackerPlayer.getUUID().equals(victimPlayer.getUUID())) return;

        UUID victimId = victimPlayer.getUUID();
        CombatLogManager.claimTaczHit(victimId);

        // 存 gunId/gunDisplayId 而不是复制 ItemStack：客户端用这两个 id 去解析 TACZ 自己的
        // 图标和显示名，让这个类保持是唯一接触 com.tacz.* 类型的地方。
        HitRecord fatal = new HitRecord(
                attackerPlayer.getUUID(), attackerPlayer.getGameProfile().getName(),
                Math.max(0, Math.round(attackerPlayer.getHealth())), attackerPlayer.getMaxHealth(),
                event.getGunId(), event.getGunDisplayId(), ItemStack.EMPTY);

        CombatLogManager.recordKill(attackerPlayer, victimId); // 可选：不需要击杀计数的话可删除
        KillcamManager.setFatalHit(victimId, fatal);
    }
}
```

```java
// ===== MeleeCombatListener.java =====
package org.kevinseas.shotmchelper.killcam;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.kevinseas.shotmchelper.Shotmchelper;
import java.util.UUID;

/**
 * 捕获 TaczCombatListener 没覆盖的死亡：近战 PVP、非玩家来源的致命伤（怪物/摔落/烧伤/溺水/`/kill`）。
 * 用的是原版事件，TACZ 没装也能安全自动注册。
 *
 * TACZ 击杀同样会触发 LivingDeathEvent，所以每次 TACZ 击杀如果不做区分这里会重复记录一次
 * （把攻击者当时主手的东西当成"凶器"）。CombatLogManager.isTaczClaimed 就是这个去重闩：
 * TaczCombatListener 在它自己的 Pre 事件一发生就标记受害者，严格早于 hurt() 能走到 LivingDeathEvent。
 */
@Mod.EventBusSubscriber(modid = Shotmchelper.MODID)
public class MeleeCombatListener {

    private static final String ENVIRONMENTAL_LABEL = "环境伤害";
    private static final UUID ENVIRONMENTAL_ATTACKER_ID = new UUID(0L, 0L);

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (CombatLogManager.isTaczClaimed(victim.getUUID())) return;

        LivingEntity attacker = resolveAttacker(event);
        HitRecord fatal;
        if (attacker instanceof ServerPlayer attackerPlayer && !attackerPlayer.getUUID().equals(victim.getUUID())) {
            ItemStack weapon = attackerPlayer.getMainHandItem().copy();
            fatal = new HitRecord(attackerPlayer.getUUID(), attackerPlayer.getGameProfile().getName(),
                    Math.max(0, Math.round(attackerPlayer.getHealth())), attackerPlayer.getMaxHealth(), weapon);
        } else {
            // 没有玩家攻击者（怪物/摔落/烧伤/溺水/`/kill`）——固定伪 id，因为包协议需要一个真实的 attackerId。
            fatal = new HitRecord(ENVIRONMENTAL_ATTACKER_ID, ENVIRONMENTAL_LABEL, 0, 20f, ItemStack.EMPTY);
        }
        KillcamManager.setFatalHit(victim.getUUID(), fatal);
    }

    private static LivingEntity resolveAttacker(LivingDeathEvent event) {
        var entity = event.getSource().getEntity();
        return entity instanceof LivingEntity living ? living : null;
    }
}
```

```java
// ===== KillcamManager.java（核心流程，已精简：去掉黑屏/倒计时/配装传送，聚焦结束直接复活） =====
package org.kevinseas.shotmchelper.killcam;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
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
import org.kevinseas.shotmchelper.Shotmchelper;
import org.kevinseas.shotmchelper.network.*;

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
@Mod.EventBusSubscriber(modid = Shotmchelper.MODID)
public class KillcamManager {
    public static final int SPECTATING_TICKS = 80;  // 聚焦时长，4秒
    public static final int TURN_TICKS = 6;         // 转向杀手的缓动时长，0.3秒

    private static final Map<UUID, PendingDeath> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, KillcamState> ACTIVE = new ConcurrentHashMap<>();

    // TaczCombatListener/MeleeCombatListener 调用，记录这次死亡的凶器信息，供 respawn 时取用。
    // 单独一个 map 而不是塞进 PendingDeath：LivingDeathEvent（建 PendingDeath）和 TACZ 自己的击杀
    // 事件谁先触发没有文档保证的顺序，这样不管谁先到都能找到对方需要的数据。
    private static final Map<UUID, HitRecord> PENDING_FATAL = new ConcurrentHashMap<>();

    public static void setFatalHit(UUID victimId, HitRecord fatal) {
        PENDING_FATAL.put(victimId, fatal);
    }

    private record PendingDeath(int killerId) {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        int killerId = player.getKillCredit() != null ? player.getKillCredit().getId() : -1;
        PENDING.put(player.getUUID(), new PendingDeath(killerId));
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return;
        ServerPlayer player = (ServerPlayer) event.getEntity();
        UUID id = player.getUUID();
        PendingDeath pending = PENDING.remove(id);
        if (pending == null) return; // 没有死亡记录（比如 /kill 又被其它逻辑拦了）就什么都不做

        GameType previousGameType = player.gameMode.getGameModeForPlayer();
        Vec3 spawnPos = player.position();
        float spawnYaw = player.getYRot();
        float spawnPitch = player.getXRot();
        ResourceKey<Level> spawnDimension = player.level().dimension();

        KillcamState state = new KillcamState(previousGameType, pending.killerId(),
                spawnPos, spawnYaw, spawnPitch, spawnDimension);
        ACTIVE.put(id, state);

        // 把玩家"拉回"刚死的地方，切观察者，面朝杀手。
        player.setGameMode(GameType.SPECTATOR);
        // 这里补充：teleportTo 回死亡位置 + 朝向杀手的 lookAt（与原逻辑一致，略，见下方「补充说明」）。

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

    private static void sendTo(ServerPlayer player, Object packet) {
        NetworkHandler.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
```

```java
// ===== KillcamState.java（精简：去掉 Phase/loadoutSelected/respawned） =====
package org.kevinseas.shotmchelper.killcam;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class KillcamState {
    public int ticksInPhase = 0;
    public final GameType previousGameType;
    public final int killerId;
    public final Vec3 spawnPos;
    public final float spawnYaw;
    public final float spawnPitch;
    public final ResourceKey<Level> spawnDimension;

    public KillcamState(GameType previousGameType, int killerId,
                         Vec3 spawnPos, float spawnYaw, float spawnPitch, ResourceKey<Level> spawnDimension) {
        this.previousGameType = previousGameType;
        this.killerId = killerId;
        this.spawnPos = spawnPos;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
        this.spawnDimension = spawnDimension;
    }
}
```

## 网络包（只保留 4 个，去掉 Blackout/LoadoutStatus/LoadoutToggle）

```java
// KillcamLockPacket：死亡瞬间通知客户端进入聚焦状态（空包）
public record KillcamLockPacket() {
    public static void encode(KillcamLockPacket packet, FriendlyByteBuf buf) {}
    public static KillcamLockPacket decode(FriendlyByteBuf buf) { return new KillcamLockPacket(); }
    public static void handle(KillcamLockPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(KillcamClientHandler::onLock);
        context.get().setPacketHandled(true);
    }
}

// KillcamFacePacket：转向杀手
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

// KillcamHitLogPacket：淘汰面板数据（已去掉击杀计数两个字段）
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
            ResourceLocation gunId = null, gunDisplayId = null;
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

// KillcamEndPacket：聚焦结束+已复活通知（空包，不再需要 fadeTicks）
public record KillcamEndPacket() {
    public static void encode(KillcamEndPacket packet, FriendlyByteBuf buf) {}
    public static KillcamEndPacket decode(FriendlyByteBuf buf) { return new KillcamEndPacket(); }
    public static void handle(KillcamEndPacket packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(KillcamClientHandler::onEnd);
        context.get().setPacketHandled(true);
    }
}
```

## 客户端核心

```java
// ===== KillcamClientHandler.java（精简：去掉 Phase.BLACKOUT/NONE 渐变、loadoutSelected） =====
package org.kevinseas.shotmchelper.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.kevinseas.shotmchelper.killcam.HitRecord;
import org.kevinseas.shotmchelper.network.KillcamFacePacket;
import org.kevinseas.shotmchelper.network.KillcamHitLogPacket;

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
```

```java
// ===== KillcamClientEvents.java（精简：去掉黑屏 fill/倒计时文字/L键监听，只留输入锁定+面板渲染） =====
package org.kevinseas.shotmchelper.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.kevinseas.shotmchelper.Shotmchelper;
import org.kevinseas.shotmchelper.killcam.HitRecord;

@Mod.EventBusSubscriber(modid = Shotmchelper.MODID, value = Dist.CLIENT)
public class KillcamClientEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        KillcamClientHandler.clientTick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) { KillcamClientHandler.reset(); }
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) { KillcamClientHandler.reset(); }

    // 聚焦观察者期间清零移动输入，防止 LocalPlayer 自己的运动模拟把摄像机"走"离死亡位置，
    // 和服务端每 tick 的 teleportTo 打架。必须在 LocalPlayer.aiStep 消费输入之前清零。
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!KillcamClientHandler.isLocking()) return;
        var input = event.getInput();
        input.leftImpulse = 0f;
        input.forwardImpulse = 0f;
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
    }

    private static final int PANEL_MARGIN_X = 24;
    private static final int PANEL_MARGIN_Y = 48;
    private static final int PANEL_PADDING = 6;
    private static final int ROW_GAP = 8;
    private static final int WEAPON_ICON_SIZE = 16;
    private static final int ICON_TEXT_GAP = 5;
    private static final int SEGMENT_GAP = 10;
    private static final int HEALTH_BAR_WIDTH = 70;
    private static final int HEALTH_BAR_HEIGHT = 7;
    private static final int BORDER_RGB = 0xFFAA00;
    private static final int PANEL_BG_RGB = 0x000000;
    private static final int HEALTH_BAR_BG_RGB = 0x000000;
    private static final int HEALTH_BAR_FG_RGB = 0xFF5555;

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!KillcamClientHandler.shouldShowPanel()) return;
        renderPanel(event.getGuiGraphics());
    }

    // 屏幕左下角面板：攻击者名字+血条+血量 / 武器图标+武器名。
    private static void renderPanel(GuiGraphics graphics) {
        HitRecord fatal = KillcamClientHandler.getFatalHit();
        boolean taczLoaded = ModList.get().isLoaded("tacz");
        boolean isTaczWeapon = fatal.isTaczWeapon();
        boolean showWeapon = fatal.hasWeapon() && !(isTaczWeapon && !taczLoaded);

        var font = Minecraft.getInstance().font;
        int lineH = font.lineHeight;
        int screenHeight = graphics.guiHeight();

        String attackerName = fatal.attackerName();
        int nameWidth = font.width(attackerName);
        String healthText = fatal.attackerRemainingHealth() + "/" + Math.round(fatal.attackerMaxHealth());
        int healthTextWidth = font.width(healthText);
        int row1Width = nameWidth + SEGMENT_GAP + HEALTH_BAR_WIDTH + ICON_TEXT_GAP + healthTextWidth;
        int row1Height = Math.max(lineH, HEALTH_BAR_HEIGHT);

        String weaponName = showWeapon
                ? (isTaczWeapon ? TaczWeaponIconRenderer.resolveName(fatal) : fatal.weaponStack().getHoverName().getString())
                : "环境伤害";
        int row2Width = WEAPON_ICON_SIZE + ICON_TEXT_GAP + font.width(weaponName);
        int row2Height = Math.max(lineH, showWeapon ? WEAPON_ICON_SIZE : 0);

        int contentWidth = Math.max(row1Width, row2Width);
        int contentHeight = row1Height + ROW_GAP + row2Height;
        int panelX = PANEL_MARGIN_X;
        int panelY = screenHeight - PANEL_MARGIN_Y - (contentHeight + PANEL_PADDING * 2);
        int panelWidth = contentWidth + PANEL_PADDING * 2;
        int panelHeight = contentHeight + PANEL_PADDING * 2;

        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF000000 | PANEL_BG_RGB);
        drawBoxOutline(graphics, panelX, panelY, panelWidth, panelHeight, 0xFF000000 | BORDER_RGB);

        int contentX = panelX + PANEL_PADDING;
        int row1Y = panelY + PANEL_PADDING;
        int row2Y = row1Y + row1Height + ROW_GAP;
        int textColor = 0xFFFFFFFF;

        int x = contentX;
        int nameY = row1Y + (row1Height - lineH) / 2;
        graphics.drawString(font, attackerName, x, nameY, textColor, false);
        x += nameWidth + SEGMENT_GAP;

        int barY = row1Y + (row1Height - HEALTH_BAR_HEIGHT) / 2;
        float healthFraction = fatal.attackerMaxHealth() > 0f
                ? Mth.clamp(fatal.attackerRemainingHealth() / fatal.attackerMaxHealth(), 0f, 1f) : 0f;
        int filledWidth = Math.round(HEALTH_BAR_WIDTH * healthFraction);
        graphics.fill(x, barY, x + HEALTH_BAR_WIDTH, barY + HEALTH_BAR_HEIGHT, 0xFF000000 | HEALTH_BAR_BG_RGB);
        if (filledWidth > 0) {
            graphics.fill(x, barY, x + filledWidth, barY + HEALTH_BAR_HEIGHT, 0xFF000000 | HEALTH_BAR_FG_RGB);
        }
        x += HEALTH_BAR_WIDTH + ICON_TEXT_GAP;
        graphics.drawString(font, healthText, x, nameY, textColor, false);

        x = contentX;
        int row2TextY = row2Y + (row2Height - lineH) / 2;
        if (showWeapon) {
            int iconY = row2Y + (row2Height - WEAPON_ICON_SIZE) / 2;
            // TACZ 枪械走 GunItemBuilder 构造真实 ItemStack，再用原版 renderItem 渲染——
            // 和近战走同一条渲染路径，避免手动解析 TACZ 内部贴图路径。
            ItemStack weapon = isTaczWeapon ? TaczWeaponIconRenderer.buildIconStack(fatal) : fatal.weaponStack();
            if (!weapon.isEmpty()) graphics.renderItem(weapon, x, iconY);
            x += WEAPON_ICON_SIZE + ICON_TEXT_GAP;
        }
        graphics.drawString(font, weaponName, x, row2TextY, textColor, false);
    }

    private static void drawBoxOutline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }
}
```

```java
// ===== TaczWeaponIconRenderer.java（原样保留，TACZ 枪械图标/名字解析核心） =====
package org.kevinseas.shotmchelper.client;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;
import org.kevinseas.shotmchelper.killcam.HitRecord;

/**
 * 全 mod 唯一允许引用 com.tacz.* 类型的客户端类（和服务端 TaczCombatListener 同理）。
 * 调用方必须先确认 ModList.isLoaded("tacz")。
 *
 * 核心经验：不要手动解析 TACZ 内部的 GunDisplayInstance HUD 贴图路径去 blit——那条路走不通，
 * 而是用 TACZ 官方的 GunItemBuilder 构造一个真实的枪械 ItemStack（和发枪给玩家用的是同一套
 * API），再交给原版 GuiGraphics.renderItem 渲染，直接复用 TACZ 自己的物品渲染管线。
 */
public final class TaczWeaponIconRenderer {

    private TaczWeaponIconRenderer() {}

    public static ItemStack buildIconStack(HitRecord fatal) {
        return GunItemBuilder.create().setId(fatal.gunId()).setCount(1).build();
    }

    public static String resolveName(HitRecord fatal) {
        String name = TimelessAPI.getClientGunIndex(fatal.gunId())
                .map(ClientGunIndex::getName)
                .orElseGet(() -> fatal.gunId().getPath());
        return I18n.exists(name) ? I18n.get(name) : name;
    }
}
```

## 交给另一个 Agent 时要特别说明的点

这些是原 mod 里踩过的坑，直接照抄能省掉大量调试：

1. **TACZ 类型隔离**：`TaczCombatListener`/`TaczWeaponIconRenderer` 是全项目唯一能写 `com.tacz.*` 的地方，必须手动注册（在确认 `ModList.get().isLoaded("tacz")` 之后），不能用 `@Mod.EventBusSubscriber` 自动扫描，否则没装 TACZ 的环境直接 `NoClassDefFoundError` 崩溃。
2. **去重闩**：TACZ 击杀也会触发原版 `LivingDeathEvent`，`MeleeCombatListener` 靠 `CombatLogManager.isTaczClaimed`（一个 `Set<UUID>`，记一下 UUID、下一次死亡清空即可）避免同一次击杀被记两次。
3. **TACZ 图标渲染**：别自己解析 `GunDisplayInstance` 贴图路径手动 blit，用 `GunItemBuilder` 构造真实 `ItemStack` 走 `renderItem`，这是唯一验证可行的路径。CustumHud 里看似相关的 `TaczCompat` 贴图读取代码实际是死代码，不要参考。
4. **面板渐隐**：当前简化版没做渐入渐出，直接硬切；如果想要更顺滑的效果，可以在 `onEnd`/`onLock` 附近自己加个简单的 alpha 淡出。
5. **复活时机**：面板在 `onEnd` 收到后清空，和复活同一瞬间消失，符合"聚焦结束=复活=面板消失"的设计。
6. **`KillcamManager.onPlayerRespawn`** 里 `teleportTo` 回死亡位置并计算朝向杀手的具体代码在本文档中用注释占位（原文件 `ShotMCHelper` 里有完整实现），抽取时需要补全这部分逐行照抄。
