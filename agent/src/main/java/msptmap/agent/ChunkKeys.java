package msptmap.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * 区块坐标打包成 long。
 */
public final class ChunkKeys {
	private ChunkKeys() {
	}

	public static long pack(ChunkPos pos) {
		return pos.pack();
	}

	public static long pack(int x, int z) {
		return ChunkPos.pack(x, z);
	}

	public static long pack(BlockPos pos) {
		return ChunkPos.pack(pos);
	}

	/**
	 * 实体所在区块。直接用已取整的方块坐标，不构造 ChunkPos。
	 */
	public static long pack(Entity entity) {
		return pack(entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
	}
}
