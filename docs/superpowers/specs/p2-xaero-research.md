---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_b3e3178281bf11f18a64525400826444
    ReservedCode1: BGKP6grIZ6c2R//Ex32VC6jdaIcD0V+Jfi4CVtusuWLL6SQhqRT1qqmlhLV2NFS93ou6KCCb9z5+seElpAq5K8i2qYxWKygwqnW2MTyBtF8DdSMi3bFv6OQ7sBGOVFKRqimrRHqVoOyfynHl6ZCzTj9wbQT2rNpFGL7GFAlTIx/iIxrY7mDhpjmrhiU=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_b3e3178281bf11f18a64525400826444
    ReservedCode2: BGKP6grIZ6c2R//Ex32VC6jdaIcD0V+Jfi4CVtusuWLL6SQhqRT1qqmlhLV2NFS93ou6KCCb9z5+seElpAq5K8i2qYxWKygwqnW2MTyBtF8DdSMi3bFv6OQ7sBGOVFKRqimrRHqVoOyfynHl6ZCzTj9wbQT2rNpFGL7GFAlTIx/iIxrY7mDhpjmrhiU=
---

# P2 第一阶段研究结论：Xaero World Map 混合注入逆向分析

> 日期: 2026-07-17
> 状态: 研究完成

---

## 1. 参考项目 xaero-train-map 分析

### 1.1 项目概览

xaero-train-map 是一个 NeoForge 模组 (For Minecraft 1.20.x)，在 Xaero 世界地图上渲染 Create 模组的铁路网络。核心技术路线：**Mixin 注入 GuiMap.render() → 用 PoseStack 做坐标投影 → 委托 TrainMapManager 渲染**。

### 1.2 Mixin 注入点（2个）

| Mixin 类 | 目标类 | 注入方法 | 注入位置 | 说明 |
|---|---|---|---|---|
| `GuiMapMixin` | `xaero.map.gui.GuiMap` | `render` | `@At("TAIL")` | 在地图渲染完成后叠加铁路线 |
| `StationScreenMixin` | `com.simibubi.create.content.trains.station.StationScreen` | `mapModsPresent` | `@At("HEAD")`, cancellable | 强制返回 true，启用站台地图按钮 |

### 1.3 GuiMapMixin 核心结构

```java
@Mixin(GuiMap.class)
public class GuiMapMixin extends Screen {
    @Shadow private double scale;       // Xaero 地图缩放级别
    @Shadow private double cameraZ;      // 世界坐标 - 相机 Z
    @Shadow private double cameraX;      // 世界坐标 - 相机 X
    @Shadow private int mouseBlockPosX;  // 鼠标指向的方块 X
    @Shadow private int mouseBlockPosZ;  // 鼠标指向的方块 Z

    @Inject(method = "render", at = @At("TAIL"))
    public void renderTrain(GuiGraphics guiGraphics, int scaledMouseX,
                            int scaledMouseY, float partialTicks, CallbackInfo ci) {
        TrainMap.onRender(this, guiGraphics, scaledMouseX, scaledMouseY,
            partialTicks, this.scale, this.cameraX, this.cameraZ,
            mouseBlockPosX, mouseBlockPosZ);
    }
}
```

**注入签名（旧版 Xaero API，MC 1.20.x）：** `render(GuiGraphics, int, int, float)`

### 1.4 坐标投影公式（TrainMap.onRender）

```java
// Step 1: 计算 GUI 缩放因子（物理分辨率 vs GUI 坐标）
double guiScale = (double)window.getScreenWidth() / window.getGuiScaledWidth();

// Step 2: 地图缩放 = Xaero内部缩放 / GUI缩放
double scale = mapScale / guiScale;

// Step 3: 坐标变换序列
PoseStack pose = graphics.pose();
pose.pushPose();
pose.translate(screen.width / 2.0f, screen.height / 2.0f, 0);  // 中心锚点
pose.scale((float)scale, (float)scale, 1);                       // 缩放
pose.translate(-cameraX, -cameraZ, 0);                           // 平移到世界坐标
// ... 此时坐标系统与 Xaero 地图完全对齐

// Step 4: 计算可视矩形（世界坐标）
Rect2i bounds = new Rect2i(
    Mth.floor(-screen.width / 2.0f / scale + cameraX),
    Mth.floor(-screen.height / 2.0f / scale + cameraZ),
    Mth.floor(screen.width / scale),
    Mth.floor(screen.height / scale)
);
```

**公式总结：**
- 世界坐标 `(wx, wz)` → 屏幕坐标: `screenPos = (wx - cameraX) * scale + screenCenter`
- 屏幕坐标 → 世界坐标: `worldPos = (screenPos - screenCenter) / scale + cameraX`

