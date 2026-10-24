package msptmap.agent;

import java.net.URL;
import java.util.Arrays;

/**
 * 引导器的类路径钩子：由注入器在 {@code setupClasspath} 返回前调用。
 *
 * <p>Paperclip（Paper 及多数分支）、Leavesclip 与 Leaper（Leaf）都用新的类加载器加载服务端，
 * 其父加载器为平台加载器，因此 {@code -javaagent} 的系统类路径对它不可见。Paper 26.2 起 Paperclip 也不再读取
 * {@code paperclip.extraClasspathUrls}，而注入器必须先于引导器运行，无法在其创建类加载器后再
 * 追加 URL。因此在返回值里补上注入器运行时类路径，使服务端类加载器同时加载本包。钩子自身只依赖
 * JDK，能由系统类加载器安全加载。
 */
public final class ClipHook {
	private ClipHook() {
	}

	/**
	 * 在引导器的类路径数组末尾追加注入器运行时类路径；已存在或取不到路径时原样返回。
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
		Log.info("已把注入器运行时类加入服务端类路径：{0}", runtime);
		return extended;
	}
}
