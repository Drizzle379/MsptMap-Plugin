package msptmap.plugin;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 自动扫描的聊天告警：一条标题加若干可点击的区块行，只用服务端可构造的 Bungee Chat 组件。
 *
 * <p>文案走本模组的语言键并带中文回退：装了模组的客户端按自己的语言显示，未装的客户端
 * （1.20.5 起支持 fallback）显示回退文案。耗时按地图热力色的同一条斜坡着色。
 */
final class Alert {
	private static final String INDENT = "  ";
	private static final int NOTE_COLOR = 0xB0B0B0;

	/** 单区块红点默认值（ms/tick），与模组的 HeatColors.DEFAULT_RED_POINT 一致。 */
	private static final float RED_POINT = 1.5f;
	private static final float YELLOW_KNEE = 1.0f / 3.0f;

	private static final String TITLE_KEY = "msptmap.alert.title";
	private static final String TITLE_FALLBACK = "[MsptMap] 服务器 MSPT 持续高于 %1$s，已自动扫描卡顿区块：";
	private static final String ROW_KEY = "msptmap.summary.line";
	private static final String ROW_FALLBACK = "%1$s @ %2$s %3$s,%4$s";
	private static final String HOVER_KEY = "msptmap.alert.hover";
	private static final String HOVER_FALLBACK = "点击在世界地图上定位该区块";

	private final Config config;

	Alert(Config config) {
		this.config = config;
	}

	/**
	 * 发给受众；数据由 agent 的 Bridge.alert 组装为 {@code {UUID[], String[], int[], int[], long[], Integer}}。
	 */
	void send(Object[] data) {
		UUID[] audience = (UUID[]) data[0];
		String[] dimensions = (String[]) data[1];
		int[] chunkXs = (int[]) data[2];
		int[] chunkZs = (int[]) data[3];
		long[] totals = (long[]) data[4];
		int windowTicks = (Integer) data[5];

		List<BaseComponent> lines = new ArrayList<>(totals.length + 1);
		TranslatableComponent title = new TranslatableComponent(TITLE_KEY, format1(config.threshold));
		title.setFallback(TITLE_FALLBACK);
		title.setBold(true);
		lines.add(title);
		for (int i = 0; i < totals.length; i++) {
			lines.add(row(dimensions[i], chunkXs[i], chunkZs[i], totals[i], windowTicks));
		}
		BaseComponent[] message = lines.toArray(new BaseComponent[0]);
		for (UUID id : audience) {
			Player player = Bukkit.getPlayer(id);
			if (player != null && player.isOnline()) {
				player.spigot().sendMessage(message);
			}
		}
	}

	/**
	 * 一条区块行：{@code 耗时 @ 维度 x,z}；整行可点击并带悬停提示。
	 */
	private BaseComponent row(String dimension, int chunkX, int chunkZ, long nanos, int windowTicks) {
		float mspt = (float) (nanos / 1_000_000.0 / Math.max(1, windowTicks));
		TextComponent value = new TextComponent(format3(mspt));
		value.setColor(ChatColor.of(hex(soften(heatRgb(mspt)))));

		TranslatableComponent name = new TranslatableComponent(dimensionKey(dimension));
		name.setFallback(dimensionFallback(dimension));

		TranslatableComponent text = new TranslatableComponent(ROW_KEY, value, name, chunkX, chunkZ);
		text.setFallback(ROW_FALLBACK);
		text.setColor(ChatColor.of(hex(NOTE_COLOR)));

		TextComponent line = new TextComponent(INDENT);
		line.setColor(ChatColor.of(hex(NOTE_COLOR)));
		line.addExtra(text);

		// 维度 ID 含冒号，命令里走字符串参数，故加引号
		String command = "/msptmap locate \"" + dimension + "\" " + chunkX + " " + chunkZ;
		for (BaseComponent part : new BaseComponent[] {line, text, value, name}) {
			part.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
			part.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover())));
		}
		return line;
	}

	private static BaseComponent hover() {
		TranslatableComponent hover = new TranslatableComponent(HOVER_KEY);
		hover.setFallback(HOVER_FALLBACK);
		return hover;
	}

	/**
	 * 维度名语言键；非原版维度用原版键 {@code dimension.<命名空间>.<路径>}。
	 */
	private static String dimensionKey(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "msptmap.dimension.overworld";
			case "minecraft:the_nether" -> "msptmap.dimension.nether";
			case "minecraft:the_end" -> "msptmap.dimension.end";
			default -> "dimension." + dimension.replace(':', '.');
		};
	}

	/** 未装模组时的维度名回退：原版三个维度给中文短名（与模组 zh_cn 一致），其余给 ID。 */
	private static String dimensionFallback(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> "主世界";
			case "minecraft:the_nether" -> "下界";
			case "minecraft:the_end" -> "末地";
			default -> dimension;
		};
	}

	/** 绿 → 黄 → 红斜坡的红分量；黄点在红点的三分之一处。 */
	private static float red(float mspt) {
		float knee = RED_POINT * YELLOW_KNEE;
		return mspt <= knee ? mspt / knee : 1.0f;
	}

	private static float green(float mspt) {
		float knee = RED_POINT * YELLOW_KNEE;
		return mspt <= knee ? 1.0f : 1.0f - Math.min(1.0f, (mspt - knee) / (RED_POINT - knee));
	}

	private static int heatRgb(float mspt) {
		return Math.round(red(mspt) * 255.0f) << 16 | Math.round(green(mspt) * 255.0f) << 8;
	}

	/** 文字档柔化：每通道 ×0xD0/0xFF，与模组 HeatColors.soften 一致。 */
	private static int soften(int rgb) {
		return ((rgb >> 16 & 0xFF) * 0xD0 / 0xFF) << 16 | ((rgb >> 8 & 0xFF) * 0xD0 / 0xFF) << 8;
	}

	private static String hex(int rgb) {
		return String.format(Locale.ROOT, "#%06X", rgb);
	}

	private static String format1(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static String format3(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}
}
