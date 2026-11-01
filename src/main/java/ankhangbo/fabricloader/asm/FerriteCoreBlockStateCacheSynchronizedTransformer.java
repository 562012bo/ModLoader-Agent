package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FerriteCore's {@code BlockStateCacheImpl} keeps its de-duplication maps in plain (not thread-safe) fastutil hash maps, because
 * vanilla builds block state caches on one thread. Leaf runs {@code Blocks.<clinit>} -> initCache in a PARALLEL stream, so two
 * threads rehash the same map at once: "ArrayIndexOutOfBoundsException: Index 512 out of bounds for length 257" in
 * Object2ObjectOpenCustomHashMap.rehash. Marking every {@code deduplicate*} method synchronized serialises access to those maps.
 */
public final class FerriteCoreBlockStateCacheSynchronizedTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.FerriteCoreBlockStateCacheSynchronizedTransformer");
	private static final String TARGET = "malte0811/ferritecore/impl/BlockStateCacheImpl";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(buf).accept(cn, 0);
			int changed = 0;

			for (MethodNode m : cn.methods) {
				if (!m.name.startsWith("deduplicate") || (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) continue;
				m.access |= Opcodes.ACC_SYNCHRONIZED;
				changed++;
			}

			if (changed == 0) return null;

			ClassWriter w = new ClassWriter(0);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (" + changed + " deduplicate* method(s) synchronized: Leaf builds block state caches in parallel)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}