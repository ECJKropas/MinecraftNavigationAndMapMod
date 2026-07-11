# MinecraftNavigationAndMapMod

这是一个 Fabric 客户端模组的起始工程，目标版本先定为 **Minecraft 1.20.1**。

## 当前状态

- Java 17 已可用
- 工程已按 Fabric 1.20.1 客户端模组骨架搭好
- 入口类会在客户端启动时打印一条日志，方便确认环境正常
- 已加入一个最小“道路记录”原型：
  - 按 `R` 开始记录轨迹
  - 再按 `R` 停止记录并输入道路名、宽度
  - 记录会保存到本地 `config/mcnav/roads.json`
  - 会自动做一个简单的交叉判断
- 已加入游戏内路线列表：
  - 按 `N` 打开路线列表
  - 可以查看名称、宽度、轨迹点和交叉点
  - 可以直接打开本地网页预览
- 已加入本地网页预览服务：
  - 游戏运行时自动监听 `http://localhost:7891/`
  - 页面会绘制全部已保存道路的二维预览图
  - 同时提供 `http://localhost:7891/api/roads` JSON 数据接口

## 本机环境

- `Java 17`: `/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home`
- `Java 25`: 也已安装，但这个项目建议使用 Java 17
- `Gradle`: 目前不在 PATH 上

## 下一步

1. 用 IntelliJ IDEA 或你习惯的 IDE 打开这个工程
2. 把项目 JVM 设成 **Java 17**
3. 连接/补齐 Gradle wrapper 后运行客户端
4. 游戏里按 `R` 录路，按 `N` 看列表
5. 用浏览器打开 `http://localhost:7891/`

## 依赖来源

这个骨架参考了 Fabric 官方示例工程的 1.20.1 配置：

- https://fabricmc.net/
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/1.20.1/build.gradle
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/1.20.1/gradle.properties
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/1.20.1/settings.gradle