### 1.5 渲染管线

1. `TrainMap.tick()` 每帧检查是否在 GuiMap 界面，是则通过 `TrainMapSyncClient.requestData()` 请求铁路数据
2. `TrainMap.onRender()` 使用 PoseStack 对齐坐标系后，调用 `TrainMapManager.renderAndPick()` 渲染铁路并返回 tooltip
3. 渲染使用标准 MC PoseStack + GuiGraphics API（1.20.x 传统风格）

---

## 2. Xaero 世界地图 JAR 逆向分析

### 2.1 JAR 信息

| 属性 | 值 |
|---|---|
| 文件名 | `xaeroworldmap-fabric-26.1.2-1.44.2.jar` |
| Xaero WM 版本 | 26.1.2 |
| 目标 MC 版本 | 1.44.2 (MC 快照/新版) |
| 包名 | `xaero.map` |

### 2.2 GuiMap 类分析（新版 API）

**类层次：** `xaero.map.gui.GuiMap extends xaero.lib.client.gui.ScreenBase implements IRightClickableElement`

**关键字段**（可通过 `@Shadow` 访问）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `scale` | `double` | 地图总缩放级别 |
| `userScale` | `double` | 用户手动缩放级别 |
| `cameraX` | `double` | 相机世界坐标 X |
| `cameraZ` | `double` | 相机世界坐标 Z |
| `mouseBlockPosX` | `int` | 鼠标指向方块 X |
| `mouseBlockPosY` | `int` | 鼠标指向方块 Y |
| `mouseBlockPosZ` | `int` | 鼠标指向方块 Z |
| `screenScale` | `double` | 屏幕缩放因子 |

### 2.3 **严重差异：新版渲染 API 已变更**

> **这是最重要的发现！** Xaero World Map 26.1.2 的渲染入口与 xaero-train-map 完全不同。

| 特性 | 旧版 (1.20.x / xaero-train-map) | 新版 (26.1.2 / 1.44.2) |
|---|---|---|
| 渲染方法 | `render(GuiGraphics, int, int, float)` | `extractRenderState(GuiGraphicsExtractor, int, int, float)` |
| 图形上下文 | `GuiGraphics` | `GuiGraphicsExtractor`（Xaero 私有抽象） |
| 叠加层渲染 | `render` 方法 | `renderPreDropdown(GuiGraphicsExtractor, int, int, float)` |
| 可用渲染方法 | 标准 MC API | `drawDotOnMap`, `drawArrowOnMap`, `drawObjectOnMap` |

**新版 GuiMap 渲染相关方法签名：**

```java
// 主渲染管线（新版核心方法）
public void extractRenderState(GuiGraphicsExtractor, int, int, float);
protected void renderPreDropdown(GuiGraphicsExtractor, int, int, float);
public void extractBackground(GuiGraphicsExtractor, int, int, float);

// 地图上绘制元素（公共 API，可在 Mixin 中调用）
public void drawDotOnMap(PoseStack, VertexConsumer, double worldX, double worldZ, float, double);
public void drawArrowOnMap(PoseStack, VertexConsumer, double worldX, double worldZ, float, double);
public void drawObjectOnMap(PoseStack, VertexConsumer, double worldX, double worldZ,
    float, double, float textureScale, float, int u, int v, int width, int height);

// 坐标/缩放工具方法
private double getCurrentMapCoordinateScale();  // 当前维度坐标缩放
public double getUserScale();                    // 用户缩放值
```

### 2.4 Xaero 元素渲染系统

Xaero 内部使用了一套抽象元素渲染体系，Waypoint 渲染就是基于这个系统实现的：

```java
// 核心抽象类
public abstract class ElementRenderer<E, C, R> {
    public abstract void preRender(ElementRenderInfo, BufferSource, MultiTextureRenderTypeRendererProvider, boolean);
    public abstract void postRender(ElementRenderInfo, BufferSource, MultiTextureRenderTypeRendererProvider, boolean);
    public abstract boolean renderElement(E, boolean, double dimScale, float partialTick,
        double worldX, double worldZ, ElementRenderInfo, MapElementGraphics,
        BufferSource, MultiTextureRenderTypeRendererProvider);
    public abstract boolean shouldRender(ElementRenderLocation, boolean);
}

// 渲染位置枚举
public static final ElementRenderLocation WORLD_MAP;       // 世界地图主视图
public static final ElementRenderLocation WORLD_MAP_MENU;  // 世界地图菜单层
public static final ElementRenderLocation IN_WORLD;        // 游戏世界中
public static final ElementRenderLocation IN_MINIMAP;      // 小地图中

// 渲染上下文信息
public class ElementRenderInfo {
    public final ElementRenderLocation location;
    public final Entity renderEntity;
    public final Vec3 renderEntityPos;   // 实体世界坐标
    public final Vec3 renderPos;         // 渲染参考点
    public final double scale;           // 当前缩放
    public final double screenSizeBasedScale;
    public final float partialTicks;
    public final RenderTarget framebuffer;  // 渲染目标FBO
}
```

