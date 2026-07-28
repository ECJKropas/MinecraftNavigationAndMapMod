---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_27a2e1bd88e611f18766525400f8a581
    ReservedCode1: WporhtFuSoWYjHqx+prSOSL/JHQq4e0fafoPSEPTiZ58zxCFsFfm4nIdAhxIcNRJwIykO107IreiBYgMtsWmxxdLWXZZ5apeZc6589bqBxcXERmzwDE9fTafF1VFv13K0pnDJID4SmxEgAwbJOFXwYxQ9FElYfDbcNWirdxjnspI+D/j4yxe8s54tXk=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_27a2e1bd88e611f18766525400f8a581
    ReservedCode2: WporhtFuSoWYjHqx+prSOSL/JHQq4e0fafoPSEPTiZ58zxCFsFfm4nIdAhxIcNRJwIykO107IreiBYgMtsWmxxdLWXZZ5apeZc6589bqBxcXERmzwDE9fTafF1VFv13K0pnDJID4SmxEgAwbJOFXwYxQ9FElYfDbcNWirdxjnspI+D/j4yxe8s54tXk=
---



# 路网节点指示渲染功能计划

> 日期：2026-07-26 | 状态：草稿

## 目标

在路网录制和编辑过程中，提供一种可开关的视觉指示模式，在已加载区块内的节点位置渲染标识物，帮助玩家精确对齐和观察节点布局。

**指示物组合**：每个节点方块位置上方渲染一个**向上的末地烛**（实体模型），再在末地烛之上渲染一个**半透明光柱**（纯几何体），兼顾近距离辨识度和远距离可视性。

## 设计决策

| 决策 | 理由 |
|---|---|
| 末地烛 + 光柱双层组合 | 末地烛提供近距精确位置参考，光柱提供远距可视性（litematica OverlayRenderer 经验） |
| 光柱用纯几何体（GL_QUADS）而非方块模型 | 代码简单，无 BakedModel 注册负担，性能极低；且末地烛模型无竖高变体 |
| 仅渲染已加载区块内的节点 | 避免跨维度/未加载区块位置的无效渲染调用 |
| 独立渲染开关 + 快捷键 | 类似 litematica `INVERT_SCHEMATIC_RENDER_STATE`，录制/编辑时按需开启 |
| 颜色可区分节点类型 | 普通节点/路口节点/分段端点使用不同颜色光柱，提升信息密度 |

## 坐标处理与取整

这是实现的关键细节。路网节点的坐标是 `double`（世界坐标连续值），而渲染指示物必须对齐到整数方块坐标。

### 取整策略

```
路网节点坐标 (x, y, z)  →  BlockPos(floor(x), floor(y), floor(z))
```

| 场景 | 处理方式 |
|---|---|
| 节点恰好落在方块边界（如 x=100.0） | `floor(100.0) = 100`，正确对齐 |
| 节点在方块内部（如 x=100.3） | `floor(100.3) = 100`，对齐到所在方块底面 |
| 两个不同节点落入同一方块 | 只渲染一个指示物，不做叠加（性能 + 视觉清晰度） |
| 节点 Y 坐标在方块上半部（如 y=64.8） | 仍取 `floor(64.8) = 64`，末地烛从该方块顶面（y=65）开始渲染 |

### 去重

同一 `BlockPos` 只需渲染一次指示物。用 `HashSet<BlockPos>` 或 `HashMap<BlockPos, NodeType>` 去重，后者还能保留"该位置优先级最高的节点类型"用于颜色选择。

## 文件规划

```
新增:
src/main/java/com/ecjkim/wayfarer/client/render/NodeIndicatorRenderer.java   # 主渲染逻辑

修改:
src/main/java/com/ecjkim/wayfarer/client/config/WayfarerConfig.java          # 开关 + 快捷键 + 颜色配置
src/main/resources/assets/wayfarer/lang/en_us.json                             # 配置项翻译
src/main/resources/assets/wayfarer/lang/zh_cn.json
```

## 渲染实现细节

### 末地烛

- 使用原版 `Blocks.END_ROD` + `FACING=UP` 的 `IBlockState`
- 借助 `Minecraft.getBlockRendererDispatcher().getModelForState(END_ROD_UP)` 获取 `IBakedModel`
- 渲染位置：`BlockPos` 的顶面（即 `pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5` 居中）
- 不修改原版光照，直接 `renderModelBrightness()`，与场景融合
- 每帧对每个可见节点走一次 BakedModel 渲染，注意缓存 `IBakedModel` 引用避免重复查找

### 光柱

- 纯几何体方案：一个竖直半透明四棱柱
- 顶点构造：4 个侧面 × 2 个三角形 = 8 个三角形（如用 GL_QUADS 则为 4 个 quad）
- 底部起点：末地烛顶面偏上（`pos.y + 1.0` 或 `pos.y + 1.5`）
- 高度：可配置，默认 32 格
- 宽度：方块中心 ±0.15（略窄于方块，避免和末地烛完全重叠混淆）
- 半透明：`enableBlend()` + `depthMask(false)`，alpha 默认 0.3
- 可选：从下到上 alpha 渐变（底部更亮，顶部渐隐）→ 用 `posColor()` 顶点色控制

### 相机偏移

所有渲染坐标需减去 `EntityWrap.lerpX/Y/Z(entity, partialTicks)`，与 litematica / Minecraft 渲染管线一致。

## 渲染注入点

参考 litematica 的 `RenderHandler`（通过 Mixin `MixinEntityRenderer` 或 `MixinRenderGlobal` 注入）：

```
EntityRenderer.renderWorldPass() 
  → (原版方块/实体渲染)
    → NodeIndicatorRenderer.render()  ← 在这里注入
```

或使用 Fabric API 的 `WorldRenderEvents.AFTER_TRANSLUCENT` 等事件注入，减少 Mixin 依赖。

## 配置项

| 键 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `nodeIndicatorEnabled` | boolean | false | 节点指示总开关 |
| `nodeIndicatorHotkey` | KeyBind | 未绑定 | 临时切换指示渲染 |
| `nodeIndicatorBeamHeight` | double | 32.0 | 光柱高度（格） |
| `nodeIndicatorBeamAlpha` | double | 0.3 | 光柱透明度 |
| `nodeIndicatorNormalColor` | Color | 白 | 普通节点光柱颜色 |
| `nodeIndicatorIntersectionColor` | Color | 金 | 路口节点光柱颜色 |
| `nodeIndicatorEndpointColor` | Color | 红 | 端点节点光柱颜色 |

## 性能考量

- 每帧遍历所有节点 → 先用已加载区块做空间过滤，只保留 `World.isBlockLoaded(pos)` 的
- `HashSet<BlockPos>` 去重，避免同方块重复渲染
- 末地烛 `IBakedModel` 缓存为 static final
- 光柱顶点数据每帧重建（节点位置可能变化），但单个光柱只有 4 个 quad，开销可忽略
- 预估：100 个节点同时渲染，末地烛 100 次 BakedModel 调用 + 100 个光柱 = 约 400 个 quad。现代硬件完全无压力
*（内容由AI生成，仅供参考）*
*（内容由AI生成，仅供参考）*
