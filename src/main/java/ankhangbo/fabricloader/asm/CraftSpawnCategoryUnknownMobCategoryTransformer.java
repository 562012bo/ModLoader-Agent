package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixes the world-tick crash
 * {@code ReportedException: Exception ticking world / Caused by: MatchException at
 * CraftSpawnCategory.toBukkit(CraftSpawnCategory.java:47) <- NaturalSpawner.getFilteredSpawningCategories}.
 *
 * <p>{@code CraftSpawnCategory#toBukkit(MobCategory)} maps the 8 vanilla MobCategory constants with an exhaustive
 * switch. A mod that adds its own MobCategory (via an enum-extending mixin / Fabric spawn-group API) hits no case:
 * the compiler-generated fallthrough throws {@code MatchException} (or Paper's own UnsupportedOperationException)
 * and the whole level tick dies, every tick.
 *
 * <p>The whole method body is wrapped in {@code try { ... } catch (RuntimeException e) { return SpawnCategory.MISC; }}.
 * MISC is the category Paper itself uses for "no Bukkit equivalent": {@code CraftSpawnCategory.isValidForLimits}
 * rejects it, so {@code NaturalSpawner} falls back to the category's own vanilla per-chunk cap and spawns every tick,
 * i.e. the modded category behaves exactly like it does on a vanilla server. Vanilla categories take the normal path.
 * The handler frame is written by hand (F_FULL), so no class loading happens while the class is being defined.
 */
public final class CraftSpawnCategoryUnknownMobCategoryTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftSpawnCategoryUnknownMobCategoryTransformer");

	private static final String TARGET = "org/bukkit/craftbukkit/util/CraftSpawnCategory";
	private static final String DESC = "(Lnet/minecraft/world/entity/MobCategory;)Lorg/bukkit/entity/SpawnCategory;";

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
				if (!m.name.equals("toBukkit") || !m.desc.equals(DESC) || (m.access & Opcodes.ACC_STATIC) == 0) {
					continue;
				}
				LabelNode start = new LabelNode();
				LabelNode end = new LabelNode();
				LabelNode handler = new LabelNode();
				m.instructions.insert(start);
				m.instructions.add(end);
				InsnList h = new InsnList();
				h.add(handler);
				h.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] { "net/minecraft/world/entity/MobCategory" },
						1, new Object[] { "java/lang/RuntimeException" }));
				h.add(new InsnNode(Opcodes.POP));
				h.add(new FieldInsnNode(Opcodes.GETSTATIC, "org/bukkit/entity/SpawnCategory", "MISC",
						"Lorg/bukkit/entity/SpawnCategory;"));
				h.add(new InsnNode(Opcodes.ARETURN));
				m.instructions.add(h);
				m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));
				patched = true;
			}
			if (!patched) {
				LOGGER.warning("CraftSpawnCategory.toBukkit(MobCategory) not found - NOT patched");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED CraftSpawnCategory.toBukkit: modded MobCategory -> SpawnCategory.MISC (no more MatchException)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}