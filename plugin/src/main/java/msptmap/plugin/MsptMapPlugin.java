package msptmap.plugin;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 插件入口：读取配置、接上注入器、注册通道与命令。
 *
 * <p>注入器未安装（启动时漏了 -javaagent）时仍加载插件与命令，但不会注册任何自定义通道：
 * 客户端因此认为服务端未装 MsptMap，不会发请求。
 */
public final class MsptMapPlugin extends JavaPlugin implements Listener {
	/** 客户端请求扫描。 */
	static final String SCAN_REQUEST = "msptmap:scan_request";
	/** 客户端转发 access / monitor 命令。 */
	static final String COMMAND_RELAY = "msptmap:command_relay";
	/** 服务端回发扫描结果。 */
	static final String SCAN_RESULT = "msptmap:scan_result";

	/** 需要向客户端声明的可接收通道。 */
	private static final String[] ADVERTISED_CHANNELS = {SCAN_REQUEST, COMMAND_RELAY};

	private Config config;
	private Monitor monitor;
	private BridgeAccess bridge;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		config = Config.load(this);
		monitor = new Monitor(this, config);

		PluginCommand command = getCommand("msptmap");
		if (command != null) {
			MsptMapCommand executor = new MsptMapCommand(this, config, monitor);
			command.setExecutor(executor);
			command.setTabCompleter(executor);
		}

		bridge = BridgeAccess.create(this);
		if (bridge == null) {
			getLogger().warning("未检测到 MsptMap 注入器：本 jar 需同时以 -javaagent 加载后重启服务端，"
					+ "当前扫描与监控不可用");
			return;
		}
		bridge.setSender(this::send);
		bridge.setAlertSink(new Alert(config)::send);
		bridge.setAudienceSupplier(this::audience);
		bridge.setTickSink(monitor::onTick);

		getServer().getMessenger().registerOutgoingPluginChannel(this, SCAN_RESULT);
		getServer().getMessenger().registerIncomingPluginChannel(this, SCAN_REQUEST,
				new ScanListener(this, bridge));
		getServer().getMessenger().registerIncomingPluginChannel(this, COMMAND_RELAY,
				new RelayListener(this));
		getServer().getPluginManager().registerEvents(this, this);
		for (Player player : Bukkit.getOnlinePlayers()) {
			bridge.advertiseChannels(player.getUniqueId(), ADVERTISED_CHANNELS);
		}
		getLogger().info("MsptMap 已启用，注入器已连接");
	}

	/**
	 * 玩家进入游戏后声明服务端可接收的通道；Fabric 客户端据此判断能否发包。
	 */
	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (bridge != null) {
			bridge.advertiseChannels(event.getPlayer().getUniqueId(), ADVERTISED_CHANNELS);
		}
	}

	@Override
	public void onDisable() {
		if (bridge != null) {
			bridge.reset();
		}
	}

	/**
	 * 扫描权限：配置放开给所有人，或来源持有 msptmap.use（控制台恒为 true）。
	 */
	boolean canScan(CommandSender sender) {
		return config.access == Config.Access.ALL || sender.hasPermission("msptmap.use");
	}

	/** 自动扫描触发时使用。 */
	int startAuto() {
		return bridge == null ? -1 : bridge.startAuto(Monitor.AUTO_SCAN_SECONDS);
	}

	/** 监控受众是否至少有一人。 */
	boolean hasAudience() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (config.audience == Config.Audience.ALL || player.isOp()) {
				return true;
			}
		}
		return false;
	}

	/** 当前在线受众（UUID），供 agent 广播结果与告警。 */
	private UUID[] audience() {
		List<UUID> ids = new ArrayList<>();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (config.audience == Config.Audience.ALL || player.isOp()) {
				ids.add(player.getUniqueId());
			}
		}
		return ids.toArray(new UUID[0]);
	}

	/**
	 * agent 的发包器：玩家不在线或客户端未监听结果通道时返回 false。
	 */
	private boolean send(UUID id, byte[] payload) {
		Player player = Bukkit.getPlayer(id);
		if (player == null || !player.isOnline()
				|| !player.getListeningPluginChannels().contains(SCAN_RESULT)) {
			return false;
		}
		try {
			player.sendPluginMessage(this, SCAN_RESULT, payload);
			return true;
		} catch (IllegalArgumentException | IllegalStateException error) {
			return false;
		}
	}

	BridgeAccess bridge() {
		return bridge;
	}
}
