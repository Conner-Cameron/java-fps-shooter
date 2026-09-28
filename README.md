# Java FPS Shooter

A first-person shooter with two clients that share one game:

- **Desktop client** (this project's Java sources) -- LWJGL 3 (GLFW + OpenGL 3.3)
  and JOML; runs as a jar or a packaged Windows app.
- **Browser client** (`web/`) -- three.js, served by `web/server/GameServer.java`,
  which is also the authoritative multiplayer server both clients talk to.

Both offer the same two modes and feature set:

- **PvP Deathmatch** -- online, first to 10 kills wins the room. The server owns
  spawns, validates movement and shots, and resolves hits. Pick **Quick Play**
  (auto-matchmaking into the fullest public room), **Create Room** (private, 4-letter
  code to share) or **Join by Code**. Rooms hold 8 players; all-time kills/wins
  show as a leaderboard on the setup screen.
- **Aim Training** -- private solo range with target blocks that roll their own
  size, hitpoints and drift.

Shared between the clients: the level (`web/map.json` -- walls, multi-floor
building with windows, spiral ramp, trees, rocks), the weapons (pistol, rifle,
sniper, SMG, plus a knife you scroll to), the art (`web/assets/`: reference
photos and glTF/PBR weapon models), and the protocol.

## Features (desktop client)

- Menu flow: mode select -> loadout (damage/ammo/description per weapon, live 3D
  previews) -> setup -> play; the loadout is locked in until you leave the game
- Movement with sprint, jump, gravity, and full collision (walls, ceilings,
  the building, the ramp)
- Weapons with per-weapon damage, magazines, reload, fire rate (SMG is automatic),
  hip-fire spread that the crosshair shows, aim-down-sights per weapon, and a real
  sniper scope; a 100-damage knife within arm's reach
- Hit marker, health/ammo HUD, scoreboard, death overlay, match banner
- Synthesized sound effects (gunshots per weapon, reload, knife whoosh, hit tick)
- PvP lobby picker (quick play / create / join by code), room code in the HUD, and a
  "waking the server" screen for free hosting that has gone to sleep
- PvP over WebSocket (JDK `java.net.http`, no extra dependency) to the live
  server by default, or any `ws://host:port/ws` you type on the PvP setup screen
- Weapon models loaded from `assets/manifest.json` (a small glTF/PBR loader and
  shader); a weapon with no model keeps its box model

## Controls

| Input          | Action                                   |
|----------------|-------------------------------------------|
| `W A S D`      | Move                                      |
| Mouse          | Look                                      |
| `Space`        | Jump                                      |
| `Left Shift`   | Sprint                                    |
| Left click     | Shoot (hold for the SMG) / knife swing    |
| Right click    | Aim down sights (not with the knife)      |
| Scroll wheel   | Toggle knife / your class weapon          |
| `R`            | Reload                                    |
| `Esc`          | Back (menus) / pause menu in game: Resume, **Leave Game** (back to mode select), Quit |

## Requirements

- JDK 17+
- Maven 3.8+

LWJGL's native binaries are resolved automatically for Windows, macOS
(Intel/Apple Silicon) and Linux (x86_64/arm64) via Maven profiles in `pom.xml`.

## Running

```bash
mvn compile exec:java                       # from source
mvn package && java -jar target/java-fps-shooter.jar
java -jar target/java-fps-shooter.jar --server ws://localhost:8080/ws   # default PvP server
```

The build packages `web/map.json` and `web/assets/` into the jar, so the desktop
client always ships with the same level and art as the browser game.

A Windows app image (no JDK needed to run it) can be made from the shaded jar:

```bash
jpackage --type app-image --name JavaFpsShooter --input <dir with the jar> \
  --main-jar java-fps-shooter.jar --main-class com.conner.fps.Main --dest dist \
  --add-modules java.desktop,java.net.http,java.logging,jdk.unsupported
```

## Scripted self-test

`SelfTest` drives the real client the way a player would (clicking menu buttons,
holding keys, scrolling, aiming at targets) and saves screenshots:

```bash
java -jar target/java-fps-shooter.jar --selftest training --out selftest-output
java -jar target/java-fps-shooter.jar --selftest pvp --server ws://localhost:8080/ws --out selftest-output
```

It prints `OK`/`FAIL` per check and exits non-zero on failure. The `pvp` scenario
needs a local server (`java web/server/GameServer.java`).

## Hosting the server (Render free tier)

`render.yaml` + `Dockerfile` run `web/server/GameServer.java` as one web service (site, WebSocket
and API on one port). Things worth knowing on the free plan:

- **It sleeps after ~15 minutes without traffic** and takes up to a minute to wake. The clients
  cope: the browser page pings `/health` every few minutes while it's open, and the desktop
  client polls `/health` first and shows "Waking the server..." instead of a dead screen. Nothing
  can keep it awake once every client is closed -- that needs a paid plan or an external pinger.
- **Lifetime stats are ephemeral there.** `STATS_FILE` (default `data/stats.json`) is written every
  20 s and on shutdown, but the free plan's disk is wiped on each deploy or restart. Point
  `STATS_FILE` at a persistent disk (paid plans) or another host to keep the leaderboard.
- Environment: `PORT` (injected by the host), `MAX_ROOM_PLAYERS` (default 8), `STATS_FILE`.
- Guard rails: 300 connections, 16 KB max frame, 300 messages/s per client, sockets that never
  join are dropped after 15 s, idle sockets after 75 s (the server pings every 25 s).
- `GET /health` -> `{"ok":true,"rooms":N,"players":N,"uptimeSec":N}`; `GET /stats` -> leaderboard JSON.

## Project layout

```
src/main/java/com/conner/fps/
  Main.java, Game.java   entry point; menus -> Aim Training / PvP loop
  SelfTest.java          scripted end-to-end scenarios
  data/                  Weapons (stat table), MapData (map.json loader)
  engine/                window, input, shader wrapper, screenshots
  game/                  Player (movement), WeaponState, Practice (targets),
                         PvpSession (match state), Menus
  net/                   GameClient (WebSocket, wake-up + room join), ServerInfo (/health, /stats)
  render/                Ui + FontAtlas (2D), Hud, GunModels, GlbModel (glTF),
                         meshes, procedural textures
  world/                 World (level + collision), Ramp
  audio/                 Sounds (synthesized effects)
  util/                  Json, ray/box math
src/main/resources/shaders/
  vertex/fragment (scene), pbr_fragment (glTF), sky_*, ui_*
web/                     the browser client, server, shared map and assets
```