**WaypointRenderer 就是通过扩展 ElementRenderer 实现的** — 这意味着如果要注册自定义地图元素，理论上可以走 Xaero 的 ElementRenderProvider 体系，但 Mixin 注入更简单直接。

### 2.5 渲染纹理方法

```java
// Xaero 内部静态渲染工具
public static void renderTexturedModalRectWithLighting3(Matrix4f, float, float, float, float,
    GpuTextureView, boolean, MultiTextureRenderTypeRenderer);
public static void renderTexturedModalSubRectWithLighting(Matrix4f, float, float, float, float,
    float, float, float, float, GpuTextureView, boolean, MultiTextureRenderTypeRenderer);
```

---

## 3. Wayfarer 现有代码分析

### 3.1 多版本架构

| 目录 | 用途 |
|---|---|
| `src/main/` | 通用源码（所有版本共享） |
| `versions/26.2/` | MC 1.44.2 版本特定覆盖 |
| `versions/26.1.1/` | MC 1.21.x 覆盖 |
| `versions/1.20.1/` | MC 1.20.1 覆盖（旧版编译产物） |
| `gradle.properties` | `modId=wayfarer`, `modVersion=0.2.0` |

**无任何现有 Mixin 配置** — Wayfarer 目前完全没有任何 `*.mixins.json` 或 Mixin 类。P2 的 Xaero 集成是**从零开始**的 Mixin 开发。

### 3.2 道路数据模型

```
RoadDataStore
└── RoadBook
    └── List<RoadPath>
        ├── id: String
        ├── name: String
        ├── width: double
        ├── classification: String (G/S/X/Y/C)
        ├── number: String
        ├── points: List<RoadPoint>
        │   ├── x: double  (世界坐标)
        │   ├── y: double
        │   ├── z: double
        │   └── tick: long
        ├── intersections: List<RoadIntersection>
        │   ├── position: IntersectionPosition (x,y,z)
        │   ├── type: String
        │   └── roadId: String
        ├── segments: List<RoadSegment>
        │   ├── points: List<RoadPoint>
        │   └── startIntersection / endIntersection
        └── style: RoadStyle
            ├── color: String
            ├── lineWidth: Double
            └── dashPattern: String
```

**数据存储位置：** `{run_dir}/config/wayfarer/roads.json` (有上下文隔离机制 RoadStorageContext，支持单人多服的存档绑定)

### 3.3 渲染相关现有能力

- `Geometry.closestPointOnSegment()` — 点到线段投影（3D）
- `RoadDataStore.toGeoJson()` — 已有坐标导出能力
- `RoadPreviewServer` — HTTP 地图预览服务器（用 MapLayer 体系渲染）

**Wayfarer 缺乏的：**在 Xaero 世界地图 GUI 内直接渲染道路线的能力 — 这正是 P2 要实现的。

---

## 4. 可行方案

### 4.1 推荐注入点

**主注入点：** `xaero.map.gui.GuiMap.extractRenderState(GuiGraphicsExtractor, int, int, float)` → `@Inject(at = @At("TAIL"))`

这是新版 Xaero 的主渲染回调。在 `extractRenderState` 的 TAIL 注入可以确保：
- 从 `GuiGraphicsExtractor` 对象中提取 `PoseStack` 进行自定义渲染
- 此时所有地图瓦片已经渲染完毕，叠加道路线不会被遮挡
- 通过 `@Shadow` 获取 `scale`、`cameraX`、`cameraZ` 等参数

**备用注入点：** `renderPreDropdown(GuiGraphicsExtractor, int, int, float)` → `@Inject(at = @At("TAIL"))`

如果 extractRenderState 不方便注入，这是地图界面元素的渲染入口。

### 4.2 坐标转换方案

沿用 xaero-train-map 的已验证公式，但需适配 `GuiGraphicsExtractor` API：

```
// 从 GuiGraphicsExtractor 获取 PoseStack
PoseStack pose = extractor.pose();  // 需验证此 API 是否存在

// 获取窗口缩放
Window window = mc.getWindow();
double guiScale = (double)window.getScreenWidth() / window.getGuiScaledWidth();
double effectiveScale = this.scale / guiScale;

// 投影变换
pose.pushPose();
pose.translate(screenWidth / 2.0, screenHeight / 2.0, 0);
pose.scale(effectiveScale, effectiveScale, 1);
pose.translate(-this.cameraX, -this.cameraZ, 0);

// 此时渲染坐标 = 世界坐标，可直接用 RoadPoint.x / RoadPoint.z
```

