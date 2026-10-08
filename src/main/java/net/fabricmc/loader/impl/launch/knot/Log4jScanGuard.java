package net.fabricmc.loader.impl.launch.knot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Stops Log4j's legacy {@code packages="..."} scanning from loading every game class.
 *
 * <p>Cause (seen in the stack trace): the first Log4j initialisation inside Knot (triggered e.g. by
 * {@code Create.<clinit> -> LogUtils.getLogger()} from Create Fly's mixin plugin during Mixin's select phase)
 * reads log4j2.xml, whose {@code packages} attribute makes {@code ResolverUtil.addIfMatching} call
 * {@code loadClass} on EVERY class in {@code net.minecraft}/{@code com.mojang} (~7500 classes). They get defined
 * before Mixin finished preparing, so Mixin reports "target X was loaded too early" and the classes are defined
 * without any mixin applied.
 *
 * <p>Fix: when the load request comes from ResolverUtil for such a class, only let it through if the class file
 * actually references Log4j's {@code @Plugin} annotation (e.g. com.mojang.util.QueueLogAppender); otherwise throw
 * ClassNotFoundException, which ResolverUtil handles by simply skipping the class.
 * Disable with {@code -Dankhangbo.log4jScanGuard=false}.
 */
final class Log4jScanGuard {
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.log4jScanGuard"));
	private static final String RESOLVER = "org.apache.logging.log4j.core.config.plugins.util.ResolverUtil";
	private static final byte[] PLUGIN_MARK = "org/apache/logging/log4j/core/config/plugins/Plugin".getBytes(StandardCharsets.US_ASCII);
	private static int skipped;

	private Log4jScanGuard() { }

	static boolean shouldBlock(ClassLoader loader, String name) {
		if (!ENABLED) return false;
		if (!name.startsWith("net.minecraft.") && !name.startsWith("com.mojang.")) return false;
		if (!StackWalker.getInstance().walk(s -> s.limit(5).anyMatch(f -> RESOLVER.equals(f.getClassName())))) return false;

		boolean plugin = false;

		try (InputStream in = loader.getResourceAsStream(name.replace('.', '/') + ".class")) {
			if (in == null) return false; // can't tell: let it load as before
			plugin = contains(in.readAllBytes(), PLUGIN_MARK);
		} catch (IOException e) {
			return false; // can't tell: let it load as before
		}

		if (plugin) return false;

		synchronized (Log4jScanGuard.class) {
			if (++skipped == 1) {
				Log.info(LogCategory.KNOT, "Log4j package scanning: skipping non-plugin game classes (e.g. %s) so they are not defined before Mixin is ready", name);
			}
		}

		return true;
	}

	private static boolean contains(byte[] data, byte[] needle) {
		outer:
		for (int i = 0, max = data.length - needle.length; i <= max; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (data[i + j] != needle[j]) continue outer;
			}

			return true;
		}

		return false;
	}
}
