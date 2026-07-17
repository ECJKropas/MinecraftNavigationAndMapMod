---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_5862d48f81d911f180b3525400bff409
    ReservedCode1: j4EcNr+9ZDWirH5kR9tX0GC7Q6Ox22K8xtJY+WWDyQT9jr/zP5aumBsveFH2Dcg9HqZqsiRdFDUZr4Ki0e8rs+voSw14qk/PKYgEiNuR3hHopO62J0kxdCjKHnwe6HCyxErIGgAik6+tY3hCxGacEOx6uMf4d0rLGWv5tY7M6JBU2El557bpXLmVLXg=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_5862d48f81d911f180b3525400bff409
    ReservedCode2: j4EcNr+9ZDWirH5kR9tX0GC7Q6Ox22K8xtJY+WWDyQT9jr/zP5aumBsveFH2Dcg9HqZqsiRdFDUZr4Ki0e8rs+voSw14qk/PKYgEiNuR3hHopO62J0kxdCjKHnwe6HCyxErIGgAik6+tY3hCxGacEOx6uMf4d0rLGWv5tY7M6JBU2El557bpXLmVLXg=
---

# Xaero 模组多版本逆向分析报告

> 目标：为 Wayfarer P2 模块提供 Xaero World Map 的 Mixin 注入方案，覆盖 MC 1.20 / 1.21.x 双版本路径。
> 分析日期：2026-07-17

---

## 1. Xaero 版本清单与 Class 结构概览

### 1.1 被分析的 Jar 文件

| Jar 文件名 | XaeroWorldMap 版本 | 对应 MC 版本 | 大小 |
|---|---|---|---|
| `XaerosWorldMap_1.37.2_Fabric_1.20.jar` | 1.37.2 | 1.20 (Fabric) | 936 KB |
| `xaeroworldmap-fabric-26.1.2-1.41.3.jar` | 1.41.3 | 1.21.2/3 (Fabric) | 1,439 KB |
| `xaeroworldmap-fabric-26.1.2-1.44.2.jar` | 1.44.2 | 1.21.2/3 (Fabric) | 1,470 KB |
| `xaeroworldmap-fabric-26.2-1.44.2.jar` | 1.44.2 | 1.21.4 (Fabric) | 1,474 KB |

另外还有 4 个 XaeroMinimap jar（23.9.3 / 26.1.5 / 26.4.2），因本次分析聚焦 World Map 的 GuiMap 注入，Minimap 不在范围内。

### 1.2 关键 Class 路径

所有版本的 World Map 共享相同的包结构：

```
xaero/map/gui/GuiMap.class          ← 核心地图 GUI（注入目标）
xaero/map/gui/GuiMap$1.class ...    ← 匿名内部类（大量，v1.37.2 有 15+ 个）
xaero/map/gui/GuiMapSwitching.class ← 地图切换子 GUI
xaero/map/gui/ScreenBase.class       ← v1.37.2 父类（旧包）
xaero/map/MapProcessor.class         ← 地图数据处理器
xaero/map/graphics/MapRenderHelper.class
xaero/map/graphics/CustomRenderTypes.class
xaero/map/element/...               ← MapElement 渲染系统
xaero/map/WorldMap.class             ← 模组主入口
```

v1.41.3+ 的包重构：
```
xaero/lib/client/gui/ScreenBase.class  ← 新父类（xaero.lib 通用库）
xaero/lib/client/gui/widget/Tooltip.class
xaero/lib/common/config/...           ← 新配置系统
```

### 1.3 父类继承链变更

| 版本 | 父类 |
|---|---|
| 1.37.2 | `xaero.map.gui.ScreenBase` |
| 1.41.3 ~ 1.44.2 | `xaero.lib.client.gui.ScreenBase` |

这意味着 Xaero 在 1.41.x 期间将通用 GUI 基础设施提取到了独立的 `xaero.lib` 库中。

---

## 2. 各版本 GuiMap 关键反编译对比

### 2.1 构造函数

**所有四个版本**的构造函数签名**完全一致**：

```java
// 1.37.2 (obfuscated name)
public GuiMap(Screen, Screen, MapProcessor, Entity)

// 1.41.3 ~ 1.44.2 (deobfuscated name)
public GuiMap(
    net.minecraft.client.gui.screens.Screen,       // 上一个画面
    net.minecraft.client.gui.screens.Screen,       // 上一个画面的上一个画面
    xaero.map.MapProcessor,                        // 地图处理器
    net.minecraft.world.entity.Entity              // 玩家实体
)
```

