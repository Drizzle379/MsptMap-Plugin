package msptmap.agent;

import java.util.Locale;

/**
 * 小数显示的唯一出口：固定 {@link Locale#ROOT}。
 */
public final class Decimals {
	private Decimals() {
	}

	/**
	 * 三位小数（mspt）。
	 */
	public static String format3(double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	/**
	 * 纳秒 → ms/tick；分母至少为 1，避免窗口刻数为 0 时除零。
	 */
	public static double mspt(long nanos, int windowTicks) {
		return nanos / 1_000_000.0 / Math.max(1, windowTicks);
	}

	/**
	 * 纳秒 → ms/tick 文本（三位小数）。
	 */
	public static String formatMspt(long nanos, int windowTicks) {
		return format3(mspt(nanos, windowTicks));
	}
}
