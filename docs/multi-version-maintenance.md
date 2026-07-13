---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_f7dd904d7ea711f181d7525400e6dd8f
    ReservedCode1: P4rSx4EO7nAobIATZ8PRbwrmF+CSpJWqZFVweOxwC9awwjWw1g5Sqmj0j9LitAwawcuYaLUAjvBSnvlPHq4MjIHszUZJTDpMSquxlzpoTRBZB5ULnV2maU9UewfjBpemdh3LStQw7sGd3ihkGeKS5Zva4P7+ljzMePSQefnLBxTfe2tLTwJ6EyXlSLo=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_f7dd904d7ea711f181d7525400e6dd8f
    ReservedCode2: P4rSx4EO7nAobIATZ8PRbwrmF+CSpJWqZFVweOxwC9awwjWw1g5Sqmj0j9LitAwawcuYaLUAjvBSnvlPHq4MjIHszUZJTDpMSquxlzpoTRBZB5ULnV2maU9UewfjBpemdh3LStQw7sGd3ihkGeKS5Zva4P7+ljzMePSQefnLBxTfe2tLTwJ6EyXlSLo=
---

# 多版本维护经验总结

Wayfarer（越陌度阡）从单版本（1.20.1）扩展到三版本（1.20.1 / 26.1.1 / 26.2）期间的踩坑记录与维护工作流。

---

## 1. 架构概览

### 1.1 版本覆盖机制

