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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code net.minecraft.world.entity.Entity#move(MoverType, Vec3)} tolerate a
 * {@code null} {@code Material} when it checks the block a vehicle just collided with, instead of
 * throwing a {@code NullPointerException} every time a modded vehicle (e.g. a modded boat) touches
 * a modded block.
 *
 * <p>Root cause chain, confirmed against Leaf 26.1.2's real decompiled source:
 * <ol>
 *   <li>{@code move(...)}, when a {@code Vehicle} has a horizontal collision, looks up the Bukkit
 *       {@code Block} at the collision position and does
 *       {@code if (!block.getType().isAir()) { fire VehicleBlockCollisionEvent }}.</li>
 *   <li>{@code CraftBlock.getType()} - like {@code CraftBlockStates} and the other block-material
 *       lookups already patched elsewhere in this project - legitimately returns {@code null} when
 *       the block is one a mod registered, since Bukkit's {@code Material} enum has no constant for
 *       it. Calling {@code .isAir()} on that null reference throws immediately.</li>
 * </ol>
 *
 * <p>Note this specific path was unreachable before {@code CraftEntityGenericFallbackTransformer}
 * started returning a real {@code CraftBoat} (which implements {@code Vehicle}) for modded boats -
 * with the old plain {@code CraftEntity} fallback, {@code getBukkitEntity() instanceof Vehicle} was
 * always false here, so this branch never ran for a modded boat at all. Making the entity's Bukkit
 * type correct exposed this next, separate bug in the same code path; it's not a regression, just
 * the next layer of the same underlying "mod content is unmapped in Bukkit" problem.
 *
 * <p><b>Matching is intentionally loose, and patches every occurrence found</b>: this only requires
 * finding an {@code INVOKEVIRTUAL Material.isAir()} call site anywhere in the method, not that it's
 * immediately preceded by a specific {@code Block.getType()} call, and fixes ALL matches rather
 * than stopping at the first. A stricter, adjacency-checking version of this transformer - even
 * after also accepting {@code INVOKEINTERFACE} for {@code getType()} (since
 * {@code org.bukkit.block.Block} is an interface) and only patching the first match - still failed
 * to prevent this exact crash recurring on a real server, most likely because there's more than one
 * matching call site in this method and the stricter version stopped after the first, or because
 * the actual compiled bytecode isn't the simple back-to-back shape decompiled source suggests (e.g.
 * an intermediate {@code CHECKCAST} from {@code block.getRelative(...)}'s return type). The JVM
 * stack guarantees a {@code Material} reference (possibly null) is on top right before ANY
 * {@code isAir()} invocation regardless of what instruction produced it, so requiring that
 * adjacency added fragility without adding safety.
 */
public final class EntityMoveVehicleBlockNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.EntityMoveVehicleBlockNullSafeTransformer");

	private static final String TARGET_CLASS = "net/minecraft/world/entity/Entity";
	private static final String TARGET_METHOD = "move";
	private static final String TARGET_DESC =
			"(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V";

	private static final String IS_AIR_OWNER = "org/bukkit/Material";
	private static final String IS_AIR_NAME = "isAir";
	private static final String IS_AIR_DESC = "()Z";

	private static final class SafeClassWriter extends ClassWriter {
		private final ClassLoader targetLoader;

		SafeClassWriter(int flags, ClassLoader targetLoader) {
			super(flags);
			this.targetLoader = targetLoader;
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			Class<?> class1;
			Class<?> class2;
			try {
				class1 = Class.forName(type1.replace('/', '.'), false, targetLoader);
				class2 = Class.forName(type2.replace('/', '.'), false, targetLoader);
			} catch (Throwable t) {
				return "java/lang/Object";
			}
			if (class1.isAssignableFrom(class2)) {
				return type1;
			}
			if (class2.isAssignableFrom(class1)) {
				return type2;
			}
			if (class1.isInterface() || class2.isInterface()) {
				return "java/lang/Object";
			}
			do {
				class1 = class1.getSuperclass();
			} while (!class1.isAssignableFrom(class2));
			return class1.getName().replace('.', '/');
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET_CLASS.equals(className)) {
			return null;
		}

		try {
			ClassNode classNode = new ClassNode();
			new ClassReader(classfileBuffer).accept(classNode, 0);

			boolean patched = false;
			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals(TARGET_METHOD) || !method.desc.equals(TARGET_DESC)) {
					continue;
				}

				// Match on the isAir() call alone, regardless of what instruction immediately
				// precedes it, and patch EVERY occurrence found (not just the first) - the JVM
				// stack guarantees a Material reference (possibly null) is on top right before
				// ANY isAir() invocation no matter what produced it, so we don't need to also
				// verify the previous instruction is literally a getType() call. A stricter,
				// adjacency-checking version of this transformer (even after also accepting
				// INVOKEINTERFACE for getType(), since org.bukkit.block.Block is an interface)
				// kept failing to find/patch this exact real-world crash site on this server
				// build - most likely because there is more than one matching call site in this
				// method and only the first was ever patched, or because the real compiled
				// bytecode isn't the simple back-to-back shape decompiled source suggests.
				List<MethodInsnNode> isAirCalls = new java.util.ArrayList<>();
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn instanceof MethodInsnNode call
							&& call.getOpcode() == Opcodes.INVOKEVIRTUAL
							&& call.owner.equals(IS_AIR_OWNER)
							&& call.name.equals(IS_AIR_NAME)
							&& call.desc.equals(IS_AIR_DESC)) {
						isAirCalls.add(call);
					}
				}
				if (isAirCalls.isEmpty()) {
					LOGGER.warning("Could not locate any 'Material.isAir()' call in "
							+ className + "#" + TARGET_METHOD + " - leaving it unpatched");
					continue;
				}

				for (MethodInsnNode isAirCall : isAirCalls) {
					LabelNode isNullBranch = new LabelNode();
					LabelNode haveResult = new LabelNode();

					InsnList guard = new InsnList();
					guard.add(new InsnNode(Opcodes.DUP)); // duplicate the Material ref for the null test
					guard.add(new JumpInsnNode(Opcodes.IFNULL, isNullBranch));
					// non-null path: call isAir() as before
					guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, IS_AIR_OWNER, IS_AIR_NAME, IS_AIR_DESC, false));
					guard.add(new JumpInsnNode(Opcodes.GOTO, haveResult));
					guard.add(isNullBranch);
					guard.add(new InsnNode(Opcodes.POP)); // discard the null Material reference
					guard.add(new InsnNode(Opcodes.ICONST_1)); // treat "unknown material" as "is air" (skip the event)
					guard.add(haveResult);

					method.instructions.insertBefore(isAirCall, guard);
					method.instructions.remove(isAirCall);
				}

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " tolerate a null Material (modded block) when checking vehicle collisions.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate a null block Material in " + TARGET_METHOD
					+ " - leaving it unpatched", t);
			return null;
		}
	}
}