package msptmap.agent;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * 插件与注入代码之间的唯一接口。
 *
 * <p>本类只使用 JDK 类型：插件经服务端类加载器反射调用它，注入代码也调用它。所有涉及游戏类的
 * 操作都转交 {@link Nms}，因此加载本类本身不会牵动 NMS。
 *
 * <p>插件启动时通过 setter 挂上发包、告警、受众与 tick 回调；未挂时对应功能静默跳过。
 */
public final class Bridge {
	public static final int STARTED = 0;
	public static final int BUSY = 1;
	public static final int COOLDOWN = 2;
	public static final int STALLED = 3;

	/** 未指定秒数时的默认值。 */
	public static final int DEFAULT_SECONDS = MsptSampler.DEFAULT_SECONDS;

	/** 是否已安装注入器（由 agent 启动时写入的系统属性）。 */
	public static final String INSTALLED_PROPERTY = "msptmap.agent.installed";

	private static volatile BiPredicate<UUID, byte[]> sender;
	private static volatile Consumer<Object[]> alertSink;
	private static volatile Supplier<UUID[]> audienceSupplier;
	private static volatile LongConsumer tickSink;

	private Bridge() {
	}

	/** 插件侧：发送一个结果包，返回玩家当前能否接收。 */
	public static void setSender(BiPredicate<UUID, byte[]> value) {
		sender = value;
	}

	/** 插件侧：接收自动扫描的聊天告警数据。 */
	public static void setAlertSink(Consumer<Object[]> value) {
		alertSink = value;
	}

	/** 插件侧：提供自动扫描的告警受众。 */
	public static void setAudienceSupplier(Supplier<UUID[]> value) {
		audienceSupplier = value;
	}

	/** 插件侧：每 tick 接收一次服务端 tick 的实际执行耗时（纳秒）。 */
	public static void setTickSink(LongConsumer value) {
		tickSink = value;
	}

	public static boolean isInstalled() {
		return Boolean.getBoolean(INSTALLED_PROPERTY);
	}

	/** 注入点：计时开始。 */
	public static long begin() {
		return MsptSampler.begin();
	}

	/** 注入点：计时结束并入账。 */
	public static void end(int category, Object level, long key, long start) {
		Nms.end(category, level, key, start);
	}

	/** 注入点：进入一层方块更新。 */
	public static long neighborBegin(Object level) {
		return Nms.neighborBegin(level);
	}

	/** 注入点：退出一层方块更新。 */
	public static void neighborEnd(Object level, long key, long start) {
		Nms.neighborEnd(level, key, start);
	}

	/** 注入点：服务端 tick 开始。 */
	public static void tickStart() {
		MsptSampler.onTickStart();
	}

	/** 注入点：服务端 tick 结束。 */
	public static void tickEnd() {
		long nanos = MsptSampler.onServerTick();
		LongConsumer sink = tickSink;
		if (nanos != 0L && sink != null) {
			sink.accept(nanos);
		}
	}

	/**
	 * 插件侧：开始一次扫描，返回 {@link #STARTED} 等结果码。
	 *
	 * <p>状态包由本方法直接回给发起人：START 里的秒数以服务端夹取后的值为准。
	 */
	public static int start(int seconds, UUID requester) {
		int clamped = MsptSampler.clampSeconds(seconds);
		MsptSampler.StartResult result = MsptSampler.start(clamped, requester);
		if (requester != null) {
			switch (result) {
				case STARTED -> send(requester, Wire.start(clamped));
				case BUSY, STALLED -> send(requester, Wire.busy());
				case COOLDOWN -> send(requester, Wire.cooldown());
			}
		}
		return result.ordinal();
	}

	/** 插件侧：开始一次自动扫描。 */
	public static int startAuto(int seconds) {
		return MsptSampler.startAuto(seconds).ordinal();
	}

	/** 插件侧：回复无权限。 */
	public static void sendDenied(UUID player) {
		send(player, Wire.denied());
	}

	/**
	 * 插件侧：向玩家声明服务端可接收的通道，供 Fabric 客户端判断能否发包。
	 */
	public static void advertiseChannels(UUID player, String[] channels) {
		Nms.advertiseChannels(player, String.join("\0", channels).getBytes(StandardCharsets.US_ASCII));
	}

	/** 插件侧：服务器停止时复位。 */
	public static void reset() {
		MsptSampler.reset();
		Nms.reset();
	}

	/**
	 * 发包；未安装发送器或玩家不可达时返回 false。
	 */
	static boolean send(UUID player, byte[] payload) {
		BiPredicate<UUID, byte[]> current = sender;
		if (current == null) {
			return false;
		}
		try {
			return current.test(player, payload);
		} catch (RuntimeException error) {
			Log.warn("发送扫描结果包失败：" + error);
			return false;
		}
	}

	/**
	 * 自动扫描的告警受众；未安装时为空。
	 */
	static UUID[] audience() {
		Supplier<UUID[]> current = audienceSupplier;
		if (current == null) {
			return new UUID[0];
		}
		try {
			UUID[] value = current.get();
			return value == null ? new UUID[0] : value;
		} catch (RuntimeException error) {
			Log.warn("获取扫描受众失败：" + error);
			return new UUID[0];
		}
	}

	/**
	 * 把告警数据交给插件；未安装时返回 false。
	 *
	 * <p>数组内容依次为：受众 UUID[]、维度 String[]、区块 X int[]、区块 Z int[]、总耗时 long[]、
	 * 窗口刻数 Integer。
	 */
	static boolean alert(UUID[] audience, List<MsptSampler.Heavy> rows, int windowTicks) {
		Consumer<Object[]> current = alertSink;
		if (current == null) {
			return false;
		}
		int size = rows.size();
		String[] dimensions = new String[size];
		int[] xs = new int[size];
		int[] zs = new int[size];
		long[] totals = new long[size];
		for (int i = 0; i < size; i++) {
			MsptSampler.Heavy row = rows.get(i);
			dimensions[i] = row.dimension();
			xs[i] = row.chunkX();
			zs[i] = row.chunkZ();
			totals[i] = row.totalNanos();
		}
		try {
			current.accept(new Object[] {audience, dimensions, xs, zs, totals, windowTicks});
			return true;
		} catch (RuntimeException error) {
			Log.warn("发送扫描告警失败：" + error);
			return false;
		}
	}
}