使用 [ReplayMod Preprocessor](https://github.com/ReplayMod/preprocessor) 实现源码覆盖。基座为 1.20.1，26.2 覆盖其上，26.1.1 覆盖 26.2：

```
src/main/          ← 1.20.1 基座（所有版本共用代码）
versions/26.2/src/ ← 26.x 差异代码（覆盖基座）
versions/26.1.1/src/ ← 26.1.1 差异代码（覆盖 26.2）
```

preprocessor 链路定义在 `build.gradle.kts` 中：

```kotlin
mc1201.link(mc2602)    // 26.2 继承 1.20.1
mc2602.link(mc26011)   // 26.1.1 继承 26.2
```

### 1.2 目录结构

```
.
├── src/                    ← 基座源码（1.20.1）
│   └── main/java/.../wayfarer/
├── versions/
│   ├── 1.20.1/             ← 1.20.1 子项目（仅 gradle.properties，无源码覆盖）
│   ├── 26.1.1/             ← 26.1.1 子项目
│   │   ├── gradle.properties
│   │   └── src/            ← 26.1.1 独有覆盖
│   └── 26.2/               ← 26.2 子项目
│       ├── gradle.properties
│       └── src/            ← 26.x 覆盖层
├── build.gradle.kts        ← 根构建配置（preprocessor 链路、spotless）
├── common.gradle.kts       ← 各版本共用构建逻辑
└── settings.gradle.kts     ← 动态子项目注册
```

### 1.3 版本隔离机制

| 层级 | 文件 | 作用 |
|------|------|------|
| 版本参数 | `versions/<ver>/gradle.properties` | MC 版本号、Fabric API 版本、依赖声明 |
| 构建共享 | `common.gradle.kts` | 统一的 loom 配置，通过 `mcVersion` 整数判断分支 |
| 源码覆盖 | `versions/<ver>/src/` | 仅放置与基座不同的文件，其余继承 |
| 资源覆盖 | `versions/<ver>/src/main/resources/` | 可覆盖 fabric.mod.json、assets 等 |

`common.gradle.kts` 中通过 `unobfuscated` 变量（`mcVersion >= 26_00_00`）自动切换：
- ≥ 26.x：使用 `fabric-loom`（不 remap）、`fabric-key-mapping-api-v1`
- < 26.x：使用 `fabric-loom-remap`、`fabric-key-binding-api-v1`

---

## 2. 核心踩坑实录

### 2.1 extractContent 参数语义变化

**问题版本**：26.2

**症状**：路线列表的背景色和条目文字跟随鼠标 Y 坐标移动，而不是固定在条目位置。

**排查过程**：
- 背景/文字出现跟随鼠标的异常行为 → 怀疑渲染时使用了鼠标坐标
- 对比 1.20.1 和 26.2 的 `AbstractSelectionList.extractItem` 方法签名
- 反编译 26.2 字节码确认参数

**根因**：

26.x 的 `extractContent` 签名发生了变化：

```java
// 1.20.1: (graphics, index, top, isHovered, partialTick)
// 26.x:    (graphics, mouseX, mouseY, isHovered, partialTick)
```

原代码将 `mouseX`、`mouseY` 当作 `index`、`top` 使用，导致渲染位置完全错误。

**修复**：使用 `entry.getY()` 获取实际 Y 坐标；斑马条纹通过 `getY() / 24` 计算，不再依赖传入参数。

```java
// 修复前（错误）
int index = mouseX;   // 实际是 X 坐标
int top = mouseY;     // 实际是 Y 坐标

// 修复后
int top = entry.getY();
int index = top / 24;
```

**通用教训**：跨大版本（特别是 Mojang mappings 从 obfuscated 切到 unobfuscated 的 26.x）时，**所有回调方法签名都应重新验证**，不能假设同名方法参数语义一致。

---

### 2.2 AbstractSelectionList 构造函数参数变化

**问题版本**：26.2

**症状**：列表条目高度异常，单个条目撑满整个列表可视区域（~400px）。

**排查过程**：条目高度异常大 → 检查条目创建逻辑 → 发现构造函数最后一个参数被误用。

**根因**：

26.x 的 `AbstractSelectionList` 构造函数：

```java
AbstractSelectionList(Minecraft, width, height, top, bottom, defaultEntryHeight)
```

最后一个 `int` 参数含义从 `bottom`（底部坐标）变成了 `defaultEntryHeight`（默认条目高度）。原代码将 `bottom` 坐标（约 400）传入，条目高度被设为 400px。

**修复**：

```java
// 修复前（条目高 400px）
addEntry(entry);

// 修复后（条目高 24px）
addEntry(entry, 24);
```

**通用教训**：构造函数签名变化是跨版本最常见的静默错误源——编译通过但行为异常。升级 MC 版本时优先检查核心基类的构造函数。

---

### 2.3 extractSelection 高亮错位

**问题版本**：26.2

**症状**：默认选择高亮框（选中条目时的背景框）与条目位置不匹配，偏移若干像素。

**排查过程**：高亮错位 → 检查父类 `extractSelection` → 发现与自定义条目渲染的坐标系不一致。

**根因**：26.x 的 `extractSelection` 实现逻辑与自定义的 `extractContent` 不协调。条目通过 `extractContent` 自行处理选中样式，父类的默认高亮框属于多余渲染且位置计算有偏差。

**修复**：参照 1.20.1 的做法，在 `RoadEntryList` 中覆盖 `extractSelection` 为空操作：

```java
@Override
protected void extractSelection(GuiGraphicsExtractor graphics, E entry, int mouseX) {
    // no-op: 选中样式由 RoadEntry.extractContent() 自行处理
}
```

**通用教训**：当子类覆盖了父类的渲染方法后，检查父类是否有其他相关渲染方法也需要一并覆盖。

---

### 2.4 按键注册 API 变更

**问题版本**：26.x（26.1.1 / 26.2）

**症状**：编译错误，`fabric-key-binding-api-v1` 模块不存在。

**排查过程**：编译报错 → 查阅 Fabric API 26.x 文档 → 发现 API 改名。

**根因**：Fabric API 在 26.x 中将按键相关 API 拆分为两个模块：

| 1.20.1 | 26.x |
|--------|------|
| `fabric-key-binding-api-v1` | `fabric-key-mapping-api-v1` |

同时 `KeyBinding` 类被重命名为 `KeyMapping`，且需要 `KeyBindingHelper.registerKeyMapping()` 包装注册。

**修复**：

1. `common.gradle.kts`：根据 `unobfuscated` 条件选择不同依赖
2. `versions/26.2/src/main/resources/fabric.mod.json`：声明 `fabric-key-mapping-api-v1`
3. 26.x 源码中 `KeyMapping` 注册改由 `KeyBindingHelper` 包装

**通用教训**：Fabric API 大版本升级时不仅检查 MC 类变更，还需关注 Fabric API 自身的 breaking changes。

---

### 2.5 文字颜色 alpha 通道

**问题版本**：26.x

**症状**：部分文字不可见或渲染为透明。

**排查过程**：文字不可见 → 怀疑颜色值问题 → 发现 26.x 渲染管线对颜色做了 ARGB 检查。

**根因**：26.x 的渲染代码对颜色值有 `ARGB.alpha(color) != 0` 的检查。如果颜色值没有显式设置 alpha 通道（如 `0xFF0000`），会被当作 alpha=0 而跳过渲染。

**修复**：确保所有颜色值显式包含 alpha 通道，如 `0xFFFF0000`。

**通用教训**：MC 26.x 的渲染管线对颜色格式更严格，所有颜色常量应使用 `0xFF` 作为 alpha 前缀。

---

### 2.6 seeThrough 参数

**问题版本**：26.x

**症状**：文字被背景遮挡或穿透显示。

**排查过程**：文字渲染异常 → 检查 `GuiGraphicsExtractor.text()` 调用 → 发现默认参数值变化。

**根因**：26.x 中 `GuiGraphicsExtractor.text()` 的 `seeThrough` 参数默认行为与 1.20.1 不同。原代码传入 `false` 导致文字被深度缓冲遮挡。

**修复**：将 `seeThrough` 从 `false` 改为 `true`。

```java
// 修复前
extractor.text(font, text, x, y, color, false);

// 修复后
extractor.text(font, text, x, y, color, true);
```

**通用教训**：渲染相关方法的 boolean 参数语义可能随版本变化，不依赖默认值，每次显式指定。

---

### 2.7 资源目录与配置路径统一

**问题版本**：全版本

**症状**：项目从 `mcnav` 重命名为 `wayfarer` 后，多处引用未同步。

**根因**：modId / 包名 / 资源目录 / 配置路径 / 翻译 key 分散在多个文件中，批量重命名容易遗漏。

**涉及位置**：

| 位置 | 示例 |
|------|------|
| `gradle.properties` | `modId`, `archivesBaseName`, `mavenGroup` |
| `fabric.mod.json` | entrypoints |
| Java 源码 | `package` / `import`, 类名, 配置路径字面量 |
| `assets/<modid>/lang/` | 翻译 yml 的顶层 key |
| 按键 key | `key.<modid>.*`, `category.<modid>` |

**修复策略**：`sed` 批量替换 + 手动验证残留 + 目录 `mv` 重命名。

**通用教训**：重命名 modId 时准备一份检查清单，逐项确认。写一个 grep 命令做最终残留检查。

---

## 3. 工作流速查

### 3.1 添加新 MC 版本

1. 在 `settings.json` 的 `versions` 数组中添加版本号
2. 创建 `versions/<版本号>/` 目录及 `gradle.properties`
3. 在 `build.gradle.kts` 中添加 preprocessor 节点并链接
4. 如有 API 差异，创建 `versions/<版本号>/src/` 放置覆盖文件

### 3.2 同步策略

- **基座优先**：新功能/通用修复写在 `src/`（1.20.1 基座）
- **按需覆盖**：仅当高版本 API 不兼容时，才在 `versions/<ver>/src/` 中放置同名覆盖文件
- **覆盖粒度**：尽量整个文件覆盖而非 `//#if` 预处理指令，保持可读性

### 3.3 构建命令

```bash
# 跳过 spotless 格式检查（缺少 npm/prettier 时）
./gradlew build -x spotlessCheck

# 运行指定版本客户端
./gradlew :26.2:runClient

# 构建并收集所有 jar 到根 build/libs/
./gradlew buildAndGather
```

### 3.4 提交规范

遵循约定式提交（Conventional Commits）：

```
feat: 新功能
fix(26.2): 26.2 版本的特有修复
fix: 全版本通用修复
chore: 构建/工具链/重命名
docs: 文档
```

### 3.5 Release 流程

```bash
# 1. 构建所有版本
./gradlew build -x spotlessCheck

# 2. 提交 & 推送
git add -A && git commit -m "chore: prepare vX.Y.Z release" && git push

# 3. 打 tag
git tag vX.Y.Z && git push --tags

# 4. 创建 release（gh CLI）
gh release create vX.Y.Z \
  --title "Wayfarer vX.Y.Z" \
  --notes-file /tmp/release_notes.md \
  versions/1.20.1/build/libs/wayfarer-*-mc1.20.1*.jar \
  versions/26.1.1/build/libs/wayfarer-*-mc26.1.1*.jar \
  versions/26.2/build/libs/wayfarer-*-mc26.2*.jar
```

### 3.6 跨版本排查技巧

1. **对比字节码**：`javap -c` 反编译可疑类的方法签名
2. **grep 残留**：重命名后用 `grep -rn "old_name" --include="*.java" --include="*.json" --include="*.yml"` 检查
3. **二分定位**：不确定是哪个版本引入的问题时，分别运行 `:1.20.1:runClient` 和 `:26.2:runClient` 对比行为
4. **优先看构造函数**：跨 MC 大版本时，核心 GUI 类的构造函数参数最易变更
*（内容由AI生成，仅供参考）*
