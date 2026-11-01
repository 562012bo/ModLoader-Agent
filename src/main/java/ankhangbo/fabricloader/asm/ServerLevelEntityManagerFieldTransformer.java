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
 * Moonrise (Paper/Leaf 26.2) removed {@code ServerLevel.entityManager}, but Fabric mods still declare an {@code @Accessor}/{@code @Shadow}
 * for it (Chunky's ServerLevelMixin#getEntityManager) and Mixin aborts the whole bootstrap with InvalidAccessorException.
 * Re-adds the field (always null; the real entity storage lives in Moonrise now) so such mixins apply.
 *
 * <p>Must be visible both when Mixin reads the target's bytes and when Knot defines it, so it is run from
 * {@link GameClassPatcher}-chain AND {@code GameClassPatcher.applyMixinVisible}; a second pass finds the field and does nothing.
 */
public final class ServerLevelEntityManagerFieldTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.ServerLevelEntityManagerFieldTransformer");
	private static final String TARGET = "net/minecraft/server/level/ServerLevel";
	private static final String FIELD = "entityManager";
	private static final String DESC = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(buf).accept(cn, 0);

			for (FieldNode f : cn.fields) {
				if (f.name.equals(FIELD)) return null;
			}

			cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, FIELD, DESC, null, null));
			ClassWriter w = new ClassWriter(0);
			cn.accept(w);
			//LOGGER.info("PATCHED " + className + " (re-added removed field " + FIELD + ", null; Chunky etc. still reference it)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}