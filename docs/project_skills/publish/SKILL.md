---
name: publish
description: Wayfarer 项目完整发布流程。版本号更新 → 构建三版本 jar（MC 1.20.1 / 26.1.1 / 26.2）→ 生成 release note → Git 提交推送 → gh release create → Modrinth 三版本上传。当用户提及发布、release、publish、发版、上传 Modrinth 或构建正式版时触发。
---

# Wayfarer 发布流程

项目根目录 `/Users/cjkim/Documents/MinecraftNavigationAndMapMod`。以下所有操作默认 `cd` 到该项目根目录执行。

## 前置确认

开始前确认以下信息：

- 目标版本号（如 `v0.3.3`）
- 三个目标 MC 版本：`mc1.20.1`、`mc26.1.1`、`mc26.2`
- `gh` CLI 已登录且 `ego-browser` 可用

## 工作流程

按顺序执行 6 个阶段，每个阶段完成后再进入下一阶段。

### 1. 版本号更新

- 修改项目根目录 `gradle.properties` 的 `modVersion` 字段为目标版本号（不带 `v` 前缀，如 `0.3.3`）
- 不要修改 `versions/<ver>/gradle.properties` 中的版本参数

### 2. 构建

```bash
cd /Users/cjkim/Documents/MinecraftNavigationAndMapMod
./gradlew clean
BUILD_RELEASE=true ./gradlew clean buildAndGather
```

构建成功后产出三个 jar（位于 `build/libs/`）：
- `wayfarer-v0.3.3-mc1.20.1.jar`
- `wayfarer-v0.3.3-mc26.1.1.jar`
- `wayfarer-v0.3.3-mc26.2.jar`

### 3. 生成 Release Note

文件路径：`docs/release-notes/vX.Y.Z.md`（如 `docs/release-notes/v0.3.3.md`）

生成方式：使用 `git log` 获取自上一个 tag 以来的所有 commit：
```bash
git log <previous-tag>..HEAD --oneline --no-merges
```

按以下格式整理：
```markdown
## vX.Y.Z

### Features
- feat1
- feat2

### Fixes
- fix1
- fix2

### Chores
- chore1
```

### 4. Git 提交与推送

```bash
./gradlew spotlessApply
git add -A
git commit -m "chore: release vX.Y.Z"
git push
```

提交格式：`feat:` / `fix:` / `chore:` / `docs:`。

### 5. GitHub Release

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
gh release create vX.Y.Z \
  --title "vX.Y.Z" \
  --notes-file docs/release-notes/vX.Y.Z.md \
  build/libs/wayfarer-vX.Y.Z-mc1.20.1.jar \
  build/libs/wayfarer-vX.Y.Z-mc26.1.1.jar \
  build/libs/wayfarer-vX.Y.Z-mc26.2.jar
```

> Very Important! gh由于信息配置原因以及代理问题,请告知User要用如上gh命令发布,让User发布,不要自己发布,你执行git相关命令即可.

### 6. Modrinth 上传

通过 ego-browser 操作 Modrinth 版本管理页面（`https://modrinth.com/mod/wayfarer-in-game-gis-system/settings/versions`），**三个 MC 版本各走一轮**，每轮上传一个 jar。

详细操作指南见 [references/modrinth_upload.md](references/modrinth_upload.md)。

核心步骤（每轮）：
1. 点击「Create version」按钮
2. `uploadFile` 上传对应 jar，等待 6-8 秒解析
3. 修复 Detected versions：取消多余版本，搜索并只保留该 jar 对应的 MC 版本
4. 进入 Details 标签，在 CodeMirror 编辑器中填写变更日志
5. 进入 Metadata 标签，添加依赖：Fabric API + MaLiLib
6. 提交发布

**Modrinth 上传顺序**：mc1.20.1 → mc26.1.1 → mc26.2
