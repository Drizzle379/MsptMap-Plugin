package msptmap.agent;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 采样核心：逐区块累计耗时，开一个 N 秒的窗口，窗口结束出结果。
 *
 * <p>无后台线程，只有开关与数据表：未采样时注入点只读一个 boolean；采样时每个事件仅记两笔
 * （纳秒、次数），不执行除法，也不分配对象。本类不含玩家对象，发起人与结果接收者一律用 UUID，
 * 由插件侧负责解析与发包。
 */
public final class MsptSampler {
	/** 采样秒数上限。 */
	public static final int MAX_SECONDS = 60;

	/** 未指定秒数时的默认值。 */
	public static final int DEFAULT_SECONDS = 2;

	/** 每秒刻数，窗口长度与客户端进度圈共用此口径。 */
	public static final int TICKS_PER_SECOND = 20;

	/** 进度包发送间隔：2 刻 = 0.1 秒。 */
	private static final int PROGRESS_EVERY_TICKS = 2;

	/** 自动扫描告警里列出的区块行数。 */
	private static final int ALERT_ROWS = 5;

	/** 上一次扫描结束后至少间隔的现实秒数。收尾成本较高，需限流。 */
	public static final int COOLDOWN_SECONDS = 2;

	private static final long COOLDOWN_NANOS = COOLDOWN_SECONDS * 1_000_000_000L;

	/**
	 * 超过这么多现实秒没有服务端 tick 即视为服务端未运行（空载自动暂停 / 世界暂停 / 长时间卡顿）。
	 */
	private static final int STALL_SECONDS = 5;

	private static final long STALL_NANOS = STALL_SECONDS * 1_000_000_000L;

	/** 正在采样。热路径只读这一个字段。 */
	private static boolean sampling;

	/** 窗口内已过的服务端 tick 数：mspt 的分母。 */
	private static int windowTicks;

	/** 窗口内各 tick 耗时之和（纳秒）。 */
	private static long windowTickNanos;

	/** 本次窗口的秒数（已夹取）。 */
	private static int seconds;

	/** 本次扫描的发起人；控制台 / 自动扫描为 null。 */
	private static UUID requester;

	/** 本次结果是否广播给监控受众。 */
	private static boolean broadcast;

	/** 每个维度一张表：区块坐标 → 账本。 */
	private static final Map<ServerLevel, Long2ObjectOpenHashMap<ChunkTiming>> timings = new IdentityHashMap<>();

	/** 上次扫描结束的时刻，用于冷却；0 = 还没扫过。 */
	private static long lastEndNanos;

	/** 上一条记账的来源缓存。 */
	private static ServerLevel lastLevel;
	private static Long2ObjectOpenHashMap<ChunkTiming> lastChunks;
	private static long lastKey;
	private static ChunkTiming lastTiming;

	/** 上一次服务端 tick 的时刻；0 = 服务器还没有 tick 过。 */
	private static long lastTickNanos;

	/** 本 tick 的起始时刻；0 = 尚未记过。 */
	private static long tickStartNanos;

	/** 上一 tick 收尾、本 tick 才发的完成包。 */
	private static UUID pendingPlayer;
	private static byte[] pendingDone;

	private MsptSampler() {
	}

	/** 一次「开始」请求的结果。 */
	public enum StartResult {
		/** 窗口已开。 */
		STARTED,
		/** 已有扫描进行中。 */
		BUSY,
		/** 距上次扫描结束不足 {@link #COOLDOWN_SECONDS} 秒。 */
		COOLDOWN,
		/** 服务端当前未运行（见 {@link #stalled}）：窗口数不到刻，控制台请求直接拒绝。 */
		STALLED
	}

	/** 服务端是否未在运行：距上次 tick 超过 {@link #STALL_SECONDS} 秒。 */
	private static boolean stalled(long now) {
		return lastTickNanos != 0L && now - lastTickNanos > STALL_NANOS;
	}

	/** 将请求秒数夹取到合法区间。 */
	public static int clampSeconds(int seconds) {
		return Clamp.of(seconds, 1, MAX_SECONDS);
	}

	/**
	 * 开一个 N 秒的窗口。
	 *
	 * @param requester 发起人；null 表示控制台请求（结果打控制台）
	 */
	public static StartResult start(int seconds, UUID requester) {
		return startInternal(seconds, requester, false);
	}

	/**
	 * 开一个 N 秒的窗口，结果广播给监控受众。
	 */
	public static StartResult startAuto(int seconds) {
		return startInternal(seconds, null, true);
	}

