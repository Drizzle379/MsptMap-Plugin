package msptmap.agent;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * agent 侧日志。Paper 启动阶段尚无插件日志器，统一走 JDK 日志（输出到控制台）。
 */
final class Log {
	private static final Logger LOGGER = Logger.getLogger("MsptMap");

	private Log() {
	}

	static void info(String message, Object... args) {
		LOGGER.log(Level.INFO, message, args);
	}

	static void warn(String message, Object... args) {
		LOGGER.log(Level.WARNING, message, args);
	}

	static void debug(String message, Object... args) {
		LOGGER.log(Level.FINE, message, args);
	}
}
