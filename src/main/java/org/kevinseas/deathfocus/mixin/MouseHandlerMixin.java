package org.kevinseas.deathfocus.mixin;

import net.minecraft.client.MouseHandler;
import org.kevinseas.deathfocus.client.KillcamClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * turnPlayer() 是客户端唯一把原始鼠标位移转换成玩家 yaw/pitch 的地方（通过 Player.turn）。
 * 原始鼠标位移在每一次 GLFW 鼠标移动回调里都会被 onMove() 累加进 accumulatedDX/accumulatedDY，
 * 这个累加和 turnPlayer() 是否消费/清空它完全独立。
 *
 * 最初的实现是在 killcam 期间直接 cancellable 取消整个 turnPlayer() 方法体——这确实能冻住摄像机，
 * 但也跳过了方法末尾本该清零累加器的那一步。于是聚焦期间玩家的每一点鼠标抖动都会不断堆积在累加器里
 * 不被消费；isLocking() 一变回 false，下一次 turnPlayer() 调用会把堆积的全部位移一次性应用——
 * 表现为聚焦结束瞬间视角猛地往上/往下甩一下的 bug。
 *
 * 修复方式：改成在方法开头把累加器清零、让方法正常往下执行，这样它会算出一个零位移并按正常流程
 * 自我清空，等同于"这一帧没有任何鼠标移动"，之后不会有任何残留位移可以被应用。
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void deathfocus$blockTurnDuringKillcam(CallbackInfo ci) {
        if (KillcamClientHandler.isLocking()) {
            accumulatedDX = 0.0;
            accumulatedDY = 0.0;
        }
    }
}
