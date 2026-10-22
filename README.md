# MsptMap-Plugin

为 Paper 服务端提供 MsptMap 的采样与发包能力。安装 `msptmap-fabric` 1.0.1 的客户端连接后，可在 Xaero 世界地图上查看逐区块 MSPT 热力图。

当前为 alpha 测试版，插件与客户端模组需配套使用（协议 `0x4D535034`）。

jar 同时承担两种角色：放入 `plugins/` 是 Bukkit 插件，以 `-javaagent` 加载是注入器。两者必须配合使用。

## 环境要求

| 项目 | 要求 |
|---|---|
| Java | 25 |
| 服务端 | Paper 26.1.2 / 26.2 / 26.3 |
| 客户端 | `msptmap-fabric` 1.0.1（协议 `0x4D535034`，需与服务器 Minecraft 版本对应） |
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

插件与注入器必须是同一个 jar，且只放在 `plugins/` 下。漏加 `-javaagent` 时插件仍会加载，但扫描与监控不可用。不支持 `/reload`，修改配置后需重启。无法自定义 JVM 参数的托管面板不能使用。

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/msptmap scan [秒数]` | 开始一次扫描，秒数范围 1～60，默认 2。控制台执行时结果打印到控制台；玩家执行时结果同时回发客户端。 | `msptmap.use` |
| `/msptmap access [ops\|all]` | 查看或设置谁可以发起扫描。 | `msptmap.admin` |
| `/msptmap monitor [on\|off\|threshold <mspt>\|cooldown <分钟>\|audience <op\|all>]` | 查看或修改常态监控设置。 | `msptmap.admin` |

权限节点默认均为 OP。`access` 设为 `all` 后，任何玩家都可发起扫描。

## 配置

`plugins/MsptMap/config.yml`：

```yaml
access: ops          # ops = 仅 OP，all = 所有玩家
monitor:
  enabled: false     # 常态监控开关
  threshold: 40.0    # 触发阈值（MSPT），1.0 ～ 1000.0
  cooldown-minutes: 5  # 两次自动扫描的最短间隔，1 ～ 1440
  audience: op       # 告警接收范围：op = 仅在线 OP，all = 所有在线玩家
```

通过命令修改会立即生效并写回文件；手工修改文件需重启。

## 常态监控

默认关闭。开启后，注入器每 tick 回报整 tick 的执行耗时；插件维护 5 秒滑动平均，每 20 tick 评估一次。连续 5 次高于阈值、存在受众且不在冷却中时，自动发起一次 5 秒扫描，并把最重区块列表发给受众。每行可点击，点击后由客户端定位到对应区块。

## 平台兼容性

| 服务端 | 状态 |
|---|---|
| Paper 26.1.2 / 26.2 / 26.3 | 支持（已验证） |
| Purpur / Pufferfish / Leaves 等 Paper 分支 | 未验证，NMS 改动可能导致注入失败 |
| Spigot 26.x | 未验证，注入点需实测 |
| Folia | 不支持，region 多线程模型与整 tick 采样语义不兼容 |

## 构建

需要 JDK 25 与网络访问：

```bash
./gradlew :plugin:jar
```

Windows 使用 `gradlew.bat :plugin:jar`。产物位于 `plugin/build/libs/MsptMap-Plugin-1.0.1-alpha+26.x.jar`。

## 已知限制

- 已验证 Paper 26.1.2、26.2、26.3。服务端升级后 NMS 方法签名变化可能导致注入失败，日志会记录未注入的目标。
- 单条插件消息上限约 1 MB。快照按 700 KiB 预算裁剪，区块极多时部分低权重区块不会出现在热力图中。
- 末影珍珠票依赖 Paper 私有字段名反射识别，字段更名后该票会显示为“未知”。

## 许可

MIT，见 [LICENSE](LICENSE)。
