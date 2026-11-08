package org.kevinseas.deathfocus.client;

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
import org.kevinseas.deathfocus.Deathfocus;
import org.kevinseas.deathfocus.killcam.HitRecord;

@Mod.EventBusSubscriber(modid = Deathfocus.MODID, value = Dist.CLIENT)
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
