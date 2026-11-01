package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes Moonrise's {@code VoxelShape} cache initialisation compatible with mods written for vanilla.
 *
 * <p>Moonrise (bundled in Paper/Leaf) calls {@code this.moonrise$initCache()} at the END OF THE CONSTRUCTOR of
 * {@code ArrayVoxelShape}, {@code CubeVoxelShape} and {@code SliceShape}. {@code initCache} calls the virtual
 * {@code getCoords(Axis)}. Vanilla does not do that, so mods subclass those shapes and override {@code getCoords}
 * with an implementation that needs a field the subclass only assigns AFTER {@code super(...)} returned, e.g.
 * DiagonalBlocks' {@code NoneVoxelShape extends ExtensibleVoxelShape extends SliceShape}:
 * <pre>NullPointerException: Cannot invoke "VoxelShape.getCoords(Axis)" because "this.collisionShape" is null</pre>
 * Mods that extend {@code VoxelShape} directly never get {@code initCache} called at all, so the first use fails with
 * {@code NullPointerException: ... "shapeData" is null} (SpikySpikes' block registration).
 *
 * <p>Fix: (1) the constructor-time call only runs when {@code getClass()} is exactly the class whose constructor makes the
 * call (i.e. a vanilla shape); (2) {@code VoxelShape} gets {@code ankh$ensure()} which runs {@code initCache} on demand,
 * and it is called before every read of the cached fields, so subclasses are initialised lazily on first use (when
 * their own constructor has long finished). Disable with {@code -Dankhangbo.voxelShapeLazyInit=false}.
 */
public final class VoxelShapeLazyInitTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.VoxelShapeLazyInitTransformer");
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.voxelShapeLazyInit"));

	private static final String VOXEL_SHAPE = "net/minecraft/world/phys/shapes/VoxelShape";
	private static final String PACKAGE = "net/minecraft/world/phys/shapes/";
	private static final String INIT_CACHE = "moonrise$initCache";
	private static final String ENSURE = "ankh$ensure";
	private static final String CTOR_INIT = "ankh$ctorInit";
	private static final String COLLISION_VOXEL_SHAPE = "ca/spottedleaf/moonrise/patches/collisions/shape/CollisionVoxelShape";

	/** every field Moonrise fills in initCache() and then reads directly */
	private static final Set<String> GATED_FIELDS = new HashSet<>(Arrays.asList(
			"cachedShapeData", "isEmpty", "offsetX", "offsetY", "offsetZ", "singleAABBRepresentation",
			"rootCoordinatesX", "rootCoordinatesY", "rootCoordinatesZ", "cachedBounds"));

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!ENABLED || className == null || !className.startsWith(PACKAGE)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);
			boolean changed;

			if (className.equals(VOXEL_SHAPE)) {
				changed = patchVoxelShape(cn);
			} else {
				changed = patchConstructors(cn);
			}

			if (!changed) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (Moonrise shape cache initialised lazily for mods that subclass shapes)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}

	/**
	 * vanilla shape constructors: {@code ((CollisionVoxelShape) this).moonrise$initCache()} (an INVOKEINTERFACE in Paper's
	 * patch, an INVOKEVIRTUAL in other builds) -> {@code VoxelShape.ankh$ctorInit(this, ThisClass.class)}.
	 */
	@SuppressWarnings("unchecked")
	private static boolean patchConstructors(ClassNode cn) {
		boolean changed = false;

		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (!m.name.equals("<init>")) continue;

			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode) {
					MethodInsnNode call = (MethodInsnNode) insn;
					int op = call.getOpcode();

					if ((op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE || op == Opcodes.INVOKESPECIAL)
							&& call.name.equals(INIT_CACHE) && call.desc.equals("()V")) {
						m.instructions.insertBefore(call, new LdcInsnNode(Type.getObjectType(cn.name)));
						m.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, VOXEL_SHAPE, CTOR_INIT,
								"(L" + COLLISION_VOXEL_SHAPE + ";Ljava/lang/Class;)V", false));
						changed = true;
					}
				}
			}
		}

		return changed;
	}

	@SuppressWarnings("unchecked")
	private static boolean patchVoxelShape(ClassNode cn) {
		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (m.name.equals(ENSURE)) return false; // already patched
		}

		boolean sawInitCache = false;

		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (m.name.equals(INIT_CACHE)) {
				sawInitCache = true;
				continue; // it writes these fields itself
			}
			if (m.name.equals("<init>") || (m.access & Opcodes.ACC_STATIC) != 0) continue;

			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn.getOpcode() == Opcodes.GETFIELD) {
					FieldInsnNode f = (FieldInsnNode) insn;

					if (f.owner.equals(VOXEL_SHAPE) && GATED_FIELDS.contains(f.name)) {
						InsnList pre = new InsnList();
						pre.add(new InsnNode(Opcodes.DUP));
						pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VOXEL_SHAPE, ENSURE, "()V", false));
						m.instructions.insertBefore(f, pre);
					}
				}
			}
		}

		if (!sawInitCache) {
			LOGGER.warning(VOXEL_SHAPE + ": " + INIT_CACHE + " not found - NOT patched (not a Moonrise build?)");
			return false;
		}

		// public final void ankh$ensure() { if (this.cachedShapeData == null) this.moonrise$initCache(); }
		MethodNode ensure = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, ENSURE, "()V", null, null);
		LabelNode done = new LabelNode();
		ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ensure.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VOXEL_SHAPE, "cachedShapeData",
				"Lca/spottedleaf/moonrise/patches/collisions/shape/CachedShapeData;"));
		ensure.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, done));
		ensure.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ensure.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VOXEL_SHAPE, INIT_CACHE, "()V", false));
		ensure.instructions.add(done);
		ensure.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		ensure.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(ensure);

		// public static void ankh$ctorInit(CollisionVoxelShape self, Class c) { if (self.getClass() == c) self.moonrise$initCache(); }
		MethodNode ctorInit = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, CTOR_INIT,
				"(L" + COLLISION_VOXEL_SHAPE + ";Ljava/lang/Class;)V", null, null);
		LabelNode skip = new LabelNode();
		ctorInit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ctorInit.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
		ctorInit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		ctorInit.instructions.add(new JumpInsnNode(Opcodes.IF_ACMPNE, skip));
		ctorInit.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ctorInit.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, COLLISION_VOXEL_SHAPE, INIT_CACHE, "()V", true));
		ctorInit.instructions.add(skip);
		ctorInit.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		ctorInit.instructions.add(new InsnNode(Opcodes.RETURN));
		cn.methods.add(ctorInit);

		return true;
	}
}