构造函数内部逻辑概要（所有版本一致）：
1. 调用 `super(prevScreen, prevPrevScreen, Component.translatable("gui.xaero_world_map_screen"))`
2. 初始化 `screenScale`, `mouseDownPos*`, `mouseCheckPos*` 等鼠标追踪字段为默认值
3. 初始化 `cameraX = 0.0`, `cameraZ = 0.0`, `shouldResetCameraPos = true`
4. 存储 `player` 引用（第 4 个参数）
5. 创建 `leftMouseButton` / `rightMouseButton` (MapMouseButtonPress)
6. 创建 `mapSwitchingGui` (GuiMapSwitching)，传入 `MapProcessor`
7. 计算 `userScale = destScale * (openingAnimation ? 1.5 : 1.0)`
8. 存储 `this.mapProcessor = mapProcessor`（第 3 个参数）
9. **1.37.2**：通过 `WorldMap.settings` 获取配置；**1.41.3+**：通过 `WorldMap.INSTANCE.getConfigs().getClientConfigManager()` 获取配置

### 2.2 渲染方法签名对比（核心断裂）

这是本次逆向分析最重要发现——**render 方法在 1.41.3+ 被完全移除**：

| 版本 | `render` 方法 | `extractRenderState` | `extractBackground` | `renderPreDropdown` 参数类型 |
|---|---|---|---|---|
| **1.37.2** | ✅ `render(GuiGraphics, int, int, float)` | ❌ 不存在 | ❌ 不存在 | `GuiGraphics` |
| **1.41.3** | ❌ **不存在** | ✅ `extractRenderState(GuiGraphicsExtractor, int, int, float)` | ✅ `extractBackground(GuiGraphicsExtractor, int, int, float)` | `GuiGraphicsExtractor` |
| **1.44.2 (26.1.2)** | ❌ **不存在** | ✅ 同上 | ✅ 同上 | `GuiGraphicsExtractor` |
| **1.44.2 (26.2)** | ❌ **不存在** | ✅ 同上 | ✅ 同上 | `GuiGraphicsExtractor` |

**结论**：Minecraft 1.21.2+ 将 `Screen.render()` 拆分为 `extractRenderState()` + `extractBackground()` + `renderPreDropdown()` 三步渲染管线。Xaero 1.41.3+ 顺应了这一变更，彻底移除了传统的 `render` 方法。`GuiGraphicsExtractor` 是 MC 原版类（`net.minecraft.client.gui.GuiGraphicsExtractor`），用于从 `GuiGraphics` 中提取 PoseStack / Matrix4f 等渲染状态。

### 2.3 `renderPreDropdown` 方法差异

**1.37.2** 版本：
```java
protected void renderPreDropdown(GuiGraphics, int, int, float)
```
- 直接接收标准 `GuiGraphics`，从中获取 `PoseStack`
- 内部执行：`super.renderPreDropdown()` → Waypoint 菜单 → Players 菜单 → mapSwitchingGui

**1.41.3+** 版本：
```java
protected void renderPreDropdown(GuiGraphicsExtractor, int, int, float)
```
- 接收 `GuiGraphicsExtractor`，需要通过 extractor 获取渲染状态
- 内部调用流程相同：`super.renderPreDropdown()` → Waypoint 菜单渲染 → Players 菜单 → mapSwitchingGui

### 2.4 可 @Shadow 获取的关键字段

以下字段在**所有四个版本**中均存在且类型一致，可直接通过 Mixin `@Shadow` 获取：

| 字段名 | 类型 | 访问修饰符 | 含义 |
|---|---|---|---|
| `scale` | `double` | private | 当前地图缩放比例 |
| `cameraX` | `double` | private | 地图视口中心 X 坐标（世界坐标） |
| `cameraZ` | `double` | private | 地图视口中心 Z 坐标（世界坐标） |
| `mouseBlockPosX` | `int` | private | 鼠标指向的方块 X 坐标 |
| `mouseBlockPosZ` | `int` | private | 鼠标指向的方块 Z 坐标 |
| `mapProcessor` | `MapProcessor` | private | 地图数据处理器 |
| `screenScale` | `double` | private | 屏幕缩放比例 |

**仅 1.44.2+ 才有的新字段**：

