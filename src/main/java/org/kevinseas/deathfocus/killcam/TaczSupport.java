package org.kevinseas.deathfocus.killcam;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 全 mod 唯一允许接触 TACZ（com.tacz.*）相关类型的地方——但做成反射调用而不是编译期 import，
 * 因为本项目没有引入 TACZ 作为编译依赖。这样既能保持“没装 TACZ 的服务端不会因为解析不到
 * com.tacz.* 类型而崩溃”这一原始设计目标，又不需要额外的 TACZ Maven 依赖就能编译整个 mod。
 *
 * 调用方必须先确认 {@link #isLoaded()} 为 true 才能调用 {@link #register(IEventBus)}。
 * 任何反射失败都会被吞掉并记日志，绝不向上抛出导致游戏崩溃。
 */
public final class TaczSupport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "tacz";

    private static final String EVENT_HURT_PRE_CLASS = "com.tacz.guns.api.event.common.EntityHurtByGunEvent$Pre";
    private static final String EVENT_KILL_CLASS = "com.tacz.guns.api.event.common.EntityKillByGunEvent";

    // IEventBus 没有 (Class, Consumer) 的两参数重载，具体事件类型版本是
    // addListener(EventPriority, boolean receiveCancelled, Class<T>, Consumer<T>)，四个参数。
    private static final Method ADD_LISTENER;

    static {
        Method method;
        try {
            method = IEventBus.class.getMethod("addListener",
                    EventPriority.class, boolean.class, Class.class, Consumer.class);
        } catch (ReflectiveOperationException e) {
            method = null;
        }
        ADD_LISTENER = method;
    }

    private TaczSupport() {}

    public static boolean isLoaded() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在确认 TACZ 已加载后调用：往事件总线注册两个监听器，全部通过反射完成。 */
    public static void register(IEventBus eventBus) {
        if (!isLoaded() || ADD_LISTENER == null) return;
        try {
            registerHurtPreListener(eventBus);
            registerKillListener(eventBus);
        } catch (Throwable t) {
            LOGGER.error("DeathFocus: 注册 TACZ 事件监听失败，TACZ 击杀将不会被识别。", t);
        }
    }

    private static void registerHurtPreListener(IEventBus eventBus) throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName(EVENT_HURT_PRE_CLASS);
        Consumer<Object> handler = TaczSupport::onGunHurtPre;
        ADD_LISTENER.invoke(eventBus, EventPriority.NORMAL, false, eventClass, handler);
    }

    private static void registerKillListener(IEventBus eventBus) throws ReflectiveOperationException {
        Class<?> eventClass = Class.forName(EVENT_KILL_CLASS);
        Consumer<Object> handler = TaczSupport::onGunKill;
        ADD_LISTENER.invoke(eventBus, EventPriority.NORMAL, false, eventClass, handler);
    }

    /**
     * 对应原 TaczCombatListener#onGunHurtPre(EntityHurtByGunEvent.Pre)。
     * 必须在 Pre 阶段标记，TACZ 自己的 hurt() 调用（会触发 vanilla LivingHurtEvent，
     * 被 MeleeCombatListener 捕获）发生在 Pre 之后、Post 之前，Post 再标记就晚了。
     */
    private static void onGunHurtPre(Object event) {
        try {
            LogicalSide side = (LogicalSide) invoke(event, "getLogicalSide");
            if (side != LogicalSide.SERVER) return;
            LivingEntity hurt = (LivingEntity) invoke(event, "getHurtEntity");
            if (hurt != null) {
                CombatLogManager.claimTaczHit(hurt.getUUID());
            }
        } catch (Throwable t) {
            LOGGER.error("DeathFocus: 处理 TACZ EntityHurtByGunEvent.Pre 失败。", t);
        }
    }

    /** 对应原 TaczCombatListener#onGunKill(EntityKillByGunEvent)。 */
    private static void onGunKill(Object event) {
        try {
            LogicalSide side = (LogicalSide) invoke(event, "getLogicalSide");
            if (side != LogicalSide.SERVER) return;

            LivingEntity attacker = (LivingEntity) invoke(event, "getAttacker");
            LivingEntity victim = (LivingEntity) invoke(event, "getKilledEntity");
            if (!(attacker instanceof ServerPlayer attackerPlayer) || !(victim instanceof ServerPlayer victimPlayer)) {
                return;
            }
            if (attackerPlayer.getUUID().equals(victimPlayer.getUUID())) return;

            ResourceLocation gunId = (ResourceLocation) invoke(event, "getGunId");
            ResourceLocation gunDisplayId = (ResourceLocation) invoke(event, "getGunDisplayId");

            UUID victimId = victimPlayer.getUUID();
            CombatLogManager.claimTaczHit(victimId);

            // 存 gunId/gunDisplayId 而不是复制 ItemStack：客户端用这两个 id 去解析 TACZ 自己的
            // 图标和显示名，让这个类保持是唯一接触 TACZ 的地方。
            HitRecord fatal = new HitRecord(
                    attackerPlayer.getUUID(), attackerPlayer.getGameProfile().getName(),
                    Math.max(0, Math.round(attackerPlayer.getHealth())), attackerPlayer.getMaxHealth(),
                    gunId, gunDisplayId, ItemStack.EMPTY);

            KillcamManager.setFatalHit(victimId, fatal);
        } catch (Throwable t) {
            LOGGER.error("DeathFocus: 处理 TACZ EntityKillByGunEvent 失败。", t);
        }
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
