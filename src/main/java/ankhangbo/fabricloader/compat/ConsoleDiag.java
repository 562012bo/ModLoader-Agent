package ankhangbo.fabricloader.compat;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One-shot console report printed when Paper starts its console thread, to find out WHY Tab completion is
 * missing (JLine running as a "dumb" terminal, a provider failing to load, or JLine disabled).
 * JDK-only signature; Minecraft/JLine classes are reached reflectively through the server's classloader.
 */
public final class ConsoleDiag {
	private static final Logger LOGGER = Logger.getLogger("ConsoleDiag");

	private ConsoleDiag() {
	}

	public static void report(Object server) {
		try {
			ClassLoader cl = server.getClass().getClassLoader();
			StringBuilder sb = new StringBuilder("Console diagnostics:");
			sb.append("\n  java=").append(System.getProperty("java.version")).append(" os=").append(System.getProperty("os.name"));
			sb.append("\n  System.console()=").append(System.console() != null ? "present" : "null");
			for (String p : new String[] { "terminal.jline", "terminal.ansi", "jdk.console", "org.jline.terminal.providers",
					"org.jline.terminal.dumb", "jline.terminal" }) {
				sb.append("\n  -D").append(p).append("=").append(System.getProperty(p));
			}
			try {
				Class<?> tca = Class.forName("net.minecrell.terminalconsole.TerminalConsoleAppender", false, cl);
				sb.append("\n  TerminalConsoleAppender.isAnsiSupported()=").append(tca.getMethod("isAnsiSupported").invoke(null));
				Object term = tca.getMethod("getTerminal").invoke(null);
				if (term == null) {
					sb.append("\n  JLine terminal = null (JLine NOT initialised -> console reads raw stdin, no Tab completion)");
				} else {
					Class<?> termC = Class.forName("org.jline.terminal.Terminal", false, cl);
					sb.append("\n  JLine terminal = ").append(term.getClass().getName())
							.append(" type=").append(termC.getMethod("getType").invoke(term))
							.append(" name=").append(termC.getMethod("getName").invoke(term));
				}
			} catch (Throwable t) {
				sb.append("\n  could not inspect TerminalConsoleAppender: ").append(describe(t));
			}
			try {
				Class<?> tp = Class.forName("org.jline.terminal.spi.TerminalProvider", false, cl);
				Method load = tp.getMethod("load", String.class);
				for (String name : new String[] { "ffm", "jni", "jansi", "jna" }) {
					try {
						Object p = load.invoke(null, name);
						sb.append("\n  provider '").append(name).append("': loaded ").append(p == null ? "null" : p.getClass().getName());
					} catch (Throwable t) {
						sb.append("\n  provider '").append(name).append("': FAILED ").append(describe(t));
					}
				}
			} catch (Throwable t) {
				sb.append("\n  JLine provider API not available: ").append(describe(t));
			}
			LOGGER.info(sb.toString());
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Console diagnostics failed", t);
		}
	}

	private static String describe(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null && sb.length() < 400; c = c.getCause()) {
			if (c instanceof java.lang.reflect.InvocationTargetException && c.getCause() != null) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(" <- ");
			}
			sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
		}
		return sb.toString();
	}
}