| 字段名 | 类型 | 含义 |
|---|---|---|
| `mouseBlockPosY` | `int` | 鼠标指向的方块 Y 坐标 |
| `mouseBlockDim` | `ResourceKey<Level>` | 鼠标指向的维度 |
| `lastStartTime` | `long` | 地图打开时间戳 |
| `immediateRenderFBO` | `ImprovedFramebuffer` (static) | 即时渲染帧缓冲 |
| `applyZoomLimits()` | `void` (private) | 缩放限制方法 |
| `toggleHiddenUI()` | `void` (private) | **仅 26.2** - 隐藏 UI 切换 |

**1.44.2 (26.2) 新增静态字段**：
- `black4f` (`Vector4f`)：黑色向量颜色常量

### 2.5 可调用的公共 Getter 方法

| 方法 | 存在版本 | 返回类型 |
|---|---|---|
| `getMapProcessor()` | 所有版本 | `MapProcessor` |
| `getUserScale()` | 所有版本 | `double` |
| `getDimensionOnInit()` | 1.41.3+ | `MapDimension` |
| `getFutureDimension()` | 1.37.2 | `MapDimension` |
| `getRightClickOptions()` | 所有版本 | `ArrayList<RightClickOption>` |
| `shouldSkipWorldRender()` | 所有版本 | `boolean` |

### 2.6 `getScaleMultiplier` 微小差异

```java
// 1.37.2
private double getScaleMultiplier(int width) {
    return width > 1080 ? width / 1080.0 : 1.0;
}

// 1.41.3 ~ 1.44.2
private double getScaleMultiplier(int width) {
    return width > 1100 ? width / 1080.0 : 1.0;  // 阈值从 1080 改为 1100
}
```

---

## 3. GuiGraphicsExtractor 分析

### 3.1 定位

`GuiGraphicsExtractor` **不是** Xaero 自定义类，而是 Minecraft 1.21.2+ 引入的原版类：

```
net.minecraft.client.gui.GuiGraphicsExtractor
```

所有四个 Xaero jar 中均**不包含**该类——它是 MC 运行时提供的。Xaero 仅在方法签名中引用它。

### 3.2 用途推断

根据 `extractRenderState` 字节码分析：

1. `extractRenderState` 仍然通过 `Minecraft.getInstance()` 获取 `Minecraft` 实例来访问 `DeltaTracker` 和 `isPaused()`
2. 通过 `MapProcessor` 获取 `MapWorld`，计算 `dimDiv`（维度缩放因子）
3. 使用 `EntityUtil.getEntityX(player, partialTick)` 获取玩家位置
4. 管理 `zoomAnim`（缩放动画）和 `cameraDestinationAnim`（相机移动动画）
5. 更新 `cameraX` / `cameraZ` / `scale` 等字段

**关键推断**：`extractRenderState` 执行的是逻辑计算 + 状态提取，而 `renderPreDropdown` 执行的是实际的 OpenGL 绘制。`GuiGraphicsExtractor` 作为两者之间的桥梁，封装了从 `GuiGraphics` 中提取的渲染状态（PoseStack、Matrix4f、BufferSource 等）。

---

## 4. xaero-train-map Mixin 机制详解

### 4.1 项目概览

`xaero-train-map` 是 Create Mod 团队开发的子模组，使用 NeoForge 1.21，依赖 `xaeros-world-map:1.39.9_NeoForge_1.21`。它通过 Mixin 在 Xaero 世界地图上叠加铁路线路信息。这是已知**唯一**成功实现的 Xaero GuiMap Mixin 注入参考项目。

### 4.2 Mixin 配置文件

```json
// xaerotrainmap.mixins.json
{
  "required": true,
  "minVersion": "0.8",
  "package": "net.foxy.xaerotrainmap.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": ["GuiMapMixin", "StationScreenMixin"],
  "injectors": { "defaultRequire": 1 },
  "mixins": []
}
```

两个 Mixin 类：
- `GuiMapMixin`：注入 Xaero 地图渲染管线
- `StationScreenMixin`：注入 Create 车站画面（修改 `mapModsPresent` 返回 `true`）

### 4.3 GuiMapMixin 详细分析

