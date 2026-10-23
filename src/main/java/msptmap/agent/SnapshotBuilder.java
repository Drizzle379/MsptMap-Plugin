package msptmap.agent;

import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 快照构建：把采样表转换成可发送的 {@link SnapshotCodec.DimensionData} 列表。
 *
 * <p>等级、加载来源与实体数均为取样时刻的瞬时值，故整段只在窗口收尾时同步执行一次。
 */
public final class SnapshotBuilder {
	/**
	 * 一次扫描所有维度合计的字节预算：原版自定义包上限 1MB，此处留约三成余量。
	 */
	public static final int MAX_SNAPSHOT_BYTES = 700 * 1024;

	/**
	 * 「仅加载」区块共用的全零数组。
	 */
	private static final long[] ZERO_NANOS = new long[TickCategory.COUNT];
	private static final int[] ZERO_COUNTS = new int[TickCategory.COUNT];

	private SnapshotBuilder() {
	}

	/**
	 * 将采样表转换为可发送的快照；字节预算按维度平分。
	 */
	static List<SnapshotCodec.DimensionData> build(
			Map<ServerLevel, Long2ObjectOpenHashMap<ChunkTiming>> timings) {
		List<SnapshotCodec.DimensionData> dimensions = new ArrayList<>(timings.size());
		int share = MAX_SNAPSHOT_BYTES / Math.max(1, timings.size());
		timings.forEach((level, chunks) -> dimensions.add(snapshotDimension(level, chunks, share)));
		return dimensions;
	}

	private static SnapshotCodec.DimensionData snapshotDimension(ServerLevel level,
			Long2ObjectOpenHashMap<ChunkTiming> chunks, int budget) {
		ChunkMap chunkMap = level.getChunkSource().chunkMap;
		DistanceManager distanceManager = chunkMap.getDistanceManager();
		List<NewChunkHolder> holders = distanceManager.moonrise$getChunkHolderManager().getChunkHolders();
		LongOpenHashSet keys = holderKeys(holders);
		Long2IntOpenHashMap entityCounts = entityCounts(level);
		// 两条链各推一遍：同一个区块上，加载票与模拟票可能不是同一张
		Long2IntOpenHashMap loadTickets = TicketSources.resolve(level, false, keys);
		Long2IntOpenHashMap simTickets = TicketSources.resolve(level, true, keys);
		TicketSources.markPlayerSimulation(level, simTickets);
		LongOpenHashSet measured = new LongOpenHashSet(chunks.size());
		List<SnapshotCodec.ChunkData> out = new ArrayList<>(chunks.size());
		chunks.forEach((key, timing) -> {
			measured.add(key);
			int loadLevel = distanceManager.getChunkLevel(key, false);
			int computeLevel = distanceManager.getChunkLevel(key, true);
			out.add(chunkData(key, timing.nanosArray(), timing.countsArray(), loadLevel, computeLevel,
					entityCounts, loadTickets, simTickets));
		});
		// 按重量降序：装不下时移除的必为尾部最轻的
		List<Weighted> weighted = new ArrayList<>(out.size());
		for (SnapshotCodec.ChunkData chunk : out) {
			weighted.add(new Weighted(chunk, chunk.totalNanos()));
		}
		weighted.sort(Comparator.comparingLong(Weighted::total).reversed());
		for (int i = 0; i < out.size(); i++) {
			out.set(i, weighted.get(i).chunk());
		}
		int used = SnapshotCodec.fitToBudget(out, budget);
		if (out.size() < chunks.size()) {
			Log.info("维度 {0} 超出字节预算：{1} 个执行过计时的区块没带上（最轻的那些）",
					Ids.id(level.dimension()), chunks.size() - out.size());
		}
		// 已计时区块处理完毕后，再补「加载着、窗口内无计时」的那些
		addLoadedChunks(level, distanceManager, holders, measured, entityCounts, loadTickets, simTickets, out,
				budget - used);
		return new SnapshotCodec.DimensionData(Ids.id(level.dimension()), out);
	}

	/**
	 * 全部区块句柄的键集合。Moonrise 取代了原版的 visibleChunkMap / updatingChunkMap，
	 * 将加载中的区块统一挂在 ChunkHolderManager 上。
	 */
	private static LongOpenHashSet holderKeys(List<NewChunkHolder> holders) {
		LongOpenHashSet keys = new LongOpenHashSet(Math.max(16, holders.size()));
		for (NewChunkHolder holder : holders) {
			keys.add(ChunkKeys.pack(holder.chunkX, holder.chunkZ));
		}
		return keys;
	}

