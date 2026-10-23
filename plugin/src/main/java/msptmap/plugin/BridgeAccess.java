package msptmap.plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * 对 {@code msptmap.agent.Bridge} 的反射访问。
 *
 * <p>插件不编译进 agent 类，也不经 PluginClassLoader 加载它：必须显式走服务端类加载器，才能与
 * 注入代码共用同一份静态状态。跨加载器传递的参数与返回值只用 JDK 类型。
 */
final class BridgeAccess {
	/** 扫描开始结果码，与 agent 侧 MsptSampler.StartResult 的 ordinal 一致。 */
	static final int STARTED = 0;
	static final int BUSY = 1;
	static final int COOLDOWN = 2;
	static final int STALLED = 3;

	/** 请求未指定秒数时的默认值。 */
	static final int DEFAULT_SECONDS = 2;

	/** 扫描冷却秒数，与 agent 侧 MsptSampler.COOLDOWN_SECONDS 保持一致。 */
	static final int COOLDOWN_SECONDS = 2;

	private static final String CLASS_NAME = "msptmap.agent.Bridge";

	private final Method setSender;
	private final Method setAlertSink;
	private final Method setAudienceSupplier;
	private final Method setTickSink;
	private final Method start;
	private final Method startAuto;
	private final Method sendDenied;
	private final Method advertiseChannels;
	private final Method reset;

	private BridgeAccess(Class<?> type) throws NoSuchMethodException {
		this.setSender = type.getMethod("setSender", BiPredicate.class);
		this.setAlertSink = type.getMethod("setAlertSink", Consumer.class);
		this.setAudienceSupplier = type.getMethod("setAudienceSupplier", Supplier.class);
		this.setTickSink = type.getMethod("setTickSink", LongConsumer.class);
		this.start = type.getMethod("start", int.class, UUID.class);
		this.startAuto = type.getMethod("startAuto", int.class);
		this.sendDenied = type.getMethod("sendDenied", UUID.class);
		this.advertiseChannels = type.getMethod("advertiseChannels", UUID.class, String[].class);
		this.reset = type.getMethod("reset");
	}

	/**
	 * 经服务端类加载器取 Bridge；未加 -javaagent 或注入器未安装时返回 null。
	 */
	static BridgeAccess create(MsptMapPlugin plugin) {
		try {
			ClassLoader loader = plugin.getServer().getClass().getClassLoader();
			Class<?> type = Class.forName(CLASS_NAME, true, loader);
			Object installed = type.getMethod("isInstalled").invoke(null);
			if (!Boolean.TRUE.equals(installed)) {
				return null;
			}
			return new BridgeAccess(type);
		} catch (ReflectiveOperationException | LinkageError error) {
			return null;
		}
	}

	void setSender(BiPredicate<UUID, byte[]> sender) {
		invoke(setSender, sender);
	}

	void setAlertSink(Consumer<Object[]> sink) {
		invoke(setAlertSink, sink);
	}

	void setAudienceSupplier(Supplier<UUID[]> supplier) {
		invoke(setAudienceSupplier, supplier);
	}

	void setTickSink(LongConsumer sink) {
		invoke(setTickSink, sink);
	}

	int start(int seconds, UUID requester) {
		return (Integer) invoke(start, seconds, requester);
	}

	int startAuto(int seconds) {
		return (Integer) invoke(startAuto, seconds);
	}

	void sendDenied(UUID player) {
		invoke(sendDenied, player);
	}

	void advertiseChannels(UUID player, String[] channels) {
		invoke(advertiseChannels, player, (Object) channels);
	}

	void reset() {
		invoke(reset);
	}

	private static Object invoke(Method method, Object... arguments) {
		try {
			return method.invoke(null, arguments);
		} catch (IllegalAccessException | InvocationTargetException error) {
			throw new IllegalStateException("调用 " + method.getName() + " 失败", error);
		}
	}
}
