package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes every {@code INVOKEVIRTUAL org/bukkit/Material.isLegacy ()Z} call site found in any
 * loaded {@code org/bukkit/*} class null-safe, so a modded item (whose {@code Material} lookup is
 * always {@code null} - see {@link ankhangbo.fabricloader.compat.NullSafeMaterial}'s javadoc for
 * the full chain) no longer crashes CraftBukkit code that calls {@code .isLegacy()} without a null
 * check.
 *
 * <p><b>This patches the call site in place, inline, instead of redirecting it to a helper
 * method.</b> An earlier version of this transformer redirected the call to a static helper
 * ({@code ankhangbo.fabricloader.compat.NullSafeMaterial#isLegacySafe(Material)}) living in this
 * agent's own jar. That helper class is loaded by the JVM's system/agent classloader (this is a
 * {@code -javaagent}), which is the PARENT of the server's own classloader
 * ("PaperFabricLoader"/Knot) that actually defines {@code org.bukkit.Material}. A child
 * classloader can see classes defined by its parent, but never the other way around - so the
 * moment the JVM tried to link {@code isLegacySafe(Material)} (on its first invocation, from deep
 * inside Bukkit's static bootstrap), it could not resolve the {@code Material} parameter type from
 * the helper's own (parent) classloader and threw {@code NoClassDefFoundError}, crashing the
 * server before it could even finish starting - a strictly worse failure than the original NPE
 * this was meant to fix.
 *
 * <p>Emitting the null check directly into the patched class's own bytecode sidesteps that
 * entirely: all the added instructions resolve {@code Material} using whatever classloader already
 * loaded the surrounding {@code org/bukkit/*} class (the correct, child, classloader), so nothing
 * new ever needs to be resolved across the classloader boundary. Each call site
 * <pre>
 *   ALOAD material
 *   INVOKEVIRTUAL org/bukkit/Material.isLegacy ()Z
 * </pre>
 * becomes
 * <pre>
 *   ALOAD material
 *   DUP
 *   IFNULL L1
 *   INVOKEVIRTUAL org/bukkit/Material.isLegacy ()Z
 *   GOTO L2
 *   L1:
 *   POP
 *   ICONST_0
 *   L2:
 * </pre>
 * which is stack-neutral (pops exactly one {@code Material} reference, possibly null, pushes
 * exactly one {@code boolean}) and treats a null/modded material as "not a legacy (pre-1.13)
 * material" - correct, since legacy-material handling has no meaning for a modded item to begin
 * with.
 *
 * <p>Registered via {@code inst.addTransformer(new NullSafeMaterialTransformer(), false)} in
 * {@code FabricLoaderAgent.premain()}, same as the delegation transformers - this is safe to
 * install that early since no {@code org.bukkit.*} class has been loaded yet at that point (Paper
 * bootstrap hasn't even started), so this is guaranteed to see every one of them exactly once, on
 * its first and only load.
 */
public final class NullSafeMaterialTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.NullSafeMaterialTransformer");

	private static final String TARGET_OWNER = "org/bukkit/Material";
	private static final String TARGET_NAME = "isLegacy";
	private static final String TARGET_DESC = "()Z";

	/**
	 * {@code ClassWriter.COMPUTE_FRAMES} recomputes stack-map frames for the WHOLE method, not just
	 * the bytecode this transformer inserted - and its default {@code getCommonSuperClass} resolves
	 * reference types via {@code Class.forName} using this class's own (agent/system) classloader,
	 * which is a parent of the server's classloader and so cannot see server-defined types. Left
	 * unhandled, an unrelated reference-type merge elsewhere in a patched method would throw and
	 * abort patching that whole class (caught further down, logged, class left unpatched). Falling
	 * back to {@code java/lang/Object} instead keeps the class loadable and correct - a
	 * conservatively-wide common-superclass guess only makes the computed frame less precise, never
	 * unsafe. Same fix already applied in {@code PaperModSignatureCompat} for the identical failure
	 * mode.
	 */
	private static final class SafeClassWriter extends ClassWriter {
		SafeClassWriter(int flags) {
			super(flags);
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			try {
				return super.getCommonSuperClass(type1, type2);
			} catch (TypeNotPresentException | LinkageError e) {
				return "java/lang/Object";
			}
		}
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null || !className.startsWith("org/bukkit/")) {
			return null;
		}

		try {
			ClassReader reader = new ClassReader(classfileBuffer);
			ClassNode classNode = new ClassNode();
			reader.accept(classNode, 0);

			boolean patchedAny = false;

			for (MethodNode method : classNode.methods) {
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL || !(insn instanceof MethodInsnNode)) {
						continue;
					}

					MethodInsnNode call = (MethodInsnNode) insn;

					if (call.owner.equals(TARGET_OWNER) && call.name.equals(TARGET_NAME) && call.desc.equals(TARGET_DESC)) {
						LabelNode nullLabel = new LabelNode();
						LabelNode endLabel = new LabelNode();

						InsnList replacement = new InsnList();
						replacement.add(new InsnNode(Opcodes.DUP));
						replacement.add(new JumpInsnNode(Opcodes.IFNULL, nullLabel));
						replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET_OWNER, TARGET_NAME, TARGET_DESC, false));
						replacement.add(new JumpInsnNode(Opcodes.GOTO, endLabel));
						replacement.add(nullLabel);
						replacement.add(new InsnNode(Opcodes.POP));
						replacement.add(new InsnNode(Opcodes.ICONST_0));
						replacement.add(endLabel);

						method.instructions.insertBefore(call, replacement);
						method.instructions.remove(call);
						patchedAny = true;
					}
				}
			}

			if (!patchedAny) {
				return null;
			}

			LOGGER.fine(() -> "Inlined null-safe Material.isLegacy() check in " + className);

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
			classNode.accept(writer);
			return writer.toByteArray();
		} catch (Throwable t) {
			// Never let a patching failure on some unrelated org.bukkit class prevent the server
			// from starting at all - just leave that one class unpatched and log why.
			LOGGER.log(Level.WARNING, "Failed to patch " + className + " for null-safe Material.isLegacy() - leaving it unpatched", t);
			return null;
		}
	}
}
