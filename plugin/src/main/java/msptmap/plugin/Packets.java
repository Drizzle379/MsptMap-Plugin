package msptmap.plugin;

import java.nio.charset.StandardCharsets;

/**
 * 客户端包体的最小读取器：LEB128 VarInt 与「长度 + UTF-8」字符串。
 *
 * <p>插件侧不引用 NMS 的 {@code FriendlyByteBuf}，也不额外依赖 Netty；请求包只有这两个字段。
 * 任何越界或超长都按格式错误抛出，由调用方记录并忽略。
 */
final class Packets {
	private final byte[] data;
	private int position;

	Packets(byte[] data) {
		this.data = data;
	}

	int readVarInt() {
		int value = 0;
		for (int shift = 0; shift < 32; shift += 7) {
			if (position >= data.length) {
				throw new IllegalArgumentException("VarInt 不完整");
			}
			byte current = data[position++];
			value |= (current & 0x7F) << shift;
			if ((current & 0x80) == 0) {
				return value;
			}
		}
		throw new IllegalArgumentException("VarInt 过长");
	}

	/**
	 * 读取 {@code writeUtf} 写入的字符串：VarInt 字节长度 + UTF-8。
	 */
	String readUtf() {
		int length = readVarInt();
		if (length < 0 || length > 32767 || position + length > data.length) {
			throw new IllegalArgumentException("字符串长度非法：" + length);
		}
		String value = new String(data, position, length, StandardCharsets.UTF_8);
		position += length;
		return value;
	}
}
