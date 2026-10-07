---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_8094f94381d811f184135254006c9bbf
    ReservedCode1: gqn1OpWwWsqDtEUBC3vM96mMB3q0FDeGELebsd1Wu0c2hb7jDVx2BwU71OcXNoxb3o9JC6Jy5ebajC1XZ9y36FeDQgBbPd/+Ss4nq0agufnfuDSqvuFrHrOAlkELvFyQYFLpi0B3UBMbd50vlDplA5FR/2TLNC5mNTjqYj0DJBMXDVvbLvhu/QO/aJM=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_8094f94381d811f184135254006c9bbf
    ReservedCode2: gqn1OpWwWsqDtEUBC3vM96mMB3q0FDeGELebsd1Wu0c2hb7jDVx2BwU71OcXNoxb3o9JC6Jy5ebajC1XZ9y36FeDQgBbPd/+Ss4nq0agufnfuDSqvuFrHrOAlkELvFyQYFLpi0B3UBMbd50vlDplA5FR/2TLNC5mNTjqYj0DJBMXDVvbLvhu/QO/aJM=
---

# P2 implementation plan

P2 has two sub-features:

1. **in-game Xaero World Map overlay** — Mixin into Xaero's map render pipeline to draw road network directly on the world map screen
2. **web bridge** — serve Xaero's cached map tiles as a Leaflet tile layer so the browser preview shows actual terrain instead of a dark grid

Xaero Minimap is out of scope. Only the full world map (M key) is targeted.

---

## 1. mixin infrastructure

Create the mixin configuration that does not exist yet. Wayfarer currently has zero mixin classes and no `wayfarer.mixins.json`.

- add `wayfarer.mixins.json` to `src/main/resources/` and register it in `fabric.mod.json`
- add `@Environment(EnvType.CLIENT)` annotation + `@Mixin` imports to the dependency set
- add a version-detection utility that reads Xaero WorldMap's `fabric.mod.json` at startup, compares against a support matrix, and disables the overlay mixin + warns the user if the version does not match
- wire this check into `WayfarerClient` initialization

Expected result: the project compiles with a valid mixin config. Xaero version detection runs on startup and logs to `logs/wayfarer.log`. No overlay rendering yet.

---

## 2. 26.2 mixin — core rendering

Implement the overlay on the latest version first. MC 26.2 uses Xaero WorldMap 26.1.2+ and the new `extractRenderState(GuiGraphicsExtractor, int, int, float)` API. The old `render(GuiGraphics, int, int, float)` does not exist in this version.

### 2.1 GuiGraphicsExtractor exploration

`GuiGraphicsExtractor` is a private Xaero abstraction. Before writing the mixin, determine how to obtain a `PoseStack` and `MultiBufferSource` from it.

- use `shell_executor` to run the 26.2 dev client with a test mixin that logs the `GuiGraphicsExtractor` class's public methods via reflection
- check whether `pose()` or `bufferSource()` methods exist
- fallback: use `Minecraft.getInstance().renderBuffers()` directly if the Extractor API is unusable

Expected result: a confirmed approach for getting rendering primitives in the 26.2 injection context.

### 2.2 GuiMapMixin

Inject at `xaero.map.gui.GuiMap.extractRenderState(GuiGraphicsExtractor, int, int, float)` TAIL.

- `@Shadow` fields: `scale`, `cameraX`, `cameraZ`
- coordinate projection: `pose.translate(screenCenter) → pose.scale(effectiveScale) → pose.translate(-cameraX, -cameraZ)`, same formula validated in xaero-train-map
- `effectiveScale = mapScale / guiScale`, where `guiScale = screenWidth / guiScaledWidth`
- render: iterate `RoadDataStore.getRoads()`, cull to viewport bounds, draw each road as a sequence of line segments using `Tessellator` + `RenderType.lines()`
- color by `classification`: G=`0xFFFF0000`, S=`0xFFFF8800`, X=`0xFFFFDD00`, Y=`0xFF00AA00`, C=`0xFFAAAAAA`
- MUST pair every `pushPose()` with `popPose()` to avoid corrupting Xaero's own UI rendering

