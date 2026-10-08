package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code CraftStatistic.statistics} (Identifier &lt;-&gt; Bukkit Statistic) is built as an {@code ImmutableBiMap}, so a mod
 * statistic can never be added. Right after that assignment, inside {@code <clinit>} (the only place a static final may be
 * assigned), the field is re-assigned to {@code HashBiMap.create(oldMap)}, which has the same content, is mutable and
 * supports {@code inverse()} exactly as before. {@code StatisticBridge.register} fills it afterwards.
 */
public final class CraftStatisticMutableMapTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.CraftStatisticMutableMapTransformer");

	private static final String TARGET = "org/bukkit/craftbukkit/CraftStatistic";

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
				if (!m.name.equals("<clinit>")) {
					continue;
				}
				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn.getOpcode() == Opcodes.PUTSTATIC && ((FieldInsnNode) insn).name.equals("statistics")
							&& ((FieldInsnNode) insn).desc.equals("Lcom/google/common/collect/BiMap;")) {
						FieldInsnNode f = (FieldInsnNode) insn;
						InsnList add = new InsnList();
						add.add(new FieldInsnNode(Opcodes.GETSTATIC, f.owner, f.name, f.desc));
						add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/collect/HashBiMap", "create",
								"(Ljava/util/Map;)Lcom/google/common/collect/HashBiMap;", false));
						add.add(new FieldInsnNode(Opcodes.PUTSTATIC, f.owner, f.name, f.desc));
						m.instructions.insert(insn, add);
						patched = true;
						break;
					}
				}
			}
			if (!patched) {
				LOGGER.warning("CraftStatistic.statistics assignment not found - NOT patched (different Paper version?)");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED CraftStatistic (statistics map is mutable, mod statistics can be added)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}