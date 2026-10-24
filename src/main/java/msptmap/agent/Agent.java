package msptmap.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Java agent 入口：安装字节码注入器，并把注入器运行时类加入服务端的类路径。
 *
 * <p>Paperclip（Paper 及多数分支）、Leavesclip 与 Leaper（Leaf）都用一个新的类加载器加载服务端，
 * 其父加载器为平台加载器，因此 {@code -javaagent} 的系统类路径对它不可见，注入代码会找不到本包
 * 中的类。这里同时走两条路：
 * <ul>
 *   <li>写入 {@code paperclip.extraClasspathUrls}，供仍支持该属性的 Paperclip 版本读取；</li>
 *   <li>由注入器改写引导器的 {@code setupClasspath} 返回值，把运行时类目录追加进返回的 URL
 *       数组，</li>
 * </ul>
 * 后者覆盖已移除该属性的版本，也覆盖不读该属性的 Leavesclip 与 Leaper。追加的是从本 jar 解出的
 * {@code msptmap/agent} 目录而不是 jar 本身：若把整只 jar 暴露给服务端类加载器，插件主类会被它
 * 抢先加载，Bukkit 将拒绝创建插件实例。插件侧再经服务端类加载器访问 {@link Bridge}，即可与注入
 * 代码共享同一份静态状态。
 */
public final class Agent {
	/** Paperclip 读取的额外类路径属性；多个路径以系统路径分隔符连接。 */
	private static final String PAPERCLIP_CLASSPATH = "paperclip.extraClasspathUrls";
	/** 需要暴露给服务端类加载器的包前缀。 */
	private static final String RUNTIME_PACKAGE = "msptmap/agent/";

	private static volatile URL serverClasspathUrl;

	private Agent() {
	}

	public static void premain(String arguments, Instrumentation instrumentation) {
		System.setProperty(Bridge.INSTALLED_PROPERTY, "true");
		serverClasspathUrl = prepareServerClasspath();
		addToPaperclipClasspath(serverClasspathUrl);
		instrumentation.addTransformer(new MsptTransformer(), false);
		Log.info("MsptMap 注入器已安装");
	}

	/**
	 * 服务端类加载器应追加的路径：解出的运行时类目录；准备失败时退回 agent jar 本身。
	 */
	static URL serverClasspathUrl() {
		URL prepared = serverClasspathUrl;
		return prepared != null ? prepared : selfUrl();
	}

	/**
	 * 把运行时类解到临时目录，只暴露 {@code msptmap/agent} 下的类。
	 *
	 * <p>开发环境中 agent 以 class 目录运行时直接返回该目录。
	 */
	private static URL prepareServerClasspath() {
		URL self = selfUrl();
		if (self == null) {
			Log.warn("无法取得 agent jar 路径，注入器类在服务端类加载器中可能不可见");
			return null;
		}
		try {
			Path source = Path.of(self.toURI());
			if (Files.isDirectory(source)) {
				return self;
			}
			Path target = Files.createTempDirectory("msptmap-agent-");
			int copied = 0;
			try (JarFile jar = new JarFile(source.toFile())) {
				Enumeration<JarEntry> entries = jar.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					String name = entry.getName();
					if (entry.isDirectory() || !name.startsWith(RUNTIME_PACKAGE) || !name.endsWith(".class")) {
						continue;
					}
					Path file = target.resolve(name);
					Files.createDirectories(file.getParent());
					try (InputStream input = jar.getInputStream(entry)) {
						Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
					}
					file.toFile().deleteOnExit();
					copied++;
				}
			}
			target.toFile().deleteOnExit();
			Log.info("已准备注入器运行时类：{0} 个 -> {1}", copied, target);
			return target.toUri().toURL();
		} catch (IOException | URISyntaxException | RuntimeException error) {
			Log.warn("无法准备注入器运行时类，将直接使用 agent jar：" + error);
			return self;
		}
	}

	/**
	 * 把运行时类路径追加到 Paperclip 的额外类路径；重复路径不再添加。
	 */
	private static void addToPaperclipClasspath(URL url) {
		if (url == null) {
			Log.warn("无法把注入器加入 Paperclip 类路径，注入将无法生效");
			return;
		}
		try {
			String path = Path.of(url.toURI()).toAbsolutePath().toString();
			String existing = System.getProperty(PAPERCLIP_CLASSPATH);
			if (existing == null || existing.isBlank()) {
				System.setProperty(PAPERCLIP_CLASSPATH, path);
			} else if (!existing.contains(path)) {
				System.setProperty(PAPERCLIP_CLASSPATH, existing + File.pathSeparator + path);
			}
		} catch (URISyntaxException | RuntimeException error) {
			Log.warn("无法把注入器加入 Paperclip 类路径，注入将无法生效：" + error);
		}
	}

	/** agent jar 自身的路径；取不到时为 null。 */
	static URL selfUrl() {
		try {
			return Agent.class.getProtectionDomain().getCodeSource().getLocation();
		} catch (RuntimeException error) {
			return null;
		}
	}
}
