package org.kevinseas.deathfocus.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.kevinseas.deathfocus.killcam.HitRecord;
import org.slf4j.Logger;

/**
 * 全 mod 唯一允许接触 TACZ 客户端类型的地方（和服务端 TaczSupport 同理），但同样做成
 * 反射调用而不是编译期 import，因为本项目没有引入 TACZ 作为编译依赖。
 * 调用方必须先确认 TACZ 已加载（{@code ModList.get().isLoaded("tacz")}）才能调用本类方法。
 *
 * 核心经验：不要手动解析 TACZ 内部的 GunDisplayInstance HUD 贴图路径去 blit——那条路走不通，
 * 而是用 TACZ 官方的 GunItemBuilder 构造一个真实的枪械 ItemStack（和发枪给玩家用的是同一套
 * API），再交给原版 GuiGraphics.renderItem 渲染，直接复用 TACZ 自己的物品渲染管线。
 */
public final class TaczWeaponIconRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String GUN_ITEM_BUILDER_CLASS = "com.tacz.guns.api.item.builder.GunItemBuilder";
    private static final String TIMELESS_API_CLASS = "com.tacz.guns.api.TimelessAPI";
    private static final String CLIENT_GUN_INDEX_CLASS = "com.tacz.guns.client.resource.index.ClientGunIndex";

    private TaczWeaponIconRenderer() {}

    public static ItemStack buildIconStack(HitRecord fatal) {
        try {
            Class<?> builderClass = Class.forName(GUN_ITEM_BUILDER_CLASS);
            Object builder = builderClass.getMethod("create").invoke(null);
            builder = builderClass.getMethod("setId", ResourceLocation.class).invoke(builder, fatal.gunId());
            builder = builderClass.getMethod("setCount", int.class).invoke(builder, 1);
            return (ItemStack) builderClass.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            LOGGER.error("DeathFocus: 构造 TACZ 枪械图标 ItemStack 失败。", t);
            return ItemStack.EMPTY;
        }
    }

    public static String resolveName(HitRecord fatal) {
        try {
            Class<?> timelessApiClass = Class.forName(TIMELESS_API_CLASS);
            Class<?> clientGunIndexClass = Class.forName(CLIENT_GUN_INDEX_CLASS);
            Object optional = timelessApiClass.getMethod("getClientGunIndex", ResourceLocation.class)
                    .invoke(null, fatal.gunId());
            boolean present = (boolean) optional.getClass().getMethod("isPresent").invoke(optional);
            if (present) {
                Object clientGunIndex = optional.getClass().getMethod("get").invoke(optional);
                String name = (String) clientGunIndexClass.getMethod("getName").invoke(clientGunIndex);
                return I18n.exists(name) ? I18n.get(name) : name;
            }
        } catch (Throwable t) {
            LOGGER.error("DeathFocus: 解析 TACZ 枪械名称失败。", t);
        }
        return fatal.gunId().getPath();
    }
}
