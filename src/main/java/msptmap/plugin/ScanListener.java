package msptmap.plugin;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 扫描请求：校验格式与权限，然后交给 agent 开窗。
 *
 * <p>状态包（START / BUSY / COOLDOWN / DENIED）由 agent 编码后经发包器回给玩家，本类只记录结果。
 */
final class ScanListener implements PluginMessageListener {
	private final MsptMapPlugin plugin;
	private final BridgeAccess bridge;

	ScanListener(MsptMapPlugin plugin, BridgeAccess bridge) {
		this.plugin = plugin;
		this.bridge = bridge;
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		int protocol;
		int seconds;
		try {
			Packets packets = new Packets(message);
			protocol = packets.readVarInt();
			seconds = packets.readVarInt();
		} catch (IllegalArgumentException error) {
			plugin.getLogger().warning("玩家 " + player.getName() + " 的扫描请求包解析失败，已忽略");
			return;
		}
		if (protocol != MsptMapPlugin.PROTOCOL) {
			plugin.getLogger().warning("玩家 " + player.getName() + " 的 MsptMap 版本与本端不一致（对面 "
					+ protocol + "，本端 " + MsptMapPlugin.PROTOCOL + "），仍按其请求执行");
		}
		if (!plugin.canScan(player)) {
			bridge.sendDenied(player.getUniqueId());
			return;
		}
		int requested = seconds > 0 ? seconds : BridgeAccess.DEFAULT_SECONDS;
		int result = bridge.start(requested, player.getUniqueId());
		switch (result) {
			case BridgeAccess.STARTED -> plugin.getLogger().info("玩家 " + player.getName()
					+ " 开始扫描 " + requested + " 秒");
			case BridgeAccess.BUSY -> plugin.getLogger().info("玩家 " + player.getName()
					+ " 的扫描请求被拒绝：已有扫描进行中");
			case BridgeAccess.COOLDOWN -> plugin.getLogger().info("玩家 " + player.getName()
					+ " 的扫描请求距上次结束不足 " + BridgeAccess.COOLDOWN_SECONDS + " 秒，已忽略");
			default -> plugin.getLogger().info("玩家 " + player.getName()
					+ " 的扫描请求被拒绝：服务端当前没有在运行");
		}
	}
}
