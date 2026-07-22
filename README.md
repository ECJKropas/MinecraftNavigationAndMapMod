# Wayfarer（越陌度阡）

Wayfarer（越陌度阡）是一个客户端侧 Minecraft Fabric 模组，帮助你在游戏中记录和浏览走过的道路。

- 按下按键即可开始/停止记录你走过的轨迹
- 自动识别道路交叉口
- 在游戏中随时查看已记录的路线列表
- 内置本地网页预览服务，在浏览器中查看道路地图

## 支持版本

| Minecraft | 模组版本 |
| --------- | -------- |
| 1.20.1    | 0.2.1    |
| 26.1.1    | 0.2.1    |
| 26.2      | 0.2.1    |

## 使用方式

| 按键 | 功能                |
| ---- | ------------------- |
| `R`  | 开始 / 停止记录道路 |
| `N`  | 打开路线列表        |

### 记录道路

1. 在游戏中按下 `R`，开始记录你的移动轨迹
2. 再次按下 `R`，输入道路名称和宽度即可保存
3. 道路数据保存在 `.minecraft/config/wayfarer/roads.json`

### 路线列表

- 按 `N` 打开路线列表，查看所有已保存的道路
- 列表显示道路名称、宽度、轨迹点数量和交叉点信息
- 点击"在浏览器中预览"可在浏览器中查看二维道路地图

### 网页预览

游戏运行时会自动启动本地 HTTP 服务：

- 地图预览：`http://localhost:7891/`
- JSON 数据接口：`http://localhost:7891/api/roads`

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/)
2. 下载对应 Minecraft 版本的模组 JAR
3. 放入 `.minecraft/mods/` 目录
4. 启动游戏

## 依赖

- [malilib](https://github.com/sakura-ryoko/malilib)（**必需前置**，提供按键绑定与配置界面）
- Fabric API（`fabric-lifecycle-events-v1`、`fabric-key-binding-api-v1`、`fabric-resource-loader-v0`）

## 构建

```bash
# 构建全部版本
./gradlew build -x spotlessCheck

# 构建并启动指定版本
./gradlew :1.20.1:runClient
```

需要 JDK 17 及以上。

## License

GPL-3.0-only
