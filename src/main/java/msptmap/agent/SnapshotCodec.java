package msptmap.agent;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/**
 * 快照的字节格式，与 Fabric 版逐字节一致。
 *
 * <p>只保留服务端需要的写入与尺寸计算；解码在客户端完成。
 */
public final class SnapshotCodec {
	private SnapshotCodec() {
	}

	/**
	 * 单个区块的全部数据。数组下标 = {@link TickCategory#ordinal()}。
	 */
	public record ChunkData(int x, int z, long[] nanos, int[] counts, int entities, int loadLevel, int computeLevel,
			int loadTicket, int simTicket) {
		public long totalNanos() {
			return TickCategory.totalNanos(nanos);
		}
	}

	/**
	 * 一个维度的一批区块。
	 */
	public record DimensionData(String dimension, List<ChunkData> chunks) {
	}

	/**
	 * {@link #encodedSize} 复用的缓冲。仅服务端主线程使用。
	 */
	private static final FriendlyByteBuf SCRATCH = new FriendlyByteBuf(Unpooled.buffer(64));

	public static void writeChunk(FriendlyByteBuf buf, ChunkData chunk) {
		buf.writeVarInt(chunk.x());
		buf.writeVarInt(chunk.z());
		for (int i = 0; i < TickCategory.COUNT; i++) {
			buf.writeVarLong(chunk.nanos()[i]);
			buf.writeVarInt(chunk.counts()[i]);
		}
		buf.writeVarInt(chunk.entities());
		buf.writeVarInt(chunk.loadLevel());
		buf.writeVarInt(chunk.computeLevel());
		buf.writeVarInt(chunk.loadTicket());
		buf.writeVarInt(chunk.simTicket());
	}

	public static void writeDimension(FriendlyByteBuf buf, DimensionData dimension) {
		buf.writeUtf(dimension.dimension());
		List<ChunkData> chunks = dimension.chunks();
		buf.writeVarInt(chunks.size());
		for (ChunkData chunk : chunks) {
			writeChunk(buf, chunk);
		}
	}

	/**
	 * 一次扫描的全部维度；调用方须先写入 {@code dimensionCount} 之外的包体头部字段。
	 */
	public static void writeDimensions(FriendlyByteBuf buf, List<DimensionData> dimensions) {
		buf.writeVarInt(dimensions.size());
		for (DimensionData dimension : dimensions) {
			writeDimension(buf, dimension);
		}
	}

	/**
	 * 计算单个区块编码后的字节数。以真实写入方法写入临时缓冲后取长度。
	 */
	public static int encodedSize(ChunkData chunk) {
		SCRATCH.clear();
		writeChunk(SCRATCH, chunk);
		return SCRATCH.readableBytes();
	}

	/**
	 * 按字节预算裁剪区块列表（就地移除尾部放不下的部分），返回保留部分占用的字节数。
	 */
	public static int fitToBudget(List<ChunkData> chunks, int budgetBytes) {
		int used = 0;
		for (int i = 0; i < chunks.size(); i++) {
			int size = encodedSize(chunks.get(i));
			if (used + size > budgetBytes) {
				chunks.subList(i, chunks.size()).clear();
				return used;
			}
			used += size;
		}
		return used;
	}
}
