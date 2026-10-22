package msptmap.agent;

import java.net.URL;
import java.util.Arrays;

/**
 * Paperclip 类路径钩子：由注入器在 {@code Paperclip.setupClasspath} 返回前调用。
 *
 * <p>Paper 26.2 起，随服务端发布的 Paperclip 已不再读取 {@code paperclip.extraClasspathUrls}，
 * 但注入器必须先于 Paperclip 运行，无法在它创建类加载器后再追加 URL。因此在返回值里补上本 jar，
 * 使服务端类加载器同时加载本包。钩子自身只依赖 JDK，能由系统类加载器安全加载。
 */
public final class PaperclipHook {
	private PaperclipHook() {
	}

	/**
	 * 在 Paperclip 的类路径数组末尾追加注入器运行时类路径；已存在或取不到路径时原样返回。
	 */
	public static URL[] appendSelf(URL[] urls) {
		URL runtime = Agent.serverClasspathUrl();
		if (runtime == null) {
			Log.warn("无法取得注入器运行时类路径，注入器类在服务端类加载器中不可见");
			return urls;
		}
		for (URL url : urls) {
			if (url.equals(runtime)) {
				return urls;
			}
		}
		URL[] extended = Arrays.copyOf(urls, urls.length + 1);
		extended[urls.length] = runtime;
		Log.info("已把注入器运行时类加入 Paperclip 类路径：{0}", runtime);
		return extended;
	}
}
