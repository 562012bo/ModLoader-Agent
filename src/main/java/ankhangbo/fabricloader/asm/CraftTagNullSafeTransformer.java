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
 * Bukkit tag views ({@code Tag#getValues}) map every vanilla/modded entry to a Bukkit constant and collect them with
 * {@code Collectors.toUnmodifiableSet()}, which throws NPE when a MODDED block/item/entity has no Bukkit counterpart
 * ({@code minecraftToBukkit} returns null). CoreProtect hit it in {@code Bukkit_v1_20.initializeTaggedBlocks}
 * (CraftBlockTag.getValues) and its onEnable aborted. Redirects that call to
 * {@link ankhangbo.fabricloader.compat.NullSafeTags#nonNullUnmodifiableSet()}, which drops the null entries: modded content is
 * simply not part of the Bukkit view of the tag. The helper only uses JDK types in its signature, so it links from the game loader.
 */
public final class CraftTagNullSafeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.CraftTagNullSafeTransformer");
	private static final String PREFIX = "org/bukkit/craftbukkit/tag/Craft";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain pd, byte[] buf) {
		if (className == null || !className.startsWith(PREFIX) || !className.endsWith("Tag")) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(buf).accept(cn, 0);
			int changed = 0;

			for (MethodNode m : cn.methods) {
				for (AbstractInsnNode in : m.instructions) {
					if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
							&& mi.owner.equals("java/util/stream/Collectors") && mi.name.equals("toUnmodifiableSet")) {
						mi.owner = "ankhangbo/fabricloader/compat/NullSafeTags";
						mi.name = "nonNullUnmodifiableSet";
						mi.itf = false;
						changed++;
					}
				}
			}

			if (changed == 0) return null;

			ClassWriter w = new ClassWriter(0);
			cn.accept(w);
			//LOGGER.info("PATCHED " + className + " (Tag#getValues drops entries with no Bukkit counterpart instead of throwing NPE)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}