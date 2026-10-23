package msptmap.agent;

/**
 * 数值夹取。
 */
public final class Clamp {
	private Clamp() {
	}

	public static int of(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