```java
@Mixin(GuiMap.class)
public class GuiMapMixin extends Screen {
    @Shadow private double scale;
    @Shadow private double cameraZ;
    @Shadow private double cameraX;
    @Shadow private int mouseBlockPosX;
    @Shadow private int mouseBlockPosZ;

    protected GuiMapMixin(Component title) {
        super(title);  // 由于 Mixin Screen，必须提供构造器
    }

    @Inject(
        method = "render",
        at = @At("TAIL")   // 在地图主体渲染完成后注入
    )
    public void renderTrain(GuiGraphics guiGraphics, int scaledMouseX,
                            int scaledMouseY, float partialTicks, CallbackInfo ci) {
        TrainMap.onRender(this, guiGraphics, scaledMouseX, scaledMouseY,
                partialTicks, this.scale, this.cameraX, this.cameraZ,
                mouseBlockPosX, mouseBlockPosZ);
    }
}
```

**关键设计要点**：
1. **`@Mixin(GuiMap.class)`**：直接 Mixin Xaero 的 `GuiMap` 类
2. **`extends Screen`**：Mixin 必须继承与目标类兼容的父类。Xaero 的 `ScreenBase` 继承自 MC 的 `Screen`
3. **`@Shadow`**：将 Xaero 的 private 字段映射到 Mixin 中可读
4. **`@At("TAIL")`**：在目标方法末尾注入，确保地图主体渲染完成后再叠加自定义内容
5. **注入点 `method = "render"`**：**这是核心限制——此签名仅适用于 Xaero 1.37.2（MC 1.20）**

### 4.4 坐标投影/转换公式

从 `TrainMap.onRender()` 提取的完整坐标变换公式：

```java
public static void onRender(Screen screen, GuiGraphics graphics, int mX, int mY,
                            float pt, double mapScale, double x, double z,
                            int mPosX, int mPosZ) {

    Minecraft mc = Minecraft.getInstance();
    Window window = mc.getWindow();

    // Step 1: 计算 GUI 缩放比
    double guiScale = (double) window.getScreenWidth() / window.getGuiScaledWidth();

    // Step 2: 将地图内部 scale 转换为实际像素缩放
    double scale = mapScale / guiScale;

    PoseStack pose = graphics.pose();
    pose.pushPose();

    // Step 3: 将原点移到屏幕中心
    pose.translate(screen.width / 2.0f, screen.height / 2.0f, 0);

    // Step 4: 按世界坐标比例缩放（Xaero 地图用的是世界坐标，1 像素 = 1 方块）
    pose.scale((float) scale, (float) scale, 1);

    // Step 5: 平移到相机位置（cameraX, cameraZ 是世界坐标）
    pose.translate(-x, -z, 0);

    // 此时 PoseStack 处于"世界坐标系"，直接在世界坐标位置绘制即可

    // Step 6: 计算当前视口在世界坐标中的可见范围
    Rect2i bounds = new Rect2i(
        Mth.floor(-screen.width / 2.0f / scale + x),
        Mth.floor(-screen.height / 2.0f / scale + z),
        Mth.floor(screen.width / scale),
        Mth.floor(screen.height / scale)
    );

    // ... 渲染叠加内容 ...

    pose.popPose();
}
```

**公式总结**：

| 步骤 | 操作 | 数学含义 |
|---|---|---|
| 1 | `guiScale = screenWidth / guiScaledWidth` | 物理像素 → GUI 像素的缩放因子 |
| 2 | `renderScale = mapScale / guiScale` | Xaero 内部 scale → 渲染 scale |
| 3 | `translate(width/2, height/2)` | 原点移到屏幕中心 |
| 4 | `scale(renderScale, renderScale)` | 世界坐标 (1block=1px) → 屏幕像素 |
| 5 | `translate(-cameraX, -cameraZ)` | 视口对准相机位置 |
| 6 | 视口范围 = `[-width/2/scale + cameraX, width/scale] × [-height/2/scale + cameraZ, height/scale]` | 世界坐标裁剪 |

### 4.5 渲染上下文获取

在旧版本中，`GuiGraphics` 直接提供 `PoseStack` 和渲染能力。`onRender` 通过 `graphics.pose()` 获取 `PoseStack`，执行 push/pop/translate/scale 变换。所有叠加绘制使用标准的 `GuiGraphics` 方法（如 `RemovedGuiUtils.drawHoveringText`）。

### 4.6 事件驱动架构

