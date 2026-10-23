package msptmap.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 服务端的 /msptmap 命令：scan、access 与 monitor 三条子命令。
 *
 * <p>回执一律用中文普通文本发回来源；access 与 monitor 修改后立即落盘。
 * 与模组服务端版的区别：scan 允许带上发起玩家，装了模组的玩家能从聊天栏直接触发并在地图上看到结果。
 */
final class MsptMapCommand implements CommandExecutor, TabCompleter {
	/** 与 agent 侧 MsptSampler 的区间一致；命令层先夹取，回执里报的值即实际生效值。 */
	private static final int MIN_SECONDS = 1;
	private static final int MAX_SECONDS = 60;

	private static final List<String> MONITOR_ITEMS =
			List.of("on", "off", "threshold", "cooldown", "audience");

	private final MsptMapPlugin plugin;
	private final Config config;
	private final Monitor monitor;

	MsptMapCommand(MsptMapPlugin plugin, Config config, Monitor monitor) {
		this.plugin = plugin;
		this.config = config;
		this.monitor = monitor;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0) {
			usage(sender);
			return true;
		}
		switch (args[0].toLowerCase(Locale.ROOT)) {
			case "scan" -> scan(sender, args);
			case "access" -> {
				if (requireAdmin(sender)) {
					access(sender, args);
				}
			}
			case "monitor" -> {
				if (requireAdmin(sender)) {
					monitor(sender, args);
				}
			}
			default -> usage(sender);
		}
		return true;
	}

	/** scan [seconds]：不带秒数用服务端默认值。 */
	private void scan(CommandSender sender, String[] args) {
		if (!plugin.canScan(sender)) {
			sender.sendMessage("你没有使用 MsptMap 扫描的权限。");
			return;
		}
		BridgeAccess bridge = plugin.bridge();
		if (bridge == null) {
			sender.sendMessage("MsptMap 注入器未安装：请把本插件 jar 同时作为 -javaagent 启动参数加载，然后重启服务端。");
			return;
		}
		int seconds = BridgeAccess.DEFAULT_SECONDS;
		if (args.length >= 2) {
			try {
				seconds = Integer.parseInt(args[1]);
			} catch (NumberFormatException error) {
				sender.sendMessage("秒数必须是整数（" + MIN_SECONDS + " ~ " + MAX_SECONDS + "）。");
				return;
			}
			seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
		}
		int result = bridge.start(seconds, sender instanceof Player player ? player.getUniqueId() : null);
		switch (result) {
			case BridgeAccess.STARTED -> {
				sender.sendMessage("开始扫描 " + seconds + " 秒。");
				plugin.getLogger().info(sender.getName() + " 开始扫描 " + seconds + " 秒");
			}
			case BridgeAccess.BUSY -> sender.sendMessage("扫描失败：已有扫描正在进行。");
			case BridgeAccess.COOLDOWN -> sender.sendMessage(
					"扫描失败：距上次扫描结束不足 " + BridgeAccess.COOLDOWN_SECONDS + " 秒，请稍后再试。");
			case BridgeAccess.STALLED -> sender.sendMessage("扫描失败：服务端当前没有在运行（空载暂停或长时间卡顿）。");
			default -> sender.sendMessage("扫描失败：未知状态。");
		}
	}

	/** access [ops|all]：不带参数时打印当前扫描权限。 */
	private void access(CommandSender sender, String[] args) {
		if (args.length < 2) {
			sender.sendMessage("MsptMap 扫描权限：" + lower(config.access.name())
					+ "（ops = 仅 OP，all = 所有玩家）");
			return;
		}
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "ops" -> {
				config.access = Config.Access.OPS;
				saved(sender, "access", "ops");
			}
			case "all" -> {
				config.access = Config.Access.ALL;
				saved(sender, "access", "all");
			}
			default -> sender.sendMessage("用法：/msptmap access [ops|all]");
		}
	}

	/** monitor [on|off|threshold <mspt>|cooldown <minutes>|audience <op|all>]。 */
	private void monitor(CommandSender sender, String[] args) {
		if (args.length < 2) {
			status(sender);
			return;
		}
		switch (args[1].toLowerCase(Locale.ROOT)) {
			case "on" -> {
				config.monitorEnabled = true;
				saved(sender, "monitor.enabled", "true");
			}
			case "off" -> {
				config.monitorEnabled = false;
				// 关掉即丢弃窗口与冷却，再开时从头数起
				monitor.reset();
				saved(sender, "monitor.enabled", "false");
			}
			case "threshold" -> threshold(sender, args);
			case "cooldown" -> cooldown(sender, args);
			case "audience" -> audience(sender, args);
			default -> sender.sendMessage("用法：/msptmap monitor [" + String.join("|", MONITOR_ITEMS) + "] ...");
		}
	}

	private void threshold(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage("用法：/msptmap monitor threshold <mspt>（"
					+ format1(Config.MIN_THRESHOLD) + " ~ " + format1(Config.MAX_THRESHOLD) + "）");
			return;
		}
		double value;
		try {
			value = Double.parseDouble(args[2]);
		} catch (NumberFormatException error) {
			sender.sendMessage("阈值必须是数字。");
			return;
		}
		if (value < Config.MIN_THRESHOLD || value > Config.MAX_THRESHOLD) {
			sender.sendMessage("阈值范围为 " + format1(Config.MIN_THRESHOLD) + " ~ "
					+ format1(Config.MAX_THRESHOLD) + " mspt。");
			return;
		}
		config.threshold = value;
		saved(sender, "monitor.threshold", format1(value));
	}

	private void cooldown(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage("用法：/msptmap monitor cooldown <minutes>（"
					+ Config.MIN_COOLDOWN_MINUTES + " ~ " + Config.MAX_COOLDOWN_MINUTES + "）");
			return;
		}
		int value;
		try {
			value = Integer.parseInt(args[2]);
		} catch (NumberFormatException error) {
			sender.sendMessage("冷却时间必须是整数分钟。");
			return;
		}
		if (value < Config.MIN_COOLDOWN_MINUTES || value > Config.MAX_COOLDOWN_MINUTES) {
			sender.sendMessage("冷却范围为 " + Config.MIN_COOLDOWN_MINUTES + " ~ "
					+ Config.MAX_COOLDOWN_MINUTES + " 分钟。");
			return;
		}
		config.cooldownMinutes = value;
		saved(sender, "monitor.cooldown-minutes", Integer.toString(value));
	}

	private void audience(CommandSender sender, String[] args) {
		if (args.length < 3) {
			sender.sendMessage("用法：/msptmap monitor audience <op|all>");
			return;
		}
		switch (args[2].toLowerCase(Locale.ROOT)) {
			case "op" -> {
				config.audience = Config.Audience.OP;
				saved(sender, "monitor.audience", "op");
			}
			case "all" -> {
				config.audience = Config.Audience.ALL;
				saved(sender, "monitor.audience", "all");
			}
			default -> sender.sendMessage("用法：/msptmap monitor audience <op|all>");
		}
	}

	/** 状态（含均值）一行、各参数一行；冷却中另加一行剩余时长。 */
	private void status(CommandSender sender) {
		sender.sendMessage("MsptMap 监控：" + stateName(monitor.state())
				+ "（均值 " + format1(monitor.meanMspt()) + " mspt）");
		sender.sendMessage("阈值 " + format1(config.threshold) + " mspt；冷却 "
				+ config.cooldownMinutes + " 分钟；接收范围 " + lower(config.audience.name()));
		long cooldown = monitor.cooldownRemainingSeconds();
		if (cooldown > 0) {
			sender.sendMessage("冷却中：剩余 " + cooldown + " 秒");
		}
	}

	private boolean requireAdmin(CommandSender sender) {
		if (sender.hasPermission("msptmap.admin")) {
			return true;
		}
		sender.sendMessage("你没有管理 MsptMap 设置的权限。");
		return false;
	}

	private void saved(CommandSender sender, String name, String value) {
		config.save();
		sender.sendMessage("MsptMap：" + name + " = " + value);
	}

	private void usage(CommandSender sender) {
		sender.sendMessage("用法：/msptmap scan [秒数]");
		if (sender.hasPermission("msptmap.admin")) {
			sender.sendMessage("/msptmap access [ops|all]；/msptmap monitor [on|off|threshold|cooldown|audience]");
		}
	}

	private static String stateName(Monitor.State state) {
		return switch (state) {
			case OFF -> "已关闭";
			case OK -> "正常";
			case WATCHING -> "观察中";
			case COOLDOWN -> "冷却中";
		};
	}

	private static String lower(String value) {
		return value.toLowerCase(Locale.ROOT);
	}

	private static String format1(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			List<String> roots = new ArrayList<>(3);
			if (plugin.canScan(sender)) {
				roots.add("scan");
			}
			if (sender.hasPermission("msptmap.admin")) {
				roots.add("access");
				roots.add("monitor");
			}
			return suggest(roots, args[0]);
		}
		String root = args[0].toLowerCase(Locale.ROOT);
		if (args.length == 2) {
			return switch (root) {
				case "scan" -> plugin.canScan(sender) ? suggest(List.of("1", "5", "10"), args[1]) : List.of();
				case "access" -> sender.hasPermission("msptmap.admin")
						? suggest(List.of("ops", "all"), args[1]) : List.of();
				case "monitor" -> sender.hasPermission("msptmap.admin")
						? suggest(MONITOR_ITEMS, args[1]) : List.of();
				default -> List.of();
			};
		}
		if (args.length == 3 && root.equals("monitor") && sender.hasPermission("msptmap.admin")) {
			return switch (args[1].toLowerCase(Locale.ROOT)) {
				case "threshold" -> suggest(List.of("20", "40", "60"), args[2]);
				case "cooldown" -> suggest(List.of("1", "5", "10"), args[2]);
				case "audience" -> suggest(List.of("op", "all"), args[2]);
				default -> List.of();
			};
		}
		return List.of();
	}

	/** 按前缀筛选候选。 */
	private static List<String> suggest(List<String> values, String prefix) {
		List<String> result = new ArrayList<>();
		String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
		for (String value : values) {
			if (value.startsWith(lowerPrefix)) {
				result.add(value);
			}
		}
		return result;
	}
}
