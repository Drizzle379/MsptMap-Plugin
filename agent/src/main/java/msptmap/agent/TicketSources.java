package msptmap.agent;

import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import java.util.function.LongToIntFunction;

/**
 * 反查每个区块的加载来源：即该区块被哪张加载票覆盖。
 *
 * <p>票只落在锚点区块上，周围区块靠等级传播覆盖。从锚点向外逐层扩散，邻居的实际等级恰等于本票的
 * 传播值时，本票即为覆盖它的（并列）最强来源。加载链与模拟链是两条独立的传播链，须各推一遍。
 *
 * <p>结果按 {@link TicketCode} 的线格式编码。
 */
public final class TicketSources {
	private TicketSources() {
	}

	/**
	 * 算出该维度、该条链上每个区块的来源。
	 *
	 * @param simulation false 走加载链，true 走模拟链
	 * @param keys       可能要认作持票区块的区块集合（该维度的全部区块句柄）
	 * @return 区块坐标 → 编码后的来源；该链一条票都没覆盖到的区块不在表里
	 */
	public static Long2IntOpenHashMap resolve(ServerLevel level, boolean simulation, LongSet keys) {
		DistanceManager distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
		return resolve(distanceManager.ticketStorage::getTickets,
				key -> distanceManager.getChunkLevel(key, simulation),
				keys, simulation, Ids.id(level.dimension()));
	}

