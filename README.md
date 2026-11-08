# DeathFocus

A Minecraft Forge mod for 1.20.1 that replaces the vanilla death screen with a short
**killcam-style focus on your killer**, plus an on-screen panel showing who killed you
and with what.

## What it does

When a player dies:

1. The death plays out normally — drops, death message, everything vanilla does.
2. Instead of the respawn screen, the player is pulled back to the exact spot and
   moment they died, switched to spectator mode, and the camera turns to face
   whoever (or whatever) killed them.
3. An on-screen panel in the bottom-left shows the attacker's name, a health bar,
   and the weapon used (melee item, or a TACZ gun's icon and name if the kill was
   made with [TACZ](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero)).
4. After about 4 seconds, the player is teleported back to their respawn point,
   switched back to their previous game mode, and play resumes — no blackout,
   no countdown, no loadout screen.

During the focus phase, mouse look is locked so the camera stays on the killer;
movement input is also suppressed so the player can't walk away from the death
location while it's being held in place server-side.

## Combat sources handled

- **Melee / any vanilla damage source** with a player attacker — the attacker's
  current main-hand item is shown as the weapon.
- **TACZ gun kills** — detected without a compile-time or runtime-mandatory
  dependency on TACZ. If TACZ isn't installed, this code path is simply never
  reached; nothing breaks.
- **Environmental deaths** (fall, fire, drowning, `/kill`, etc. — no player
  attacker) — shown as plain environmental damage, no weapon.

## TACZ support is optional

TACZ is a soft dependency. DeathFocus never has a hard compile-time or runtime
dependency on it:

- All TACZ interaction (listening for its gun-kill event, building an `ItemStack`
  for the gun icon, resolving the gun's display name) goes through reflection,
  gated behind `ModList.isLoaded("tacz")`.
- If TACZ is absent, that code path is never executed and nothing fails to load.
- If TACZ is present, kills made with it show the gun's actual icon and name in
  the panel, rendered through TACZ's own `GunItemBuilder` + vanilla item
  rendering — no manual texture parsing.

## Project layout

```
src/main/java/org/kevinseas/deathfocus/
├── Deathfocus.java            Mod entry point: registers the network channel,
│                               registers TACZ support if present
├── Config.java                 Forge config (template boilerplate, unrelated to killcam)
├── killcam/                     Server-side logic
│   ├── HitRecord.java            Attacker/weapon data for one death
│   ├── CombatLogManager.java      De-dupe latch between TACZ and melee listeners
│   ├── MeleeCombatListener.java   Catches non-TACZ deaths
│   ├── TaczSupport.java           TACZ gun-kill detection via reflection
│   ├── KillcamState.java          Per-player active-focus state
│   └── KillcamManager.java        Death → focus → respawn flow
├── network/                     Four packets (lock / face killer / hit info / end)
│   └── NetworkHandler.java
├── client/                      Client-side rendering and camera control
│   ├── KillcamClientHandler.java   Camera turn animation, lock state
│   ├── KillcamClientEvents.java    Panel rendering, input suppression
│   └── TaczWeaponIconRenderer.java TACZ icon/name resolution via reflection
└── mixin/
    └── MouseHandlerMixin.java     Zeroes accumulated mouse delta during focus
                                    so mouse movement can't turn the camera
```

## Requirements

- Minecraft 1.20.1
- Forge 47.4.28 or newer
- [TACZ](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero)
  (optional) — only needed if you want gun kills identified and their icon shown

## Building

Standard ForgeGradle workflow:

```
./gradlew build
```

The mod jar is written to `build/libs/`.

## License

[MIT](LICENSE)
