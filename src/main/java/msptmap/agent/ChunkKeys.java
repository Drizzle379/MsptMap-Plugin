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
//? if >=26.1 {
		return pos.pack();
//?} else {
/*		return pos.toLong();
*///?}
	}

	public static long pack(int x, int z) {
//? if >=26.1 {
		return ChunkPos.pack(x, z);
//?} else {
/*		return ChunkPos.asLong(x, z);
*///?}
	}

	public static long pack(BlockPos pos) {
//? if >=26.1 {
		return ChunkPos.pack(pos);
//?} else {
/*		return ChunkPos.asLong(pos);
*///?}
	}

	/**
	 * 实体所在区块。直接用已取整的方块坐标，不构造 ChunkPos。
	 */
	public static long pack(Entity entity) {
		return pack(entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
	}
}
