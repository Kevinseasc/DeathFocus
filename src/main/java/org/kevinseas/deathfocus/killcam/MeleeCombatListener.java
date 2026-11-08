package org.kevinseas.deathfocus.killcam;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.kevinseas.deathfocus.Deathfocus;

import java.util.UUID;

/**
 * 捕获 TaczCombatListener 没覆盖的死亡：近战 PVP、非玩家来源的致命伤（怪物/摔落/烧伤/溺水/`/kill`）。
 * 用的是原版事件，TACZ 没装也能安全自动注册。
 *
 * TACZ 击杀同样会触发 LivingDeathEvent，所以每次 TACZ 击杀如果不做区分这里会重复记录一次
 * （把攻击者当时主手的东西当成"凶器"）。CombatLogManager.isTaczClaimed 就是这个去重闩：
 * TaczCombatListener 在它自己的 Pre 事件一发生就标记受害者，严格早于 hurt() 能走到 LivingDeathEvent。
 */
@Mod.EventBusSubscriber(modid = Deathfocus.MODID)
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
