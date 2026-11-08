package org.kevinseas.deathfocus.killcam;

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
