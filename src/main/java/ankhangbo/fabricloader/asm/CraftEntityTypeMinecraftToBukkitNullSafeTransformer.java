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
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code org.bukkit.craftbukkit.entity.CraftEntityType#minecraftToBukkit(EntityType)} return
 * {@code null} for a modded entity type instead of throwing {@code IllegalArgumentException}.
 *
 * <p>This is the actual root of the whole "modded entity crashes X" family: {@code minecraftToBukkit}
 * is called from {@code CraftEntity}'s own constructor and from {@code CraftEntity.getEntity(...)},
 * which in turn is what {@code net.minecraft.world.entity.Entity#getBukkitEntity()} calls - and
 * *that* is called from all over NMS/CraftBukkit (event firing, entity add/load, damage handling,
 * etc.), not just the one or two call sites seen so far (boat placement, entity-add, NBT loading
 * via {@code setAirSupply}). Patching every individual caller of {@code getBukkitEntity()} one at a
 * time doesn't scale - there could be dozens more. Fixing it here, at the actual lookup, fixes all
 * of them at once.
 *
 * <p>{@code minecraftToBukkit} looks the NMS entity type up in {@code Registry.ENTITY_TYPE} by
 * namespaced key; for a mod-registered entity type, that lookup always returns null, and the method
 * then does {@code Preconditions.checkArgument(bukkit != null)}, which throws. This transformer
 * inserts an early return right after the lookup: if the result is null, return null immediately -
 * skipping both the {@code checkArgument} throw and the subsequent
 * {@code MINECRAFT_TO_BUKKIT_KEY_CACHE.put(minecraft, bukkit)} (which would itself throw a
 * {@code NullPointerException}, since {@code ConcurrentHashMap} rejects null values).
 *
 * <p>See {@link CraftEntityGenericFallbackTransformer} for the matching change needed on the
 * {@code CraftEntity} side so that a null Bukkit {@code EntityType} doesn't just turn one crash into
 * another.
 */
public final class CraftEntityTypeMinecraftToBukkitNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftEntityTypeMinecraftToBukkitNullSafeTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/entity/CraftEntityType";
	private static final String TARGET_METHOD = "minecraftToBukkit";
	private static final String TARGET_DESC =
			"(Lnet/minecraft/world/entity/EntityType;)Lorg/bukkit/entity/EntityType;";
	private static final String CHECK_ARGUMENT_OWNER = "com/google/common/base/Preconditions";
	private static final String CHECK_ARGUMENT_NAME = "checkArgument";
	private static final String CHECK_ARGUMENT_DESC = "(Z)V";

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

				// This method calls Preconditions.checkArgument(boolean) twice: once to guard
				// against a null *input*, once to guard against the lookup result being null. We
				// want the *second* one.
				List<MethodInsnNode> checkArgumentCalls = new ArrayList<>();
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof MethodInsnNode call
							&& call.getOpcode() == Opcodes.INVOKESTATIC
							&& call.owner.equals(CHECK_ARGUMENT_OWNER)
							&& call.name.equals(CHECK_ARGUMENT_NAME)
							&& call.desc.equals(CHECK_ARGUMENT_DESC)) {
						checkArgumentCalls.add(call);
					}
				}
				if (checkArgumentCalls.size() < 2) {
					LOGGER.warning("Expected two Preconditions.checkArgument(boolean) calls in "
							+ className + "#" + TARGET_METHOD + " but found " + checkArgumentCalls.size()
							+ " - leaving it unpatched");
					continue;
				}
				MethodInsnNode secondCheck = checkArgumentCalls.get(1);

				// Walk back from that call to find "ALOAD bukkit" - the first instruction of the
				// "bukkit != null ? 1 : 0" ternary that feeds it.
				VarInsnNode anchor = null;
				AbstractInsnNode cursor = secondCheck.getPrevious();
				for (int steps = 0; cursor != null && steps < 12; steps++, cursor = cursor.getPrevious()) {
					if (cursor instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ALOAD) {
						anchor = varInsn;
						// keep walking backwards - we want the *earliest* ALOAD in this short
						// idiom, i.e. the one immediately preceding the IFNULL/IFNONNULL branch.
					} else if (anchor != null) {
						break;
					}
				}
				if (anchor == null) {
					LOGGER.warning("Could not locate the 'bukkit' local variable before the second "
							+ "checkArgument call in " + className + "#" + TARGET_METHOD
							+ " - leaving it unpatched");
					continue;
				}

				LabelNode skip = new LabelNode();
				InsnList guard = new InsnList();
				guard.add(new VarInsnNode(Opcodes.ALOAD, anchor.var));
				guard.add(new JumpInsnNode(Opcodes.IFNONNULL, skip));
				guard.add(new InsnNode(Opcodes.ACONST_NULL));
				guard.add(new InsnNode(Opcodes.ARETURN));
				guard.add(skip);
				method.instructions.insertBefore(anchor, guard);

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + "#" + TARGET_METHOD
					+ " return null for unmapped (modded) entity types instead of throwing.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " to tolerate unmapped entity types - leaving it unpatched", t);
			return null;
		}
	}
}