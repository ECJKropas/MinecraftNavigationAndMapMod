---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 9402c18aa37537e73c8147051d320866_f88d3d887ea711f195d6525400bff409
    ReservedCode1: Uj5v3Dl+bmBE+N/wU9xYPrK+9A+elWGH/5nT8U9P2bH7hTCwsEhoHP903AhMge8KyDgUhvG4I6s1P/wRDIShn/521MLbG0QIqpknmnnWB1uwmN8BPWHTsPkR9bZhjm1PIqYNgPsNGczh9fhuAkjG60g0dmd3INWC/ywdhVmZzIBWpR9/4IrSDtRvMH4=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 9402c18aa37537e73c8147051d320866_f88d3d887ea711f195d6525400bff409
    ReservedCode2: Uj5v3Dl+bmBE+N/wU9xYPrK+9A+elWGH/5nT8U9P2bH7hTCwsEhoHP903AhMge8KyDgUhvG4I6s1P/wRDIShn/521MLbG0QIqpknmnnWB1uwmN8BPWHTsPkR9bZhjm1PIqYNgPsNGczh9fhuAkjG60g0dmd3INWC/ywdhVmZzIBWpR9/4IrSDtRvMH4=
---

# AI 工作流指引

本项目是 Fabric 多版本模组 Wayfarer，通过 ReplayMod Preprocessor 实现源码覆盖。基座为 1.20.1，26.2 / 26.1.1 在其上覆盖差异文件。

## 核心规则

1. 基座代码写在 `src/`，高版本差异文件写在 `versions/<ver>/src/`
2. 切勿修改 `versions/<ver>/gradle.properties` 中的版本参数（除非添加新版本）
3. 跳过 spotless 格式检查：构建时加 `-x spotlessCheck`

## 每次修改代码后的标准流程

```bash
# 1. 构建（不要跳过 spotless）
./gradlew spotlessApply
# ./gradlew build #此条已弃用
./gradlew clean buildAndGather


# 2. 修复编译错误后提交
git add -A && git commit -m "type(scope): description" && git push

# 3. 启动客户端验证
./gradlew runClient
```

提交格式：`feat:` / `fix:` / `chore:` / `docs:`，涉及特定版本标 `fix(26.2):`。

## 多版本注意事项

| 版本 | loom 插件 | 按键 API | remap | Java |
|------|-----------|----------|-------|------|
| 1.20.1 | fabric-loom-remap | fabric-key-binding-api-v1 | 是 | 17 |
| 26.1.1 | fabric-loom | fabric-key-mapping-api-v1 | 否 | 25 |
| 26.2 | fabric-loom | fabric-key-mapping-api-v1 | 否 | 25 |

26.x 常见 API 差异详见 `docs/multi-version-maintenance.md`。

## 项目标识

- modId / archivesBaseName：`wayfarer`
- mavenGroup：`com.ecjkim.wayfarer`
- 作者：ECJKim
*（内容由AI生成，仅供参考）*
