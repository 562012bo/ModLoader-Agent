package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fixes "CraftBlockState cannot be cast to CraftSign" at SignBlock#openTextEdit.
 *
 * <p>{@code CraftBlockStates.getBlockState(World, BlockPos, BlockState, BlockEntity)} picks the
 * Bukkit state class from the block's {@code Material}. A modded sign (biomesoplenty:*_sign) has
 * no Material (null), so the generic DEFAULT_FACTORY is used and a plain CraftBlockState comes
 * back (LenientDefaultBlockStateTransformer removed the check that used to throw there).
 *
 * <p>This inserts, at the very start of that method, a lookup by the BLOCK ENTITY instead:
 * <pre>
 *   if (blockEntity instanceof HangingSignBlockEntity) return new CraftHangingSign(world, be);
 *   if (blockEntity instanceof SignBlockEntity)        return new CraftSign(world, be);
 * </pre>
 * which is exactly what the vanilla-sign factory does, but independent of Material/type maps.
 */
public final class CraftBlockStatesSignFallbackTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftBlockStatesSignFallbackTransformer");

	private static final String TARGET = "org/bukkit/craftbukkit/block/CraftBlockStates";
	private static final String DESC_PREFIX = "(Lorg/bukkit/World;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/block/entity/BlockEntity;)";
	private static final String SIGN_BE = "net/minecraft/world/level/block/entity/SignBlockEntity";
	private static final String HANGING_BE = "net/minecraft/world/level/block/entity/HangingSignBlockEntity";
	private static final String CRAFT_SIGN = "org/bukkit/craftbukkit/block/CraftSign";
	private static final String CRAFT_HANGING = "org/bukkit/craftbukkit/block/CraftHangingSign";

	private static final class SafeClassWriter extends ClassWriter {
		private final ClassLoader targetLoader;

		SafeClassWriter(int flags, ClassLoader targetLoader) {
			super(flags);
			this.targetLoader = targetLoader;
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			try {
				Class<?> c1 = Class.forName(type1.replace('/', '.'), false, targetLoader);
				Class<?> c2 = Class.forName(type2.replace('/', '.'), false, targetLoader);
				if (c1.isAssignableFrom(c2)) {
					return type1;
				}
				if (c2.isAssignableFrom(c1)) {
					return type2;
				}
				if (c1.isInterface() || c2.isInterface()) {
					return "java/lang/Object";
				}
				do {
					c1 = c1.getSuperclass();
				} while (!c1.isAssignableFrom(c2));
				return c1.getName().replace('.', '/');
			} catch (Throwable t) {
				return "java/lang/Object";
			}
		}
	}

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
				if (!m.name.equals("getBlockState") || !m.desc.startsWith(DESC_PREFIX)
						|| (m.access & Opcodes.ACC_STATIC) == 0) {
					continue;
				}
				LabelNode notHanging = new LabelNode();
				LabelNode notSign = new LabelNode();
				InsnList g = new InsnList();

				g.add(new VarInsnNode(Opcodes.ALOAD, 3));
				g.add(new TypeInsnNode(Opcodes.INSTANCEOF, HANGING_BE));
				g.add(new JumpInsnNode(Opcodes.IFEQ, notHanging));
				g.add(new TypeInsnNode(Opcodes.NEW, CRAFT_HANGING));
				g.add(new InsnNode(Opcodes.DUP));
				g.add(new VarInsnNode(Opcodes.ALOAD, 0));
				g.add(new VarInsnNode(Opcodes.ALOAD, 3));
				g.add(new TypeInsnNode(Opcodes.CHECKCAST, HANGING_BE));
				g.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_HANGING, "<init>",
						"(Lorg/bukkit/World;L" + HANGING_BE + ";)V", false));
				g.add(new InsnNode(Opcodes.ARETURN));

				g.add(notHanging);
				g.add(new VarInsnNode(Opcodes.ALOAD, 3));
				g.add(new TypeInsnNode(Opcodes.INSTANCEOF, SIGN_BE));
				g.add(new JumpInsnNode(Opcodes.IFEQ, notSign));
				g.add(new TypeInsnNode(Opcodes.NEW, CRAFT_SIGN));
				g.add(new InsnNode(Opcodes.DUP));
				g.add(new VarInsnNode(Opcodes.ALOAD, 0));
				g.add(new VarInsnNode(Opcodes.ALOAD, 3));
				g.add(new TypeInsnNode(Opcodes.CHECKCAST, SIGN_BE));
				g.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_SIGN, "<init>",
						"(Lorg/bukkit/World;L" + SIGN_BE + ";)V", false));
				g.add(new InsnNode(Opcodes.ARETURN));
				g.add(notSign);

				m.instructions.insert(g);
				patched = true;
			}
			if (!patched) {
				LOGGER.warning("CraftBlockStates.getBlockState(World,BlockPos,BlockState,BlockEntity) "
						+ "not found - sign fallback NOT installed");
				return null;
			}
			ClassWriter w = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			cn.accept(w);
			LOGGER.info("PATCHED CraftBlockStates: sign block entities always map to CraftSign/CraftHangingSign");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}