	private static StartResult startInternal(int seconds, UUID requester, boolean broadcast) {
		long now = System.nanoTime();
		boolean stalled = stalled(now);
		if (sampling) {
			if (!stalled) {
				return StartResult.BUSY;
			}
			// 旧窗口已停滞（发起人掉线、世界暂停后未恢复）：作废并重新开启
			Log.info("上一个采样窗口已停滞（服务端 {0} 秒没有 tick），已作废",
					(now - lastTickNanos) / 1_000_000_000L);
			reset();
		}
		// 冷却自上次结束起算
		if (lastEndNanos != 0L && now - lastEndNanos < COOLDOWN_NANOS) {
			return StartResult.COOLDOWN;
		}
		// 服务端未运行：控制台取不到结果，直接拒绝；玩家请求照常开窗等待
		if (stalled && requester == null) {
			return StartResult.STALLED;
		}
		MsptSampler.requester = requester;
		MsptSampler.broadcast = broadcast;
		MsptSampler.seconds = clampSeconds(seconds);
		clearTimings();
		windowTicks = 0;
		windowTickNanos = 0L;
		sampling = true;
		return StartResult.STARTED;
	}

	/**
	 * 服务器停止：丢弃采样状态与表、复位冷却。
	 */
	public static void reset() {
		sampling = false;
		requester = null;
		broadcast = false;
		windowTicks = 0;
		windowTickNanos = 0L;
		pendingPlayer = null;
		pendingDone = null;
		clearTimings();
		lastEndNanos = 0L;
	}

	/** 每个服务端 tick 开始时调用一次。 */
	public static void onTickStart() {
		tickStartNanos = System.nanoTime();
	}

	/**
	 * 每个服务端 tick 结束时调用一次：返回本 tick 的执行耗时（纳秒），无起点时为 0。
	 *
	 * <p>进度与收尾逻辑同在此时执行；下一个 tick 再补发上一 tick 压住的完成包。
	 */
	public static long onServerTick() {
		long now = System.nanoTime();
		lastTickNanos = now;
		long tickNanos = tickStartNanos == 0L ? 0L : now - tickStartNanos;
		if (pendingDone != null) {
			sendPendingDone();
		}
		if (!sampling) {
			return tickNanos;
		}
		if (tickNanos != 0L) {
			windowTickNanos += tickNanos;
		}
		windowTicks++;
		if (windowTicks >= seconds * TICKS_PER_SECOND) {
			finish();
			return tickNanos;
		}
		sendProgress();
		return tickNanos;
	}

	/** 发上一 tick 压住的完成包；玩家已掉线则丢弃。 */
	private static void sendPendingDone() {
		UUID player = pendingPlayer;
		byte[] payload = pendingDone;
		pendingPlayer = null;
		pendingDone = null;
		if (!Bridge.send(player, payload)) {
			Log.info("扫描结果没送到：{0} 已不在线（窗口 {1} tick）", player, windowTicks);
		}
	}

	/** 将窗口已过的刻数推给发起人。 */
	private static void sendProgress() {
		if (windowTicks % PROGRESS_EVERY_TICKS != 0) {
			return;
		}
		UUID player = requester;
		if (player != null) {
			Bridge.send(player, Wire.progress(seconds, windowTicks));
		}
	}

	/** 计时开始。返回 0 表示未在采样。 */
	public static long begin() {
		return sampling ? System.nanoTime() : 0L;
	}

	/** 计时结束并入账。热路径：先走缓存，未命中才查表。 */
	public static void end(TickCategory category, ServerLevel level, long chunkKey, long startNanos) {
		if (startNanos == 0L) {
			return;
		}
		if (level != lastLevel) {
			lastChunks = timings.computeIfAbsent(level, key -> new Long2ObjectOpenHashMap<>());
			lastLevel = level;
			lastTiming = null;
		}
		ChunkTiming timing;
		if (chunkKey == lastKey && lastTiming != null) {
			timing = lastTiming;
		} else {
			timing = lastChunks.get(chunkKey);
			if (timing == null) {
				timing = new ChunkTiming();
				lastChunks.put(chunkKey, timing);
			}
			lastKey = chunkKey;
			lastTiming = timing;
		}
		timing.add(category, System.nanoTime() - startNanos);
	}

	/**
	 * 清表并作废来源缓存；三个清表入口一律走这里。
	 */
	private static void clearTimings() {
		timings.clear();
		lastLevel = null;
		lastChunks = null;
		lastTiming = null;
	}