	/**
	 * BFS 本体：数据源由调用方给出，采集与算法分离。
	 *
	 * @param ticketsAt 区块 → 挂在该区块上的全部票
	 * @param levelAt   区块 → 该链上传播后的实际等级
	 * @param keys      可能要认作持票区块的区块集合
	 * @param simulation false 走加载链，true 走模拟链
	 * @param dimension 日志用的维度名
	 */
	static Long2IntOpenHashMap resolve(LongFunction<List<Ticket>> ticketsAt, LongToIntFunction levelAt,
			LongSet keys, boolean simulation, String dimension) {
		Long2IntOpenHashMap sources = new Long2IntOpenHashMap();
		int capacity = Math.max(16, keys.size());
		long[] queueKeys = new long[capacity];
		int[] queueSpread = new int[capacity];
		int[] queueAnchorXs = new int[capacity];
		int[] queueAnchorZs = new int[capacity];
		int queueHead = 0;
		int queueTail = 0;

		// 一、持票区块：持有本链的票，且等级落在快照范围内
		int anchors = 0;
		for (LongIterator it = keys.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int[] anchor = anchorAt(ticketsAt.apply(key), simulation);
			if (anchor == null || !ChunkLevel.isBlockTicking(anchor[1])) {
				continue;
			}
			int x = ChunkPos.getX(key);
			int z = ChunkPos.getZ(key);
			sources.put(key, TicketCode.encode(anchor[0], 0, 0, false));
			queueKeys[queueTail] = key;
			queueSpread[queueTail] = anchor[1];
			queueAnchorXs[queueTail] = x;
			queueAnchorZs[queueTail] = z;
			queueTail++;
			anchors++;
		}

		// 二、按 8 邻扩散，只往实际等级恰好等于本票传播值的邻居走
		while (queueHead < queueTail) {
			long key = queueKeys[queueHead];
			int spread = queueSpread[queueHead];
			int anchorX = queueAnchorXs[queueHead];
			int anchorZ = queueAnchorZs[queueHead];
			queueHead++;
			int type = TicketCode.type(sources.get(key));
			int x = ChunkPos.getX(key);
			int z = ChunkPos.getZ(key);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					long neighbor = ChunkKeys.pack(x + dx, z + dz);
					if (sources.containsKey(neighbor)) {
						continue;
					}
					int neighborLevel = levelAt.applyAsInt(neighbor);
					if (neighborLevel != spread + 1 || !ChunkLevel.isBlockTicking(neighborLevel)) {
						continue;
					}
					sources.put(neighbor, TicketCode.encode(type, anchorX - (x + dx), anchorZ - (z + dz), false));
					if (queueTail == queueKeys.length) {
						int grown = queueTail * 2;
						queueKeys = Arrays.copyOf(queueKeys, grown);
						queueSpread = Arrays.copyOf(queueSpread, grown);
						queueAnchorXs = Arrays.copyOf(queueAnchorXs, grown);
						queueAnchorZs = Arrays.copyOf(queueAnchorZs, grown);
					}
					queueKeys[queueTail] = neighbor;
					queueSpread[queueTail] = spread + 1;
					queueAnchorXs[queueTail] = anchorX;
					queueAnchorZs[queueTail] = anchorZ;
					queueTail++;
				}
			}
		}

		Log.debug("维度 {0} 加载票（{1}）：持票区块 {2} 个、覆盖 {3} 个区块",
				dimension, simulation ? "模拟链" : "加载链", anchors, sources.size());
		return sources;
	}

	/**
	 * 把模拟链上玩家票覆盖的区块改写为「玩家模拟」。
	 *
	 * <p>原版的 player_simulation 是玩家所在区块的一张稀疏票，客户端据此标出中心并显示 @偏移；
	 * Paper 的玩家票则逐区块铺设，若原样上报，模拟链上只会得到一片「玩家加载」。此处按最近玩家归属，
	 * 把每个区块改写为以该玩家为中心的玩家模拟票，偏移为其相对本区块的位置。
	 *
	 * <p>只改类型为 {@link TicketCode#PLAYER_LOADING} 的条目：更强的来源（强制加载、传送门等）已在
	 * {@link #anchorAt} 中胜出，保持原样。
	 */
	static void markPlayerSimulation(ServerLevel level, Long2IntOpenHashMap simTickets) {
		List<ServerPlayer> players = level.players();
		if (players.isEmpty()) {
			return;
		}
		for (Long2IntMap.Entry entry : simTickets.long2IntEntrySet()) {
			if (TicketCode.type(entry.getIntValue()) != TicketCode.PLAYER_LOADING) {
				continue;
			}
			int x = ChunkPos.getX(entry.getLongKey());
			int z = ChunkPos.getZ(entry.getLongKey());
			int centerX = x;
			int centerZ = z;
			int best = Integer.MAX_VALUE;
			for (ServerPlayer player : players) {
				long center = ChunkKeys.pack(player);
				int playerX = ChunkPos.getX(center);
				int playerZ = ChunkPos.getZ(center);
				int distance = Math.max(Math.abs(playerX - x), Math.abs(playerZ - z));
				if (distance < best) {
					best = distance;
					centerX = playerX;
					centerZ = playerZ;
				}
			}
			entry.setValue(TicketCode.encode(TicketCode.PLAYER_SIMULATION, centerX - x, centerZ - z, false));
		}
	}

	/**
	 * 该区块在本链上的持票信息：{类型序号, 票等级}，无票返回 null。
	 */
	private static int[] anchorAt(List<Ticket> tickets, boolean simulation) {
		if (tickets == null) {
			return null;
		}
		int bestLevel = Integer.MAX_VALUE;
		int bestType = TicketCode.NONE;
		for (Ticket ticket : tickets) {
			if (!belongsTo(ticket.getType(), simulation)) {
				continue;
			}
			int level = ticket.getTicketLevel();
			int type = indexOf(ticket.getType());
			boolean better = level < bestLevel
					|| (level == bestLevel && TicketCode.priority(type) < TicketCode.priority(bestType));
			if (better) {
				bestLevel = level;
				bestType = type;
			}
		}
		return bestType == TicketCode.NONE ? null : new int[] {bestType, bestLevel};
	}

	/**
	 * 这张票参不参与本条链：加载链看 doesLoad，模拟链看 doesSimulate。
	 */
	private static boolean belongsTo(TicketType type, boolean simulation) {
		return simulation ? type.doesSimulate() : type.doesLoad();
	}

	/**
	 * 票类型身份 → 协议序号的缓存。
	 */
	private static final Map<TicketType, Integer> TYPE_CACHE = new IdentityHashMap<>();

	private static int indexOf(TicketType type) {
		Integer cached = TYPE_CACHE.get(type);
		if (cached != null) {
			return cached;
		}
		int index = computeIndexOf(type);
		TYPE_CACHE.put(type, index);
		return index;
	}

	/**
	 * 票类型 → 协议序号。识别依据为注册表中的名字，而非 {@code equals}。
	 *
	 * <p>Paper 的 Moonrise 用两张私有票加载玩家视距内的区块：它们不进
	 * {@code BuiltInRegistries.TICKET_TYPE}，按名字查不到，且逐区块铺设、同时参与加载链与模拟链，
	 * 语义等同原版的 {@code player_loading}。
	 */
	private static int computeIndexOf(TicketType type) {
		if (type == RegionizedPlayerChunkLoader.PLAYER_TICKET
				|| type == RegionizedPlayerChunkLoader.PLAYER_TICKET_DELAYED) {
			return TicketCode.PLAYER_LOADING;
		}
		String path = Ids.path(BuiltInRegistries.TICKET_TYPE, type);
		if (path == null) {
			return TicketCode.UNRECOGNIZED;
		}
		return switch (path) {
			case "player_loading" -> TicketCode.PLAYER_LOADING;
			case "player_simulation" -> TicketCode.PLAYER_SIMULATION;
			case "forced" -> TicketCode.FORCED;
			case "portal" -> TicketCode.PORTAL;
			case "ender_pearl" -> TicketCode.ENDER_PEARL;
			case "start" -> TicketCode.PLAYER_SPAWN;
			case "player_spawn" -> TicketCode.PLAYER_SPAWN;
			case "spawn_search" -> TicketCode.SPAWN_SEARCH;
			case "dragon" -> TicketCode.DRAGON;
			case "unknown" -> TicketCode.UNKNOWN;
			default -> TicketCode.UNRECOGNIZED;
		};
	}
}
