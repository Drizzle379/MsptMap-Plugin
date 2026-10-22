# MsptMap-Plugin

为 Paper 服务端提供 MsptMap 的服务端采样与发包能力。安装 `msptmap-fabric` 模组的客户端连接后，可在 Xaero 世界地图上查看逐区块 MSPT 热力图，客户端无需改动。

本项目是单一 jar，同时承担两种角色：

- 放入 `plugins/` 作为 Bukkit 插件，提供命令、权限、配置、通道与常态监控。
- 通过 `-javaagent` 加载，用 ASM 注入服务端 tick 方法，完成逐区块计时、快照构建与结果发送。

服务端采样依赖注入，两者必须配合使用。

## 环境要求

| 项目 | 要求 |
|---|---|
| Java | 25 |
| 服务端 | Paper 26.2（当前唯一验证版本） |
| 客户端 | 安装 `msptmap-fabric` 模组 |
| 启动参数 | 必须能自行添加 `-javaagent` |

## 部署

1. 将 `MsptMap-Plugin-1.0.0.jar` 放入服务端 `plugins/` 目录。
2. 修改启动命令，将 `-javaagent` 放在 `-jar` 之前：

   ```bash
   java -Xms4G -Xmx4G -javaagent:plugins/MsptMap-Plugin-1.0.0.jar -jar paper.jar nogui
   ```

3. 启动服务端。日志出现以下两行表示加载正常：

   ```text
   MsptMap 注入器已安装
   [MsptMap] MsptMap 已启用，注入器已连接
   ```

4. 按需修改首次启动生成的 `plugins/MsptMap/config.yml` 后重启服务端。

注意事项：

- 插件与注入器必须是同一个 jar，且只放在 `plugins/` 下。
- 漏加 `-javaagent` 时插件仍会加载，但扫描与监控不可用，启动日志会给出警告。
- 不支持 `/reload`；手工修改 `config.yml` 后需重启。
- 无法自定义 JVM 启动参数的托管面板不能使用本插件。

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

监控默认关闭。开启后：

1. 注入器每 tick 回报服务端整 tick 的实际执行耗时。
2. 插件维护 5 秒滑动平均，每 20 tick 评估一次；窗口填满后才参与判定。
3. 连续 5 次评估高于阈值、存在受众且不在冷却中时，自动发起一次 5 秒扫描。
4. 扫描结束后向受众发送聊天告警：标题加最重区块列表，每行可点击，点击后由客户端模组定位到对应区块。

## 平台兼容性

| 服务端 | 状态 | 说明 |
|---|---|---|
| Paper 26.2 | 支持 | 当前唯一验证版本 |
| Purpur / Pufferfish / Leaves 等 Paper 分支 | 未验证 | 继承 Paper API，但 NMS 改动可能导致注入失败 |
| Spigot 26.x | 未验证 | 插件仅用 Bukkit API，注入点需实测 |
| Folia | 不支持 | region 多线程模型与整 tick 采样语义不兼容 |
| Velocity / BungeeCord | 不适用 | 代理端不执行世界 tick，请安装到后端 Paper |
| Fabric / Forge / NeoForge | 不适用 | 请使用模组版 MsptMap |

## 构建

需要 JDK 25 与网络访问（下载 Paper 26.2 dev bundle 及依赖）：

```bash
./gradlew :plugin:jar
```

Windows：

```bat
gradlew.bat :plugin:jar
```

产物位于 `plugin/build/libs/MsptMap-Plugin-1.0.0.jar`。

## 实现概要

- `agent/`：`premain` 注册 `ClassFileTransformer`，用 ASM 改写 `ServerLevel`、`MinecraftServer` 等目标方法，在方法头尾记录随机刻、计划刻、方块实体、实体、生成、方块事件、邻居更新七类耗时。
- Paperclip 不支持追加类路径，注入器改写 `Paperclip.setupClasspath`，在返回前把自身运行时类目录追加进去；插件类仍由 `PluginClassLoader` 加载。
- 扫描窗口结束后构建快照，按 700 KiB 预算裁剪后编码，经 `msptmap:scan_result` 回发。
- 玩家加入时，服务端经 NMS 发送 `minecraft:register` 声明可接收的通道；Fabric 客户端据此允许发送扫描请求。
- 协议与 Fabric 模组版逐字节一致，通道名不变。

## 已知限制

- 仅验证 Paper 26.2。服务端升级后 NMS 方法签名变化可能导致注入失败，日志会记录未注入的目标。
- 单条插件消息上限约 1 MB。快照按 700 KiB 预算裁剪，区块极多时部分低权重区块不会出现在热力图中。