```java
// XaeroTrainMapEvents.java
@EventBusSubscriber(value = Dist.CLIENT, modid = XaeroTrainMap.MODID)
public class XaeroTrainMapEvents {
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        TrainMap.tick();  // 每 tick 检查是否需要请求铁路数据
    }
    @SubscribeEvent
    public static void mouseClick(InputEvent.MouseButton.Pre event) {
        TrainMap.mouseClick(event);  // 处理切换按钮点击
    }
}
```

`TrainMap.tick()` 检测当前画面是否为 `GuiMap`，若是则通过 `TrainMapSyncClient.requestData()` 向服务端请求铁路数据。

---

## 5. xaero-train-map 的方案局限性

### 5.1 版本绑定问题

xaero-train-map 依赖 `xaeros-world-map:1.39.9_NeoForge_1.21`，其 Mixin 注入目标是 `render(GuiGraphics, int, int, float)` 方法。该签名在 Xaero 1.41.3（对应 MC 1.21.2+）中**已被移除**，因此：

- **MC 1.20 / Xaero 1.37.2**：可以直接参考 Mixin 模式，注入 `render` 方法
- **MC 1.21.2+ / Xaero 1.41.3+**：必须改为注入 `extractRenderState` 或 `renderPreDropdown`

### 5.2 注入点迁移方案

对于 1.21.2+ 版本，存在两个可行注入点：

**方案 A：注入 `renderPreDropdown`**
```
优点：参数列表有 GuiGraphicsExtractor，可通过 extractor 获取渲染状态
     渲染时机在 Xaero 地图主体之后，不会被子元素遮挡
缺点：需要研究 GuiGraphicsExtractor 的 API 来获取 PoseStack/Matrix4f
     签名变为 (GuiGraphicsExtractor, int, int, float)
```

**方案 B：注入 `extractRenderState`**
```
优点：在渲染管线最早期，可以窃取 Xaero 自己的坐标计算逻辑
缺点：此时尚未开始绘制，需要通过其他方式注入实际的渲染
     不适合叠加绘制类需求
```

**推荐**：对于叠加绘制类需求（如 Wayfarer 的导航线、标记点），应注入 `renderPreDropdown`，因为：
- `extractRenderState` 只做状态计算，不是渲染入口
- `renderPreDropdown` 是实际绘制入口，Xaero 地图主体已渲染完毕
- `extractBackground` 负责背景，叠加元素不适合注入此处

---

## 6. 针对 Wayfarer P2 的可行注入方案建议

### 6.1 方案 A：MC 1.20 路径（兼容 Xaero 1.37.2）

**Mixin 配置**：
```json
// wayfarer-xaero-compat.mixins.json (1.20)
{
  "client": ["GuiMapMixin_1_20"]
}
```

**Mixin 实现**：
```java
@Mixin(GuiMap.class)
public abstract class GuiMapMixin_1_20 extends Screen {
    @Shadow private double scale;
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private int mouseBlockPosX;
    @Shadow private int mouseBlockPosZ;
    @Shadow private MapProcessor mapProcessor;

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphics guiGraphics, int mouseX, int mouseY,
                          float partialTick, CallbackInfo ci) {
        WayfarerOverlay.render(this, guiGraphics, mouseX, mouseY, partialTick,
            scale, cameraX, cameraZ, mouseBlockPosX, mouseBlockPosZ, mapProcessor);
    }
}
```

**坐标变换**：直接复用 xaero-train-map 的变换公式（见第 4.4 节）。

### 6.2 方案 B：MC 1.21.2+ 路径（兼容 Xaero 1.41.3 ~ 1.44.2）

**Mixin 配置**：
```json
// wayfarer-xaero-compat.mixins.json (1.21.2+)
{
  "client": ["GuiMapMixin_1_21"]
}
```

**Mixin 实现**：
```java
@Mixin(GuiMap.class)
public abstract class GuiMapMixin_1_21 extends Screen {
    @Shadow private double scale;
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private int mouseBlockPosX;
    @Shadow private int mouseBlockPosZ;
    @Shadow private MapProcessor mapProcessor;

    @Inject(method = "renderPreDropdown", at = @At("TAIL"))
    private void onRenderPreDropdown(GuiGraphicsExtractor extractor,
                                      int mouseX, int mouseY,
                                      float partialTick, CallbackInfo ci) {
        // GuiGraphicsExtractor 提供 extract() 方法获取 GuiGraphics？
        // 或者直接通过 extractor 的方法获取 PoseStack
        WayfarerOverlay.render(this, extractor, mouseX, mouseY, partialTick,
            scale, cameraX, cameraZ, mouseBlockPosX, mouseBlockPosZ, mapProcessor);
    }
}
```