Expected result: roads render on the Xaero world map in 26.2. Frame rate impact under 5% with 200 visible roads.

### 2.3 layer visibility control

- read `road_network` layer visibility from `LayerManager` before rendering
- expose a toggle in Wayfarer's config screen (`SettingsScreen`) — checked at map open time
- do not inject into Xaero's own UI components

Expected result: user can toggle the overlay on/off from Wayfarer settings. No Xaero UI conflicts.

---

## 3. backport to 26.1.1 and 1.20.1

### 3.1 26.1.1

26.1.1 likely uses the same `extractRenderState` API as 26.2. Place a copy of the mixin in `versions/26.1.1/src/` if any signature differs.

### 3.2 1.20.1

1.20.1 targets the old Xaero API: `render(GuiGraphics, int, int, float)`. Inject at `xaero.map.gui.GuiMap.render()` TAIL. The `GuiGraphics` object directly provides `pose()` and `bufferSource()`. The coordinate projection formula is identical; only the rendering context differs. Place this mixin in `versions/1.20.1/src/`.

### multi-version file layout

```
src/main/java/.../mixin/          → 1.20.1 base (render signature)
versions/26.2/src/.../mixin/      → 26.2 overlay (extractRenderState signature)
versions/26.1.1/src/.../mixin/    → 26.1.1 if different from 26.2, else empty
```

The preprocessor chain `1.20.1 → 26.2 → 26.1.1` means 26.2 files override the base, and 26.1.1 files override 26.2. Each version's mixin config declares the matching injection signature. Common rendering logic (viewport culling, line drawing, color mapping) stays in `src/main/` and is shared.

Expected result: all three versions build and render roads on their respective Xaero WorldMap versions.

---

## 4. web bridge — Xaero tiles as browser basemap

Replace the dark grid basemap in the Leaflet preview with actual terrain tiles from Xaero's cache.

- locate Xaero WorldMap's tile cache directory (typically `.minecraft/XaeroWorldMap/` under the dimension folder)
- tile coordinates in Xaero use a region-based scheme; the web bridge maps Leaflet's `{z}/{x}/{y}` requests to Xaero cache file paths
- add a new HTTP endpoint `/api/tiles/{z}/{x}/{y}.png` to `RoadPreviewServer` that reads and serves the cached PNG
- update the Leaflet SPA to use this endpoint as a `L.tileLayer` with proper attribution
- cache misses return a transparent tile; no 404 errors

Expected result: the browser map preview shows MC terrain under the road network overlay. Dark grid remains as fallback when Xaero cache is empty or unreadable.

---

## 5. performance and polish

- add Douglas-Peucker LOD: zoom level 9+ no simplification, 7-8 ε=2, 5-6 ε=5, 4 or below ε=10
- batch all road line vertices into a single `BufferBuilder` per frame
- skip rendering when `road_network` layer is hidden
- add debug logging behind `wayfarer.json` → `debug.enabled=true`: render frame time, road count, vertex count

Expected result: stable 60 fps with 200 roads visible. Debug logs available for troubleshooting.

---

## version coverage summary

| MC version | Xaero API | injection method | mixin location |
|---|---|---|---|
| 26.2 | new | `extractRenderState` TAIL | `versions/26.2/src/` |
| 26.1.1 | new (likely) | `extractRenderState` TAIL | `src/main/` or `versions/26.1.1/src/` |
| 1.20.1 | old | `render` TAIL | `src/main/` base |

Preprocessor chain: `1.20.1 base → 26.2 overlay → 26.1.1 overlay`. Version-specific mixins go in the appropriate overlay directory. Shared rendering code stays in the base.

---

## acceptance criteria

- open Xaero world map (M key) in any supported version → road network renders with correct colors per classification
- frame rate impact ≤ 5% measured via F3 debug screen over 30 seconds, with 200 roads visible
- `road_network` layer toggle in Wayfarer settings hides/shows the overlay
- Xaero not installed or version mismatch → no crash, logged warning, overlay disabled
- browser preview at `localhost:7891` shows MC terrain tiles under the road network (web bridge)
- all three versions (1.20.1 / 26.1.1 / 26.2) build and pass smoke test
*（内容由AI生成，仅供参考）*
