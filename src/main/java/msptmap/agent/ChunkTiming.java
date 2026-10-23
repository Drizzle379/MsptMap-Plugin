package msptmap.agent;

/**
 * 单个区块的采样账本：按类别累计耗时（纳秒）与调用次数。
 */
final class ChunkTiming {
	private final long[] nanos = new long[TickCategory.COUNT];
	private final int[] counts = new int[TickCategory.COUNT];

	/**
	 * 记一笔。热路径，不得分配对象或执行除法。
	 */
	public void add(TickCategory category, long durationNanos) {
		int index = category.ordinal();
		this.nanos[index] += durationNanos;
		this.counts[index]++;
	}

	public long totalNanos() {
		return TickCategory.totalNanos(this.nanos);
	}

	public long nanos(TickCategory category) {
		return this.nanos[category.ordinal()];
	}

	public long[] nanosArray() {
		return this.nanos;
	}

	public int[] countsArray() {
		return this.counts;
	}
}
