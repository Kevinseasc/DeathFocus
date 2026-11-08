package org.kevinseas.deathfocus.killcam;

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
