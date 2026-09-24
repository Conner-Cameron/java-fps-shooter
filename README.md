# Java FPS Shooter

A basic first-person shooter built in Java using [LWJGL 3](https://www.lwjgl.org/)
(GLFW + OpenGL) and [JOML](https://github.com/JOML-CI/JOML) for math. It's a
small "shooting range" arena meant as a clean starting point for a bigger game:
a fly-cam player, a handful of cover walls, and red cube targets you can shoot
that respawn elsewhere in the arena when hit.

## Features

- Windowed OpenGL 3.3 core-profile renderer
- Mouse-look + WASD movement (space/shift to rise/descend)
- Hit-scan shooting via ray/AABB intersection against target cubes
- Score tracked and shown live in the window title
- Simple crosshair overlay

## Controls

| Input          | Action                     |
|----------------|-----------------------------|
| `W A S D`      | Move                        |
| Mouse          | Look around                 |
| `Space`        | Rise                        |
| `Left Shift`   | Descend                     |
| Left click     | Shoot                       |
| `Esc`          | Quit                        |

## Requirements

- JDK 17+
- Maven 3.8+

LWJGL's native binaries are resolved automatically for Windows, macOS
(Intel/Apple Silicon) and Linux (x86_64/arm64) via Maven profiles in `pom.xml`
— no manual setup needed.

## Running

```bash
mvn compile exec:java
```

## Building a runnable jar

```bash
mvn package
java -jar target/java-fps-shooter.jar
```

## Project layout

```
src/main/java/com/conner/fps/
  Main.java              entry point
  Game.java              game loop: input -> update -> render
  engine/                window, input, camera, shader wrapper
  render/                cube mesh + crosshair (raw OpenGL buffers)
  world/                 obstacle and target entities
  util/                  ray/AABB intersection math
src/main/resources/shaders/
  vertex.glsl / fragment.glsl               scene shader
  crosshair_vertex.glsl / crosshair_fragment.glsl
```

## Ideas for extending this

- Enemy AI that moves and shoots back
- Weapon switching / reload / ammo
- Textured meshes and lighting instead of flat colors
- Level loading from a data file instead of hardcoded obstacles
- A HUD (health, ammo count) rendered as a screen-space overlay
