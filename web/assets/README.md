# Weapon assets

Weapons are procedural box models by default. A weapon listed in
`manifest.json` gets a real glTF model instead; anything missing, or any file
that fails to load, silently keeps its procedural model, so models can be
added one at a time.

```json
{ "weapons": {
    "0": { "model": "models/pistol.glb", "scale": 2.2, "position": [0, -0.12, -0.05],
           "muzzle": [0, -0.03, -0.3], "iconRotationY": 2.2 }
} }
```

| key | meaning |
| --- | --- |
| weapon index | `0` pistol, `1` rifle, `2` sniper, `3` SMG, `4` knife (see `js/weapons.js`) |
| `model` | path under `assets/` to a `.glb` |
| `scale` | file units -> first-person view-model size (a ~0.2 m pistol needs about 2.2) |
| `position` | `[x, y, z]` nudge inside the weapon's hand slot |
| `rotationY` | optional extra yaw in radians |
| `muzzle` | where the muzzle flash appears (hand-slot coordinates) |
| `iconRotationY` | yaw (radians) the loadout preview sways around; pick the angle that shows the model's best side |

## Model conventions

- Export `.glb` with PBR materials (base color, normal, metallic-roughness,
  occlusion, emissive all work). `MeshStandardMaterial` is created by the loader.
- +Y up, muzzle toward **-Z**, origin near the receiver/grip.
- Metals need something to reflect: the game applies a neutral studio
  environment map to every loaded material, so no lighting setup is needed.
- Keep textures modest (1-2K) and use JPEG/WebP inside the file where
  transparency isn't needed; the models load before the menu appears.
- Loading code: `js/assets.js`; construction and fallback: `js/weaponModels.js`.

## The stand-in models

`models/pistol.glb` and `models/sniper.glb` were generated from the side views
on the reference sheets by `tools/SilhouetteGlb.java` (silhouette extruded a few
centimetres, the photo as base color, normal and metallic-roughness maps derived
from it). They are cut-outs with painted-on detail -- placeholders for real
modelled weapons -- and were made with:

```
java tools/SilhouetteGlb.java assets/pistol_reference.png 172 72 800 520 assets/models/pistol.glb 0.213 0.030 26 3 0 8
java tools/SilhouetteGlb.java assets/sniper_reference.png 996 80 530 124 assets/models/sniper.glb 1.25 0.06 24 2 1 2
```

Run `java tools/SilhouetteGlb.java` with no arguments for the parameter list.
