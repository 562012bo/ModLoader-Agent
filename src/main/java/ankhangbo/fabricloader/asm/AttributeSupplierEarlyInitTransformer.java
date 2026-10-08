package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Leaf replaces {@code AttributeSupplier}'s map by {@code AttributeInstanceArrayMap}, whose static holder
 * {@code RegistryTypeManager} throws {@code ExceptionInInitializerError: RegistryTypeManager initialize before registries
 * bootstrap} unless the ATTRIBUTE and ACTIVITY registries already have content - and a failed class initialisation can never be
 * retried. In a Fabric setup Create/Railways register their entities (and call
 * {@code FabricDefaultAttributeRegistry.register -> AttributeSupplier.Builder.build()}) from {@code Blocks.<clinit>}, which runs
 * BEFORE {@code BuiltInRegistries.createContents()} fills those registries.
 *
 * <p>Fix: in {@code AttributeSupplier.<init>(Map)} replace
 * {@code new AttributeInstanceArrayMap(map)} with {@code AttributeMapFactory.make(AttributeInstanceArrayMap.class, map,
 * ATTRIBUTE.size() * ACTIVITY.size())}: a plain HashMap while the registries are still empty, Leaf's array map otherwise
 * (so Leaf's optimisation is kept for everything built after the bootstrap). Straight-line code, no stack map frame changes.
 */
public final class AttributeSupplierEarlyInitTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.AttributeSupplierEarlyInitTransformer");

	private static final String TARGET = "net/minecraft/world/entity/ai/attributes/AttributeSupplier";
	private static final String ARRAY_MAP = "org/dreeam/leaf/util/map/AttributeInstanceArrayMap";
	private static final String HELPER = "ankhangbo/fabricloader/compat/AttributeMapFactory";
	private static final String BUILTIN = "net/minecraft/core/registries/BuiltInRegistries";
	private static final String REGISTRY = "net/minecraft/core/Registry";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			int patched = 0;

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (!m.name.equals("<init>")) continue;

				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof TypeInsnNode) || insn.getOpcode() != Opcodes.NEW || !((TypeInsnNode) insn).desc.equals(ARRAY_MAP)) continue;

					AbstractInsnNode dup = insn.getNext();
					AbstractInsnNode load = dup != null ? dup.getNext() : null;
					AbstractInsnNode ctor = load != null ? load.getNext() : null;

					if (dup == null || dup.getOpcode() != Opcodes.DUP || load == null || load.getOpcode() != Opcodes.ALOAD
							|| !(ctor instanceof MethodInsnNode) || ctor.getOpcode() != Opcodes.INVOKESPECIAL
							|| !((MethodInsnNode) ctor).owner.equals(ARRAY_MAP) || !((MethodInsnNode) ctor).name.equals("<init>")
							|| !((MethodInsnNode) ctor).desc.equals("(Ljava/util/Map;)V")) {
						continue;
					}

					// [Class] before the existing ALOAD(map), then [int] and the call instead of NEW/DUP/INVOKESPECIAL
					m.instructions.insertBefore(insn, new LdcInsnNode(Type.getObjectType(ARRAY_MAP)));

					InsnList after = new InsnList();
					after.add(new FieldInsnNode(Opcodes.GETSTATIC, BUILTIN, "ATTRIBUTE", "L" + REGISTRY + ";"));
					after.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, REGISTRY, "size", "()I", true));
					after.add(new FieldInsnNode(Opcodes.GETSTATIC, BUILTIN, "ACTIVITY", "L" + REGISTRY + ";"));
					after.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, REGISTRY, "size", "()I", true));
					after.add(new InsnNode(Opcodes.IMUL));
					after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "make",
							"(Ljava/lang/Class;Ljava/util/Map;I)Ljava/util/Map;", false));
					m.instructions.insert(ctor, after);

					m.instructions.remove(ctor);
					m.instructions.remove(dup);
					m.instructions.remove(insn);
					patched++;
					break;
				}
			}

			if (patched == 0) {
				LOGGER.warning(className + ": 'new AttributeInstanceArrayMap(Map)' not found - NOT patched (no Leaf attribute map in this build?)");
				return null;
			}

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (attribute map falls back to HashMap before the registries are filled, for early Fabric registrations)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}
