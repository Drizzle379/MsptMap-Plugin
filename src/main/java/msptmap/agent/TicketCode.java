package msptmap.agent;

/**
 * 加载票来源的线格式：类型序号、源头偏移与存疑位打包进一个 int。
 *
 * <p>位布局：0~3 位类型，4~10 位与 11~17 位是源头相对本区块的偏移，18 位存疑。
 */
public final class TicketCode {
	public static final int NONE = 0;

	public static final int PLAYER_LOADING = 1;
	public static final int PLAYER_SIMULATION = 2;
	public static final int FORCED = 3;
	public static final int PORTAL = 4;
	public static final int ENDER_PEARL = 5;
	public static final int PLAYER_SPAWN = 6;
	public static final int SPAWN_SEARCH = 7;
	public static final int DRAGON = 8;
	public static final int UNKNOWN = 9;
	public static final int UNRECOGNIZED = 10;

	private static final int TYPE_MASK = 0xF;
	private static final int OFFSET_BIAS = 64;
	private static final int OFFSET_MASK = 0x7F;
	private static final int DX_SHIFT = 4;
	private static final int DZ_SHIFT = 11;
	private static final int DOUBTFUL_BIT = 1 << 18;

	private TicketCode() {
	}

	public static int encode(int type, int offsetX, int offsetZ, boolean doubtful) {
		return (type & TYPE_MASK)
				| (((offsetX + OFFSET_BIAS) & OFFSET_MASK) << DX_SHIFT)
				| (((offsetZ + OFFSET_BIAS) & OFFSET_MASK) << DZ_SHIFT)
				| (doubtful ? DOUBTFUL_BIT : 0);
	}

	public static int type(int code) {
		return code & TYPE_MASK;
	}

	public static int offsetX(int code) {
		return ((code >> DX_SHIFT) & OFFSET_MASK) - OFFSET_BIAS;
	}

	public static int offsetZ(int code) {
		return ((code >> DZ_SHIFT) & OFFSET_MASK) - OFFSET_BIAS;
	}

	public static boolean isCenter(int code) {
		int type = type(code);
		return type != NONE && type != PLAYER_LOADING
				&& offsetX(code) == 0 && offsetZ(code) == 0;
	}

	/**
	 * 等级相同时的取舍顺序：数值越小越优先。
	 */
	public static int priority(int type) {
		return switch (type) {
			case FORCED -> 0;
			case ENDER_PEARL -> 1;
			case PORTAL -> 2;
			case DRAGON -> 3;
			case PLAYER_SPAWN -> 4;
			case SPAWN_SEARCH -> 5;
			case PLAYER_SIMULATION -> 6;
			case PLAYER_LOADING -> 7;
			default -> 8;
		};
	}
}
