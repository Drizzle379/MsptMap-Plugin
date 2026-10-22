package msptmap.agent;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 资源位置工具：维度 ID 与注册表键路径。
 */
public final class Ids {
	private Ids() {
	}

	/**
	 * 维度的完整 ID（如 {@code minecraft:overworld}）；客户端快照按此分组存储。
	 */
	public static String id(ResourceKey<Level> dimension) {
		return dimension.identifier().toString();
	}

	/**
	 * 注册表键的路径段（如 {@code player_loading}）；值未注册时返回 null。
	 */
	public static <T> String path(Registry<T> registry, T value) {
		Identifier key = registry.getKey(value);
		return key == null ? null : key.getPath();
	}
}
