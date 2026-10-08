package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hides the startup noise
 * {@code ServerMain ERROR Unable to create Lookup for jvmrunargs / ClassCastException: ...JmxRuntimeInputArgumentsLookup}
 * (printed about ten times, with a stack trace each).
 *
 * <p>Cause: log4j's {@code Interpolator} instantiates every lookup plugin and casts it with
 * {@code asSubclass(StrLookup.class)}. In this launcher two copies of log4j-core exist in different classloaders (the
 * agent jar bundles log4j-core 2.23.1, Paper brings its own), so the plugin class is not a subclass of the
 * {@code StrLookup} the Interpolator sees. Only the {@code ${jvmrunargs:...}} lookup is lost; Paper's configuration never
 * uses it.
 *
 * <p>{@code Interpolator#handleError(String, Throwable)} - which only logs - returns immediately. The lookups themselves
 * are untouched. Hand-written F_SAME frame after the RETURN.
 */
public final class Log4jInterpolatorQuietTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.Log4jInterpolatorQuietTransformer");

	private static final String TARGET = "org/apache/logging/log4j/core/lookup/Interpolator";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET.equals(className)) {
			return null;
		}
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);
			boolean patched = false;
			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (m.name.equals("handleError") && m.desc.equals("(Ljava/lang/String;Ljava/lang/Throwable;)V")
						&& (m.access & Opcodes.ACC_STATIC) == 0) {
					InsnList add = new InsnList();
					add.add(new InsnNode(Opcodes.RETURN));
					add.add(new LabelNode());
					add.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
					m.instructions.insert(add);
					patched = true;
				}
			}
			if (!patched) {
				return null; // other log4j version: leave it alone
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			return w.toByteArray(); // intentionally silent: this runs before the logger is configured
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}