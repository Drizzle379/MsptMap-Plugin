package msptmap.agent;

/**
 * 计入耗时的七类工作。
 *
 * <p>顺序不可变更：{@link ChunkTiming} 的数组按下标（ordinal）存储，快照也按此顺序上线；
 * 新增类别只能追加在末尾。
 */
public enum TickCategory {
	/** 随机刻：{@code ServerLevel.tickChunk} 整个方法的耗时。 */
	RANDOM_TICK,
	/** 计划刻：方块与流体。 */
	SCHEDULED,
	/** 方块实体：{@code LevelChunk$BoundTickingBlockEntity.tick()}。 */
	BLOCK_ENTITY,
	/** 实体：tickNonPassenger、实体追踪与 ServerEntity.sendChanges。 */
	ENTITY,
	/** 刷怪：{@code NaturalSpawner.spawnForChunk}。 */
	SPAWN,
	/** 方块更新：邻居更新各入口与形状更新；累加合计时不计入。 */
	NEIGHBOR_UPDATE,
	/** 方块事件：{@code ServerLevel.doBlockEvent}。 */
	BLOCK_EVENT;

	/**
	 * 按序号取类别；值表只克隆一次。
	 */
	private static final TickCategory[] VALUES = values();

	/**
	 * 类别数。以它代替 {@code values().length}：后者每次调用均克隆一份数组。
	 */
	public static final int COUNT = VALUES.length;

	public static TickCategory byIndex(int index) {
		return VALUES[index];
	}

	/**
	 * 合计：各类耗时之和（纳秒），不计方块更新。
	 */
	public static long totalNanos(long[] nanos) {
		long total = 0L;
		for (int i = 0; i < COUNT; i++) {
			if (i != NEIGHBOR_UPDATE.ordinal()) {
				total += nanos[i];
			}
		}
		return total;
	}
}
