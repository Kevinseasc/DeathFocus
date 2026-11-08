# 鼠标视角锁定 Mixin —— 核心代码

killcam 期间（`KillcamClientHandler.isLocking()` 为 true 时）禁止鼠标滑动改变玩家视角的核心实现。

## 背景：为什么不能直接取消整个方法

`MouseHandler.turnPlayer()` 是客户端唯一把原始鼠标位移转换成玩家 yaw/pitch 的地方（通过 `Player.turn`）。原始鼠标位移在**每一次** GLFW 鼠标移动回调里都会被 `onMove()` 累加进 `accumulatedDX`/`accumulatedDY`，这个累加和 `turnPlayer()` 是否消费/清空它完全独立。

最初的实现是在 killcam 期间直接 `cancellable` 取消整个 `turnPlayer()` 方法体——这确实能冻住摄像机，但也跳过了方法末尾本该清零累加器的那一步。于是黑屏期间玩家的每一点鼠标抖动都会不断堆积在累加器里不被消费；`isLocking()` 一变回 false，下一次 `turnPlayer()` 调用会把堆积的全部位移一次性应用——表现为**黑屏淡出的瞬间视角猛地往上/往下甩一下**的 bug。

修复方式：改成在方法开头把累加器清零、让方法正常往下执行，这样它会算出一个零位移并按正常流程自我清空，等同于"这一帧没有任何鼠标移动"，之后不会有任何残留位移可以被应用。

## 核心代码

```java
// ===== MouseHandlerMixin.java =====
package org.kevinseas.shotmchelper.mixin;

import net.minecraft.client.MouseHandler;
import org.kevinseas.shotmchelper.client.KillcamClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * turnPlayer() is the only place raw mouse motion is turned into a yaw/pitch change on the client
 * player (via Player.turn). Raw mouse motion itself is accumulated into accumulatedDX/accumulatedDY by
 * onMove() on every single GLFW mouse-move callback - completely independent of whether turnPlayer()
 * ever consumes/clears it. Cancelling the whole method body during the killcam (as this used to do) did
 * freeze the camera, but it also skipped the accumulator reset at the end of turnPlayer(), so every bit
 * of mouse motion during the blackout kept piling up unconsumed; the instant isLocking() turned false
 * again, the very next turnPlayer() call applied the entire pent-up delta in one frame - the "look snaps
 * upward right as the blackout fades" bug. Zeroing the accumulators here instead (and letting the method
 * run normally) makes it compute a zero turn and clear itself exactly like a frame with no mouse motion,
 * so nothing is left to apply later.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void shotmchelper$blockTurnDuringKillcam(CallbackInfo ci) {
        if (KillcamClientHandler.isLocking()) {
            accumulatedDX = 0.0;
            accumulatedDY = 0.0;
        }
    }
}
```

## Mixin 注册配置

```json
// ===== src/main/resources/mixins.<modid>.json =====
{
  "required": true,
  "minVersion": "0.8",
  "package": "<your.mod.package>.mixin",
  "compatibilityLevel": "JAVA_17",
  "refmap": "<modid>.refmap.json",
  "client": [
    "MouseHandlerMixin"
  ]
}
```

`mods.toml`（或等效的 Forge mod 元信息文件）里需要声明这个 mixin 配置文件才会被加载，通常形如：

```toml
[[mixins]]
config = "mixins.<modid>.json"
```

## 迁移到新 mod 时要注意的点

1. **依赖的状态来源**：`KillcamClientHandler.isLocking()` 是外部条件——这个 mixin 本身不含任何状态，只是读一个布尔开关。移植到新 mod 时，把这个判断换成你自己项目里"当前是否应该冻结视角"的等效状态查询即可（比如一个简单的 `static boolean` 或你自己的状态管理类的方法）。
2. **不要用 `cancellable` 整体取消方法**：这是本文最核心的教训，再次强调——取消整个 `turnPlayer()` 会导致累加器不被清空，造成解锁瞬间视角猛甩的 bug。必须让方法正常执行到底，只在方法开头把累加器预先清零。
3. **`@Shadow` 字段名必须和目标类实际字段名完全一致**（`accumulatedDX`/`accumulatedDY` 是 Mojang 官方映射下 `MouseHandler` 的字段名），如果你用的是其他映射（如 Yarn/Intermediary），需要用对应的字段名或配合 refmap 重映射。
