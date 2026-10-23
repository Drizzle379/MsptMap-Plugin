package msptmap.agent;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;

/**
 * 结果包的字节编码，与 Fabric 版 {@code ScanResultPayload} 逐字节一致。
 *
 * <p>六个状态共用一种包体：魔数、状态序号、窗口秒数、已过刻数、整 tick 纳秒，再接区块数据。
 * 状态序号即客户端 {@code Status} 的 ordinal，顺序不可改动。
 */
public final class Wire {
	/** 协议魔数，与客户端一致。 */
	public static final int PROTOCOL = 0x4D535034;

	public static final int START = 0;
	public static final int DONE = 1;
	public static final int DENIED = 2;
	public static final int BUSY = 3;
	public static final int PROGRESS = 4;
	public static final int COOLDOWN = 5;

	private Wire() {
	}

	public static byte[] start(int seconds) {
		return encode(START, seconds, 0, 0L, List.of());
	}

	public static byte[] done(int seconds, int windowTicks, long tickNanos,
			List<SnapshotCodec.DimensionData> dimensions) {
		return encode(DONE, seconds, windowTicks, tickNanos, dimensions);
	}

	public static byte[] progress(int seconds, int windowTicks) {
		return encode(PROGRESS, seconds, windowTicks, 0L, List.of());
	}

	public static byte[] denied() {
		return encode(DENIED, 0, 0, 0L, List.of());
	}

	public static byte[] busy() {
		return encode(BUSY, 0, 0, 0L, List.of());
	}

	public static byte[] cooldown() {
		return encode(COOLDOWN, 0, 0, 0L, List.of());
	}

	private static byte[] encode(int status, int seconds, int windowTicks, long tickNanos,
			List<SnapshotCodec.DimensionData> dimensions) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		try {
			buf.writeVarInt(PROTOCOL);
			buf.writeVarInt(status);
			buf.writeVarInt(seconds);
			buf.writeVarInt(windowTicks);
			buf.writeVarLong(tickNanos);
			SnapshotCodec.writeDimensions(buf, dimensions);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return bytes;
		} finally {
			buf.release();
		}
	}
}