### 4.3 渲染管线设计

```
Mixin注入 GuiMap.extractRenderState (TAIL)
    │
    ├── 获取 Roads from RoadDataStore
    │
    ├── 计算可视范围 (cameraX ± halfWidth/effectiveScale, cameraZ ± halfHeight/effectiveScale)
    │
    ├── 筛选可见道路 (bounding box intersection)
    │
    ├── 对每条 RoadPath:
    │   ├── 构建 VertexConsumer (Tessellator + RenderType.lines())
    │   ├── 遍历 points 绘制线段
    │   │   └── vertex(wx, 0, wz)  // Y=0 因为地图是俯视图
    │   └── 根据 classification 选择颜色
    │
    ├── 渲染交叉路口标记 (RoadIntersection)
    │   └── drawDotOnMap() 或自定义小方块
    │
    └── popPose()
```

### 4.4 颜色映射 (RoadStyle → ARGB)

| Classification | 颜色 | ARGB |
|---|---|---|
| G (国道) | 红色 | `0xFFFF0000` |
| S (省道) | 橙色 | `0xFFFF8800` |
| X (县道) | 黄色 | `0xFFFFFF00` |
| Y (乡道) | 绿色 | `0xFF00AA00` |
| C (村道/默认) | 灰色 | `0xFFAAAAAA` |
| 自定义 style.color | 解析 hex | 按需 |

### 4.5 版本兼容策略

需要创建 Mixin 覆盖的版本层（如果存在 API 差异）：

| MC 版本 | Xaero WM 版本 | render 注入方法 | Mixin 位置 |
|---|---|---|---|
| 1.44.2 | 26.1.2 | `extractRenderState` | `versions/26.2/` |
| 1.21.x | 待确认 | 可能仍是 `extractRenderState` | `versions/26.1.1/` |
| 1.20.1 | 旧版 | `render(GuiGraphics, ...)` | `versions/1.20.1/` |

---

## 5. 风险与注意事项

### 5.1 关键风险

| 风险 | 等级 | 说明 |
|---|---|---|
| **GuiGraphicsExtractor 不透明** | 🔴 高 | 新版 Xaero 的 `GuiGraphicsExtractor` 内部 API 未公开，需要运行时反射确认如何获取 `PoseStack`。备选方案是直接使用 Minecraft 的 `Minecraft.getInstance().renderBuffers()` 获取 BufferSource。 |
| **API 签名变更** | 🟡 中 | Mixin 必须精确匹配方法签名（包括参数名）。不同 Xaero 版本间 `extractRenderState` 的参数可能变化。 |
| **Mixin 配置生成** | 🟡 中 | Wayfarer 当前没有任何 Mixin 配置，需要创建 `wayfarer.mixins.json` 并注册到 `fabric.mod.json`。 |
| **渲染性能** | 🟢 低 | 道路线渲染简单（线段 + 颜色），batch 到单个 BufferBuilder 性能可接受。 |
| **PoseStack 状态泄漏** | 🟡 中 | `pushPose/popPose` 必须严格配对，否则后续 Xaero UI 渲染会错位。 |

### 5.2 需要进一步调查的问题

1. **GuiGraphicsExtractor API 探索：** 需要通过运行时测试或反编译确认：
   - 是否有 `pose()` 方法返回 `PoseStack`？
   - 是否有 `bufferSource()` 返回 `MultiBufferSource`？
   - 是否支持直接使用 Minecraft 标准 `Tessellator` + `RenderType.lines()` 渲染？

2. **GuiMap 继承链：** `ScreenBase` 的具体实现位置（包路径 `xaero.lib.client.gui` 未在 JAR 中直接找到，可能被混淆或打包在其他 JAR 中）。

3. **多版本差异确认：** 1.20.1 版本的 Xaero WM 是否仍然使用旧的 `render(GuiGraphics, int, int, float)` 签名。

### 5.3 建议开发计划

1. **先做 26.2 版本（最新版）** — 因为这是 Wayfarer 当前主要维护的版本
2. **创建 Mixin 配置文件** — `wayfarer.mixins.json` + fabric.mod.json 注册
3. **实现 GuiMapMixin** — 注入 `extractRenderState` TAIL，先用简单矩形测试渲染
4. **接入 RoadDataStore** — 读取道路数据并绘制线段
5. **向后移植到 26.1.1 和 1.20.1** — 分别处理 API 差异
*（内容由AI生成，仅供参考）*