**⚠️ 待解决的关键问题**：
1. `GuiGraphicsExtractor` 的公开 API 尚未确认（需要反编译 MC 1.21.2+ 原版 jar）
2. 如何从 `GuiGraphicsExtractor` 获取 `PoseStack` / `Matrix4f` 以执行坐标变换
3. MC 1.21.2+ 的渲染 API 已从 `RenderSystem` + `GL11` 迁移到新的 `BufferSource`/`MultiBufferSource` 体系

### 6.3 双版本兼容策略

由于 `render` 和 `renderPreDropdown` 签名完全不同，建议使用条件 Mixin：

```groovy
// build.gradle - 按 MC 版本选择不同 Mixin 配置
sourceSets {
    main {
        java {
            if (mcVersion.startsWith("1.20")) {
                srcDir 'src/main/java_1_20'
            } else {
                srcDir 'src/main/java_1_21'
            }
        }
    }
}
```

或者使用 `@Mixin` 的 `require` 参数来按条件加载：
```java
// 仅在 MC >= 1.21.2 时加载
@Mixin(value = GuiMap.class, require = 1)  // require=1 表示必须有目标方法
```

但更可靠的方法是维护两套独立的 Mixin JSON 配置文件，在构建时根据 MC 版本选择。

### 6.4 坐标投影公式适用性验证

坐标变换的核心公式 `(translate → scale → translate)` 不依赖具体的渲染 API（PoseStack vs GuiGraphicsExtractor），只要能从渲染上下文中获取矩阵操作能力即可复用。需要验证 `GuiGraphicsExtractor` 是否提供等价的 `pushPose()` / `translate()` / `scale()` 方法。

### 6.5 额外获取的信息汇总

通过 `MapProcessor` 可以进一步获取：
```java
MapWorld world = mapProcessor.getMapWorld();          // 所有版本
MapDimension dim = world.getCurrentDimension();       // 1.41.3+ (1.37.2: getFutureDimension())
Registry<DimensionType> typeReg = mapProcessor.getWorldDimensionTypeRegistry(); // 1.41.3+
double dimDiv = dim.calculateDimDiv(registry, dimensionType); // 维度缩放因子
```

这些信息可用于正确处理跨维度导航和坐标缩放。

---

## 7. 总结与后续行动项

### 7.1 已确认的事实

1. **字段稳定性**：`scale`, `cameraX`, `cameraZ`, `mouseBlockPosX/Z`, `mapProcessor` 在所有版本中稳定存在，可直接 `@Shadow`
2. **API 断裂线**：`render(GuiGraphics, ...)` → `extractRenderState(GuiGraphicsExtractor, ...)` + `renderPreDropdown(GuiGraphicsExtractor, ...)` 的变更发生在 Xaero 1.41.x（对应 MC 1.21.2+）
3. **坐标公式可复用**：xaero-train-map 的坐标投影变换公式基于数学原理而非 API 特性，双版本均可使用
4. **`GuiGraphicsExtractor` 是 MC 原版类**：不在 Xaero jar 中，需要反编译 MC 原版来确定其 API

### 7.2 待完成事项（P2 实施前）

| 优先级 | 事项 | 说明 |
|---|---|---|
| P0 | 反编译 MC 1.21.2+ 的 `GuiGraphicsExtractor` | 确认其公开 API，特别是获取 PoseStack/Pose 的方法 |
| P0 | 编写并测试 1.20 Mixin 原型 | 在 MC 1.20 + Xaero 1.37.2 环境验证注入和渲染 |
| P1 | 编写并测试 1.21.2+ Mixin 原型 | 在 MC 1.21.2+ + Xaero 1.44.2 环境验证 |
| P1 | 验证 `renderPreDropdown` 注入点是否被子元素遮挡 | 确认叠加元素（如 Waypoint 菜单）不会覆盖自定义内容 |
| P2 | 测试 `GuiMapSwitching` 切换时的注入稳定性 | 在地图维度切换时验证 hook 不会被跳过 |

---

*报告生成于 2026-07-17。所有 jar 文件逆向分析通过 `javap -c -p` 完成，未涉及字节码修改。*
*（内容由AI生成，仅供参考）*
