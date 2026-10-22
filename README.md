# MsptMap-Plugin

为 Paper 等服务端提供 [MsptMap](https://github.com/Drizzle379/MsptMap) 的采样与发包能力。安装 `msptmap-fabric` 1.0.1 的客户端连接后，可在 Xaero 世界地图上查看逐区块 MSPT 热力图。

jar 同时承担两种角色：放入 `plugins/` 是 Bukkit 插件，以 `-javaagent` 加载是注入器。两者必须配合使用。

## 环境要求

| 项目 | 要求 |
|---|---|
| Java | 25 |
| 服务端 | Paper 26.1.2 / 26.2 / 26.3 |
| 客户端 | `msptmap-fabric` 1.0.1 |
| 启动参数 | 可自行添加 `-javaagent` |

## 部署

1. 将 `MsptMap-Plugin-1.0.1-alpha+26.x.jar` 放入 `plugins/`。
2. ⚠️ **重要**：在启动命令的 `-jar` 之前加入 `-javaagent`：

   ```bash
   java -Xms4G -Xmx4G -javaagent:plugins/MsptMap-Plugin-1.0.1-alpha+26.x.jar -jar paper.jar nogui
   ```

3. 启动后日志出现以下两行即为正常：

   ```text
   MsptMap 注入器已安装
   [MsptMap] MsptMap 已启用，注入器已连接
   ```

插件与注入器必须是同一个 jar，且只放在 `plugins/` 下。

## 平台兼容性

| 服务端 | 状态 |
|---|---|
| Paper 26.1.2 / 26.2 / 26.3 | 支持（已验证） |
| Purpur / Pufferfish / Leaves 等 Paper 分支 | 未验证，NMS 改动可能导致注入失败 |
| Spigot 26.x | 未验证，注入点需实测 |
| Folia | 不支持，region 多线程模型与整 tick 采样语义不兼容 |

## 已知限制

- 已验证 Paper 26.1.2、26.2、26.3。服务端升级后 NMS 方法签名变化可能导致注入失败，日志会记录未注入的目标。
- 单条插件消息上限约 1 MB。快照按 700 KiB 预算裁剪，区块极多时部分低权重区块不会出现在热力图中。

## 许可

MIT，见 [LICENSE](LICENSE)。
