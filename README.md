# Map Art Optimizer

<img src="src/main/resources/assets/map_art_optimizer/icon.png" alt="Map Art Optimizer" width="160">

将本机 Xaero 世界地图中已经探索的地表图像，显示在原版物品展示框里的地图上，让地形和建筑获得更丰富的色彩与细节。

**仅需客户端安装**，可用于单人世界或多人服务器。

- 作者：**sweetljk**
- 贡献者：**sunapril**
- 许可证：**MIT**

## 下载与安装

从 [Releases](https://github.com/sweetljk/Map-Art-Optimizer/releases) 下载 `map-art-optimizer-0.1.0.jar`，放入游戏实例的 `mods` 文件夹并重启游戏。

所需环境：

| 组件 | 版本 |
| --- | --- |
| Minecraft Java | 26.1.2 |
| Fabric Loader | 0.19.3 或更高版本 |
| Fabric API | 0.155.3+26.1.2 |
| Xaero's World Map | **1.41.3** |
| Java | 25 |

Xaero 世界地图和 Fabric API 需要另行安装。本项目不包含这些依赖的程序文件。

如果安装过旧名 `xaero-map-frames` 的版本，请用新版替换旧 JAR，避免同时加载。原有 `config/xaero-map-frames.json` 配置和地图绑定继续沿用。

## 快速使用

1. 打开 Xaero 世界地图，确认当前世界、维度和地图实例正确，目标区域已有探索数据。
2. 对准展示框里的地图，输入 `/xmapframe bind <地图中心X> <地图中心Z>`，或用 `/xmapframe corner <地图西北角X> <地图西北角Z>` 指定范围。
3. 等待高清瓦片加载；用 `/xmapframe status` 查看覆盖比例，用 `/xmapframe help` 查看全部指令。

也可以手持地图并站在它实际描绘的区域内，输入 `/xmapframe here` 自动推算地图网格。绑定坐标是地图描绘区域的坐标，不是展示框的位置。

详细指令、地图范围示例和缓存行为见 [中文使用说明](README.zh-CN.md)。

## 显示效果与范围

- 使用 Xaero 地表色彩，按一方块一个像素生成 128×128 至 2048×2048 的高清纹理；0 级地图主要改善色彩。
- 保留原版地图旋转、发光效果、图标和标记文字；手持地图显示原版内容。
- 未探索区域保留原版底图。高清覆盖只在安装本模组的客户端显示。
- 当前版本不导入 Xaero 路径点或地名，也没有重新设计原版标记字体。
- 高清地图会随 Xaero 数据更新；大型地图墙受纹理和缓存预算限制。

## 从源码构建

使用 JDK 25，将 Xaero's World Map 1.41.3 JAR 放到 `libs/xaeroworldmap-fabric-26.1.2-1.41.3.jar`，然后执行：

```cmd
gradlew.bat build
```

也可通过 `-PxaeroJar=<JAR绝对路径>` 指定该依赖。构建产物位于 `build/libs`。

已有相同版本开发依赖缓存的 Windows 环境还可使用 `build-local.ps1` 离线构建，具体参数见 [中文使用说明](README.zh-CN.md#从源码构建)。

## 反馈

请在 [Issues](https://github.com/sweetljk/Map-Art-Optimizer/issues) 中提供游戏和模组版本、复现步骤，以及相关日志或截图。
