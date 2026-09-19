# Neo Voxy — Minecraft 1.20.1 Forge

Neo Voxy 的原生 Minecraft 1.20.1 Forge 移植分支。当前版本为 `0.3.5`，
包含核心 LOD 渲染、流体斜坡与通用稳定性修复。

Native Minecraft 1.20.1 Forge port of Neo Voxy. Version 0.3.5 includes core LOD
rendering, fluid slopes, and general stability fixes.

## 环境要求 / Requirements

| 组件 / Component | 要求 / Requirement |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.3.0 或更高 / 47.3.0 or newer |
| Java | 17 |
| Embeddium | 0.3.31 或更高，必需 / 0.3.31 or newer, required |
| Oculus | 1.8.0 或更高，可选 / 1.8.0 or newer, optional |

这是原生 Forge 模组，不需要 Fabric API、Sinytra Connector、Sodium 或 Iris。

This is a native Forge mod. Fabric API, Sinytra Connector, Sodium, and Iris are
not required.

发布构建仅保留 Windows x64 与 Linux x64 所需原生库，其他平台数据不会写入成品。
The release JAR only contains native libraries for Windows x64 and Linux x64.

## 当前范围 / Current scope

- 核心 Voxy LOD 生成、存储与渲染
- Embeddium 区块渲染兼容
- 可选 Oculus 光影接口兼容
- 第三方区块调色板的安全回退，避免单个未知调色板中断 LOD 摄取
- 删除世界前释放 Voxy 渲染器和数据库句柄，减少 Windows 缓存锁残留
- Java 17 兼容实现与稳定版本显示
- 精简发布构建，移除 RocksDB 中无关平台与 CPU 架构的数据

Create、Sable、Domum Ornamentum、节气等模组联动暂不包含在此测试版本中。

Create, Sable, Domum Ornamentum, seasonal rendering, and other mod integrations
are intentionally excluded from this alpha.

## 注意 / Notice

这是早期测试版本，请先备份存档。更新后若出现异常，请关闭游戏，删除 Neo Voxy
配置文件及存档中的 `voxy` 缓存后重试。

This is an early alpha. Back up worlds first. If an update causes problems,
close the game and remove the Neo Voxy config and the world's `voxy` cache
before retrying.

维护者 / Maintainer: JohnSnow  
仓库 / Repository: https://github.com/NHblock-Johnsnow/neo-voxy