	/**
	 * 该区块在某条链上的加载来源。表里没有它时，以「该链是否本该覆盖本区块」区分。
	 */
	private static int ticketCode(Long2IntOpenHashMap sources, long key, boolean expected) {
		int code = sources.get(key);
		// 表里不存 NONE（0），所以取出 0 即代表没有
		return code == 0 ? TicketCode.encode(TicketCode.NONE, 0, 0, expected) : code;
	}

	/**
	 * 生成快照时每个区块的实体数（含乘客）。
	 */
	private static Long2IntOpenHashMap entityCounts(ServerLevel level) {
		Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
		for (Entity entity : level.getAllEntities()) {
			if (!entity.isRemoved()) {
				counts.addTo(ChunkKeys.pack(entity), 1);
			}
		}
		return counts;
	}

	/**
	 * 把「加载着但窗口内无计时」的区块补进快照，耗时与次数全填 0。
	 *
	 * <p>中心区块先补、不计预算；其余只收加载等级 ≤ 32 的。
	 */
	private static void addLoadedChunks(ServerLevel level, DistanceManager distanceManager,
			List<NewChunkHolder> holders, LongOpenHashSet measured, Long2IntOpenHashMap entityCounts,
			Long2IntOpenHashMap loadTickets, Long2IntOpenHashMap simTickets, List<SnapshotCodec.ChunkData> out,
			int budget) {
		LongOpenHashSet included = new LongOpenHashSet(Math.max(16, out.size()));
		for (SnapshotCodec.ChunkData chunk : out) {
			included.add(ChunkKeys.pack(chunk.x(), chunk.z()));
		}
		// 一、先补两条链的中心：每张票一个、数量少，不计预算
		for (Long2IntOpenHashMap tickets : new Long2IntOpenHashMap[] {loadTickets, simTickets}) {
			for (Long2IntMap.Entry entry : tickets.long2IntEntrySet()) {
				long key = entry.getLongKey();
				if (TicketCode.isCenter(entry.getIntValue()) && included.add(key)) {
					out.add(loadedChunk(key, distanceManager.getChunkLevel(key, false),
							distanceManager.getChunkLevel(key, true), entityCounts, loadTickets, simTickets));
				}
			}
		}
		// 二、其余「加载着、无计时」的区块
		int used = 0;
		boolean full = false;
		for (NewChunkHolder holder : holders) {
			long key = ChunkKeys.pack(holder.chunkX, holder.chunkZ);
			if (measured.contains(key) || included.contains(key)) {
				continue;
			}
			int loadLevel = distanceManager.getChunkLevel(key, false);
			if (!ChunkLevel.isBlockTicking(loadLevel)) {
				continue;
			}
			int computeLevel = distanceManager.getChunkLevel(key, true);
			SnapshotCodec.ChunkData chunk = loadedChunk(key, loadLevel, computeLevel,
					entityCounts, loadTickets, simTickets);
			int size = SnapshotCodec.encodedSize(chunk);
			if (used + size > budget) {
				full = true;
				break;
			}
			used += size;
			out.add(chunk);
		}
		if (full) {
			Log.info("维度 {0} 超出字节预算：一部分只加载着的区块没带上", Ids.id(level.dimension()));
		}
	}

	/**
	 * 「仅加载、无计时」形态的区块数据。
	 */
	private static SnapshotCodec.ChunkData loadedChunk(long key, int loadLevel, int computeLevel,
			Long2IntOpenHashMap entityCounts, Long2IntOpenHashMap loadTickets, Long2IntOpenHashMap simTickets) {
		return chunkData(key, ZERO_NANOS, ZERO_COUNTS,
				loadLevel, computeLevel, entityCounts, loadTickets, simTickets);
	}

	/**
	 * 组装一个区块的数据；实测区块与仅加载区块共用。
	 */
	private static SnapshotCodec.ChunkData chunkData(long key, long[] nanos, int[] counts,
			int loadLevel, int computeLevel, Long2IntOpenHashMap entityCounts,
			Long2IntOpenHashMap loadTickets, Long2IntOpenHashMap simTickets) {
		return new SnapshotCodec.ChunkData(ChunkPos.getX(key), ChunkPos.getZ(key), nanos, counts,
				entityCounts.get(key), loadLevel, computeLevel,
				ticketCode(loadTickets, key, ChunkLevel.isBlockTicking(loadLevel)),
				ticketCode(simTickets, key, ChunkLevel.isBlockTicking(computeLevel)));
	}

	/**
	 * 排序用的临时行：区块连同它的重量。
	 */
	private record Weighted(SnapshotCodec.ChunkData chunk, long total) {
	}
}
