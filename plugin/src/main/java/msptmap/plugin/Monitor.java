package msptmap.plugin;

import java.util.Locale;

/**
 * 常态 MSPT 监控：agent 每 tick 报一次整 tick 执行耗时，平滑后持续超标即触发一次自动扫描。
 *
 * <p>平滑窗口、评估间隔、去抖次数与自动扫描时长均为固定值；阈值、冷却与接收范围来自配置。
 * 所有方法只在服务端主线程调用。
 */
final class Monitor {
	enum State {
		OFF,
		OK,
		WATCHING,
		COOLDOWN
	}

	/** 平滑窗口（秒）。 */
	static final int WINDOW_SECONDS = 5;

	/** 自动扫描时长（秒）。 */
	static final int AUTO_SCAN_SECONDS = 5;

	/** 连续该次数的评估超标才触发。 */
	static final int CONSECUTIVE_CHECKS = 5;

	/** 评估间隔（刻）：每 1 秒判定一次。 */
	private static final int EVALUATE_TICKS = 20;

	/** 环形缓冲容量 = 窗口刻数。 */
	private static final int CAPACITY = WINDOW_SECONDS * 20;

	private final MsptMapPlugin plugin;
	private final Config config;

	private final long[] ring = new long[CAPACITY];
	private int ringIndex;
	private int ringCount;
	private long ringSum;

	private int sinceEvaluate;
	private int consecutiveChecks;
	private long cooldownUntilNanos;

	Monitor(MsptMapPlugin plugin, Config config) {
		this.plugin = plugin;
		this.config = config;
	}

	/** 服务器停止或监控关闭：丢弃全部状态。 */
	void reset() {
		ringIndex = 0;
		ringCount = 0;
		ringSum = 0L;
		sinceEvaluate = 0;
		consecutiveChecks = 0;
		cooldownUntilNanos = 0L;
	}

	/**
	 * 喂一个 tick 的实际耗时（纳秒）并决策。
	 */
	void onTick(long nanos) {
		long now = System.nanoTime();
		if (!config.monitorEnabled) {
			if (ringCount != 0) {
				reset();
			}
			return;
		}
		if (!observe(nanos, now)) {
			return;
		}
		// 无受众时扫描没有意义，也会占用采样窗口
		if (!plugin.hasAudience()) {
			return;
		}
		if (plugin.startAuto() == BridgeAccess.STARTED) {
			markTriggered(now);
		}
	}

	/**
	 * 推入一个样本，并在评估刻做一次决策；true 表示应当发起自动扫描。
	 */
	private boolean observe(long nanos, long now) {
		push(nanos);
		if (++sinceEvaluate < EVALUATE_TICKS) {
			return false;
		}
		sinceEvaluate = 0;
		// 窗口未填满时不判定：样本过少，均值不具代表性
		if (ringCount < CAPACITY) {
			consecutiveChecks = 0;
			return false;
		}
		if (meanMspt() <= config.threshold) {
			consecutiveChecks = 0;
			return false;
		}
		consecutiveChecks++;
		return consecutiveChecks >= CONSECUTIVE_CHECKS && now >= cooldownUntilNanos;
	}

	private void markTriggered(long now) {
		cooldownUntilNanos = now + config.cooldownMinutes * 60_000_000_000L;
		consecutiveChecks = 0;
	}

	private void push(long nanos) {
		if (ringCount == CAPACITY) {
			ringSum -= ring[ringIndex];
		} else {
			ringCount++;
		}
		ring[ringIndex] = nanos;
		ringSum += nanos;
		ringIndex = (ringIndex + 1) % CAPACITY;
	}

	/** 已收样本的平均 mspt；还没有样本时为 0。 */
	double meanMspt() {
		return ringCount == 0 ? 0.0 : ringSum / 1_000_000.0 / ringCount;
	}

	State state() {
		if (!config.monitorEnabled) {
			return State.OFF;
		}
		if (System.nanoTime() < cooldownUntilNanos) {
			return State.COOLDOWN;
		}
		return consecutiveChecks > 0 ? State.WATCHING : State.OK;
	}

	int consecutiveChecks() {
		return consecutiveChecks;
	}

	/** 冷却剩余秒数（向上取整）；不在冷却中时为 0。 */
	long cooldownRemainingSeconds() {
		long remaining = cooldownUntilNanos - System.nanoTime();
		return remaining <= 0 ? 0L : (remaining + 999_999_999L) / 1_000_000_000L;
	}

	/** 状态显示名。 */
	String stateName() {
		return state().name().toLowerCase(Locale.ROOT);
	}
}
