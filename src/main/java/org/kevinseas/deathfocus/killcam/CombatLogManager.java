package org.kevinseas.deathfocus.killcam;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 去重闩：TACZ 击杀会同时触发 TACZ 自己的事件和原版 LivingDeathEvent，
 * MeleeCombatListener 靠这里的 claimedTacz 集合判断某次死亡是否已经被
 * TaczCombatListener 记录过致命伤，避免同一次击杀被记两次。
 *
 * 本 mod 不保留跨死亡的击杀计数历史，只做"这次死亡有没有被 TACZ 认领"的瞬时标记。
 */
public final class CombatLogManager {

    private static final Set<UUID> CLAIMED_TACZ = ConcurrentHashMap.newKeySet();

    private CombatLogManager() {}

    /** TaczCombatListener 在确定一次击杀/受击属于 TACZ 时调用，标记受害者已被认领。 */
    public static void claimTaczHit(UUID victimId) {
        CLAIMED_TACZ.add(victimId);
    }

    /** MeleeCombatListener 在处理 LivingDeathEvent 前检查这次死亡是否已被 TACZ 认领。 */
    public static boolean isTaczClaimed(UUID victimId) {
        return CLAIMED_TACZ.remove(victimId);
    }
}
