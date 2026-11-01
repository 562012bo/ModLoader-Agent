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
 * Leaf's "Cache XP orb merge parameters" replaced {@code ExperienceOrb.canMerge(ExperienceOrb,int,int)} with
 * {@code canMerge(orb, id, value, groupsPerArea)}. Clumps' MixinExperienceOrb {@code @Shadow}s the old one; without a real method
 * ShadowMaterializer had to merge a stub, and ServerCore's {@code @ModifyConstant} on canMerge then failed with
 * "cannot inject into ... merged by com.blamejared.clumps.mixin.MixinExperienceOrb".
 *
 * <p>Adds {@code public static synthetic canMerge(ExperienceOrb,int,int)Z} forwarding to the 4-arg version with
 * {@code ORB_GROUPS_PER_AREA}. ShadowMaterializer asks {@link #hasMethodBridge} so it treats the method as existing.
 */
public final class ExperienceOrbCanMergeBridgeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.ExperienceOrbCanMergeBridgeTransformer");
	private static final String TARGET = "net/minecraft/world/entity/ExperienceOrb";
	private static final String BRIDGE_DESC = "(Lnet/minecraft/world/entity/ExperienceOrb;II)Z";
	private static final String REAL_DESC = "(Lnet/minecraft/world/entity/ExperienceOrb;III)Z";

	/** true if this transformer adds the bridge {@code nameAndDesc} to {@code internalName} (slash or dotted class name) */
	public static boolean hasMethodBridge(String internalName, String nameAndDesc) {
		return TARGET.equals(internalName.replace('.', '/')) && ("canMerge" + BRIDGE_DESC).equals(nameAndDesc);
	}

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(buf).accept(cn, 0);
			boolean hasBridge = false, hasReal = false, hasGroups = false;

			for (MethodNode m : cn.methods) {
				if (!m.name.equals("canMerge")) continue;
				if (m.desc.equals(BRIDGE_DESC)) hasBridge = true;
				if (m.desc.equals(REAL_DESC)) hasReal = true;
			}

			for (FieldNode f : cn.fields) {
				if (f.name.equals("ORB_GROUPS_PER_AREA") && f.desc.equals("I")) hasGroups = true;
			}

			if (hasBridge || !hasReal || !hasGroups) return null;

			MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "canMerge", BRIDGE_DESC, null, null);
			InsnList il = new InsnList();
			il.add(new VarInsnNode(Opcodes.ALOAD, 0));
			il.add(new VarInsnNode(Opcodes.ILOAD, 1));
			il.add(new VarInsnNode(Opcodes.ILOAD, 2));
			il.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "ORB_GROUPS_PER_AREA", "I"));
			il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, "canMerge", REAL_DESC, false));
			il.add(new InsnNode(Opcodes.IRETURN));
			bridge.instructions = il;
			bridge.maxStack = 4;
			bridge.maxLocals = 3;
			cn.methods.add(bridge);

			ClassWriter w = new ClassWriter(0);
			cn.accept(w);
			//LOGGER.info("PATCHED " + className + " (added bridge canMerge" + BRIDGE_DESC + " -> 4-arg version, Clumps/ServerCore)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}