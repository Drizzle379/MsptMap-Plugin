package msptmap.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 命令转发：只放行以 {@code msptmap } 开头的命令，再以玩家自身身份交给命令系统。
 *
 * <p>权限不在此处判定：{@code dispatchCommand} 使用玩家自身来源，各子命令的权限照常生效。
 */
final class RelayListener implements PluginMessageListener {
	private static final int PROTOCOL = 0x4D535034;
	private static final String PREFIX = "msptmap ";

	private final MsptMapPlugin plugin;

	RelayListener(MsptMapPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		int protocol;
		String command;
		try {
			Packets packets = new Packets(message);
			protocol = packets.readVarInt();
			command = packets.readUtf();
		} catch (IllegalArgumentException error) {
			plugin.getLogger().warning("玩家 " + player.getName() + " 的转发包解析失败，已忽略");
			return;
		}
		if (!command.startsWith(PREFIX)) {
			plugin.getLogger().warning("玩家 " + player.getName() + " 转发的命令不在允许范围内，已忽略："
					+ command);
			return;
		}
		if (protocol != PROTOCOL) {
			plugin.getLogger().warning("玩家 " + player.getName() + " 的 MsptMap 版本与本端不一致（对面 "
					+ protocol + "，本端 " + PROTOCOL + "），仍按其请求执行");
		}
		Bukkit.dispatchCommand(player, command);
	}
}
