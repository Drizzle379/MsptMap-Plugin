package msptmap.plugin;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 插件配置：扫描权限与常态监控参数。
 *
 * <p>值存于字段，命令修改后立即生效并落盘；读盘时一律夹取到合法区间，手工改坏配置不会影响运行。
 */
final class Config {
	static final double MIN_THRESHOLD = 1.0;
	static final double MAX_THRESHOLD = 1000.0;
	static final int MIN_COOLDOWN_MINUTES = 1;
	static final int MAX_COOLDOWN_MINUTES = 1440;

	enum Access {
		OPS,
		ALL
	}

	enum Audience {
		OP,
		ALL
	}

	private final MsptMapPlugin plugin;
	Access access;
	boolean monitorEnabled;
	double threshold;
	int cooldownMinutes;
	Audience audience;

	private Config(MsptMapPlugin plugin) {
		this.plugin = plugin;
	}

	/**
	 * 从 config.yml 读取；缺失或非法值取默认值。
	 */
	static Config load(MsptMapPlugin plugin) {
		FileConfiguration file = plugin.getConfig();
		Config config = new Config(plugin);
		config.access = readAccess(file.getString("access", "ops"));
		config.monitorEnabled = file.getBoolean("monitor.enabled", false);
		config.threshold = clamp(file.getDouble("monitor.threshold", 40.0),
				MIN_THRESHOLD, MAX_THRESHOLD);
		config.cooldownMinutes = clamp(file.getInt("monitor.cooldown-minutes", 5),
				MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES);
		config.audience = readAudience(file.getString("monitor.audience", "op"));
		return config;
	}

	void save() {
		FileConfiguration file = plugin.getConfig();
		file.set("access", access.name().toLowerCase(java.util.Locale.ROOT));
		file.set("monitor.enabled", monitorEnabled);
		file.set("monitor.threshold", threshold);
		file.set("monitor.cooldown-minutes", cooldownMinutes);
		file.set("monitor.audience", audience.name().toLowerCase(java.util.Locale.ROOT));
		plugin.saveConfig();
	}

	private static Access readAccess(String value) {
		return "all".equalsIgnoreCase(value) ? Access.ALL : Access.OPS;
	}

	private static Audience readAudience(String value) {
		return "all".equalsIgnoreCase(value) ? Audience.ALL : Audience.OP;
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