	/**
	 * 收尾：结果发给发起人；自动扫描广播给监控受众；无法回发则输出到控制台。
	 */
	private static void finish() {
		sampling = false;
		long finishStartNanos = System.nanoTime();
		lastEndNanos = finishStartNanos;
		UUID player = requester;
		requester = null;
		boolean toAudience = broadcast;
		broadcast = false;
		try {
			if (player != null && Bridge.send(player, Wire.progress(seconds, windowTicks))) {
				// 进度画满后压到下一 tick 再发完成包
				pendingDone = Wire.done(seconds, windowTicks, windowTickNanos, SnapshotBuilder.build(timings));
				pendingPlayer = player;
			} else if (toAudience) {
				broadcastToAudience();
			} else {
				logTopChunks();
			}
		} catch (Throwable error) {
			// 收尾在服务端 tick 内执行：任何异常都不得冒泡，否则会打断整个 tick
			Log.warn("扫描收尾时出错：" + error);
		} finally {
			clearTimings();
		}
		Log.info("收尾耗时 {0} ms", (System.nanoTime() - finishStartNanos) / 1_000_000L);
	}

	/**
	 * 自动扫描收尾：热力数据发给能收包的受众，聊天告警交给插件。
	 */
	private static void broadcastToAudience() {
		UUID[] targets = Bridge.audience();
		if (targets.length == 0) {
			logTopChunks();
			return;
		}
		byte[] done = Wire.done(seconds, windowTicks, windowTickNanos, SnapshotBuilder.build(timings));
		int receivers = 0;
		for (UUID target : targets) {
			if (Bridge.send(target, done)) {
				receivers++;
			}
		}
		List<Heavy> rows = topRows(ALERT_ROWS);
		if (!Bridge.alert(targets, rows, windowTicks)) {
			logTopChunks();
		}
		Log.info("自动扫描结束：告警发给 {0} 名受众，热力数据发给其中 {1} 名", targets.length, receivers);
	}

	/**
	 * 窗口内最重的 n 个区块，跨维度降序；只收合计大于 0 的。
	 */
	private static List<Heavy> topRows(int n) {
		List<Row> rows = sortedRows();
		List<Heavy> heaviest = new ArrayList<>(n);
		for (Row row : rows) {
			if (heaviest.size() >= n) {
				break;
			}
			if (row.total <= 0L) {
				continue;
			}
			heaviest.add(new Heavy(Ids.id(row.level.dimension()), ChunkPos.getX(row.key),
					ChunkPos.getZ(row.key), row.total));
		}
		return heaviest;
	}

	/** 窗口内全部区块按重量降序。 */
	private static List<Row> sortedRows() {
		List<Row> rows = new ArrayList<>();
		timings.forEach((level, chunks) -> chunks.forEach(
				(key, timing) -> rows.add(new Row(level, key, timing, timing.totalNanos()))));
		rows.sort((a, b) -> Long.compare(b.total, a.total));
		return rows;
	}

	/** 一个卡顿区块：维度 ID、区块坐标与窗口内总耗时（纳秒）。 */
	public record Heavy(String dimension, int chunkX, int chunkZ, long totalNanos) {
	}

	/** 结果无法发回客户端时，将最重的若干区块输出到服务端控制台。 */
	private static void logTopChunks() {
		List<Row> rows = sortedRows();
		Log.info("扫描结束：{0} 个维度 / {1} 个区块，窗口 {2} 秒 / {3} tick",
				timings.size(), rows.size(), seconds, windowTicks);

		int printed = 0;
		for (Row row : rows) {
			if (printed >= ALERT_ROWS) {
				break;
			}
			if (row.total <= 0L) {
				continue;
			}
			printed++;
			Log.info("  #{0} {1} ({2}, {3})  {4} mspt [随机刻 {5} 计划刻 {6} 方块更新 {7} 方块事件 {8} "
							+ "方块实体 {9} 实体 {10} 刷怪 {11}]",
					printed,
					Ids.id(row.level.dimension()),
					ChunkPos.getX(row.key),
					ChunkPos.getZ(row.key),
					ms(row.total),
					ms(row.timing.nanos(TickCategory.RANDOM_TICK)),
					ms(row.timing.nanos(TickCategory.SCHEDULED)),
					ms(row.timing.nanos(TickCategory.NEIGHBOR_UPDATE)),
					ms(row.timing.nanos(TickCategory.BLOCK_EVENT)),
					ms(row.timing.nanos(TickCategory.BLOCK_ENTITY)),
					ms(row.timing.nanos(TickCategory.ENTITY)),
					ms(row.timing.nanos(TickCategory.SPAWN)));
		}
	}

	/** 纳秒 → mspt 文本，仅供打印。 */
	private static String ms(long nanos) {
		return Decimals.formatMspt(nanos, windowTicks);
	}

	/** 打印用的临时行。 */
	private record Row(ServerLevel level, long key, ChunkTiming timing, long total) {
	}
}
