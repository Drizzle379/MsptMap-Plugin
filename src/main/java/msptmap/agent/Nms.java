package msptmap.agent;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.UUID;

/**
 * 注入代码与采样器之间的 NMS 适配层。
 *
 * <p>{@link Bridge} 只暴露 JDK 类型的签名，凡是要接触游戏类的工作都落在本类：注入点把
 * 对象原样传进来，由这里完成类型转换、取维度、取坐标与「是否服务端」的判断。
 */
public final class Nms {
	/**
	 * 邻居更新与形状更新共用的嵌套层数（主线程，跨维度不交错）。
	 */
	private static int neighborDepth;

	private Nms() {
	}

	public static long keyChunk(Object chunk) {
		return ChunkKeys.pack(((LevelChunk) chunk).getPos());
	}

	public static long keyPos(Object pos) {
		return ChunkKeys.pack((BlockPos) pos);
	}

	public static long keyEntity(Object entity) {
		return ChunkKeys.pack((Entity) entity);
	}

	public static long keyBlockEntity(Object blockEntity) {
		return ChunkKeys.pack(((BlockEntity) blockEntity).getBlockPos());
	}

	public static long keyBlockEvent(Object event) {
		return ChunkKeys.pack(((BlockEventData) event).pos());
	}

	public static Object entityLevel(Object entity) {
		return ((Entity) entity).level();
	}

	public static Object blockEntityLevel(Object blockEntity) {
		return ((BlockEntity) blockEntity).getLevel();
	}

	/**
	 * 向玩家发送一份 {@code minecraft:register}，声明服务端可接收的通道。
	 *
	 * <p>Fabric 客户端只根据服务端声明决定能否发包；直接经连接发送，不走 Bukkit 的插件消息
	 * （该通道未注册，Bukkit 会静默丢弃）。
	 */
	public static void advertiseChannels(UUID playerId, byte[] payload) {
		MinecraftServer server = MinecraftServer.getServer();
		if (server == null) {
			return;
		}
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null || player.connection == null) {
			return;
		}
//? if >=1.21.11 {
		player.connection.send(new ClientboundCustomPayloadPacket(
				new DiscardedPayload(Identifier.parse("minecraft:register"), payload)));
//?} else {
/*		player.connection.send(new ClientboundCustomPayloadPacket(
				new DiscardedPayload(ResourceLocation.parse("minecraft:register"), payload)));
*///?}
	}

	/**
	 * 记一笔各类工作的耗时；非服务端（或未采样）直接忽略。
	 */
	public static void end(int category, Object level, long key, long start) {
		if (level instanceof ServerLevel serverLevel) {
			MsptSampler.end(TickCategory.byIndex(category), serverLevel, key, start);
		}
	}

	/**
	 * 进入一层方块更新：返回最外层的开始时刻，嵌套调用返回 0。
	 */
	public static long neighborBegin(Object level) {
		boolean outermost = neighborDepth++ == 0;
		if (!outermost) {
			return 0L;
		}
		return level instanceof ServerLevel ? MsptSampler.begin() : 0L;
	}

	/**
	 * 退出一层方块更新：最外层结束时入账。
	 */
	public static void neighborEnd(Object level, long key, long start) {
		if (neighborDepth > 0) {
			neighborDepth--;
		}
		if (neighborDepth == 0 && start != 0L && level instanceof ServerLevel serverLevel) {
			MsptSampler.end(TickCategory.NEIGHBOR_UPDATE, serverLevel, key, start);
		}
	}

	/**
	 * 复位嵌套层数（服务器停止时调用）。
	 */
	public static void reset() {
		neighborDepth = 0;
	}
}
