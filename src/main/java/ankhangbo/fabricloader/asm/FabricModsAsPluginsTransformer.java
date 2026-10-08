package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes Fabric mods show up like plugins.
 *
 * <ol>
 *   <li>{@code PaperPluginsCommand#execute}: before the final return, call
 *       {@code FabricModBridge.sendModsSection(sender)} -> "Fabric Mod (N):" + names in /plugins.</li>
 *   <li>{@code CraftServer#enablePlugins(PluginLoadOrder)}: at the head call
 *       {@code FabricModBridge.registerBukkitPlugins(this)} (idempotent) -> every mod becomes a
 *       Plugin object in Bukkit's plugin manager (getPlugins/getPlugin/isPluginEnabled).</li>
 *   <li>{@code GraphDependencyContext#hasDependency} and {@code MetaDependencyTree#hasDependency}:
 *       the {@code contains} call becomes {@code FabricModBridge.containsOrMod}, so plugin.yml
 *       {@code depend:}/{@code softdepend:} on a mod id is satisfied.</li>
 *   <li>{@code PaperPluginInstanceManager#disablePlugin}: return immediately for those mod
 *       "plugins"; Paper otherwise throws "Only expects java plugins." when it disables everything
 *       on shutdown/reload.</li>
 * </ol>
 * Every insertion is straight-line except the disablePlugin guard, which gets a hand-written
 * F_SAME frame (valid because it sits at the very start of the method), so no class loading is
 * needed while these classes are defined.
 */
public final class FabricModsAsPluginsTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.FabricModsAsPluginsTransformer");

	private static final String BRIDGE = "ankhangbo/fabricloader/compat/FabricModBridge";

	private static final String PLUGINS_CMD = "io/papermc/paper/command/PaperPluginsCommand";
	private static final String CRAFT_SERVER = "org/bukkit/craftbukkit/CraftServer";
	private static final String GRAPH_CTX = "io/papermc/paper/plugin/entrypoint/dependency/GraphDependencyContext";
	private static final String META_TREE = "io/papermc/paper/plugin/entrypoint/dependency/MetaDependencyTree";
	private static final String INSTANCE_MGR = "io/papermc/paper/plugin/manager/PaperPluginInstanceManager";

	@Override
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (className == null) {
			return null;
		}
		switch (className) {
			case PLUGINS_CMD:
			case CRAFT_SERVER:
			case GRAPH_CTX:
			case META_TREE:
			case INSTANCE_MGR:
				break;
			default:
				return null;
		}
		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);
			boolean patched;
			switch (className) {
				case PLUGINS_CMD:
					patched = patchPluginsCommand(cn);
					break;
				case CRAFT_SERVER:
					patched = patchEnablePlugins(cn);
					break;
				case INSTANCE_MGR:
					patched = patchDisablePlugin(cn);
					break;
				default:
					patched = patchHasDependency(cn);
					break;
			}
			if (!patched) {
				LOGGER.warning("Fabric-mods-as-plugins hook NOT installed in " + className
						+ " (different server build?)");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (Fabric mods as plugins)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static boolean patchPluginsCommand(ClassNode cn) {
		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (!m.name.equals("execute") || !m.desc.endsWith(")I")
					|| !m.desc.startsWith("(Lcom/mojang/brigadier/context/CommandContext;")) {
				continue;
			}
			// local slot of "final CommandSender sender = context.getSource().getSender();"
			int senderSlot = -1;
			AbstractInsnNode last = null;
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (senderSlot < 0 && insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals("getSender")) {
					AbstractInsnNode next = insn.getNext();
					if (next instanceof VarInsnNode && next.getOpcode() == Opcodes.ASTORE) {
						senderSlot = ((VarInsnNode) next).var;
					}
				}
				if (insn.getOpcode() == Opcodes.IRETURN) {
					last = insn;
				}
			}
			if (senderSlot < 0 || last == null) {
				continue;
			}
			InsnList add = new InsnList();
			add.add(new VarInsnNode(Opcodes.ALOAD, senderSlot));
			add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "sendModsSection",
					"(Ljava/lang/Object;)V", false));
			m.instructions.insertBefore(last, add); // stack effect 0, fine with the int already pushed
			return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static boolean patchEnablePlugins(ClassNode cn) {
		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (m.name.equals("enablePlugins") && m.desc.equals("(Lorg/bukkit/plugin/PluginLoadOrder;)V")) {
				InsnList add = new InsnList();
				add.add(new VarInsnNode(Opcodes.ALOAD, 0));
				add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "registerBukkitPlugins",
						"(Ljava/lang/Object;)V", false));
				m.instructions.insert(add);
				return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static boolean patchHasDependency(ClassNode cn) {
		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (!m.name.equals("hasDependency") || !m.desc.equals("(Ljava/lang/String;)Z")) {
				continue;
			}
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode) {
					MethodInsnNode mi = (MethodInsnNode) insn;
					if (mi.name.equals("contains") && mi.desc.equals("(Ljava/lang/Object;)Z")) {
						m.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "containsOrMod",
								"(Ljava/util/Collection;Ljava/lang/Object;)Z", false));
						return true;
					}
				}
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static boolean patchDisablePlugin(ClassNode cn) {
		for (MethodNode m : (List<MethodNode>) cn.methods) {
			if (m.name.equals("disablePlugin") && m.desc.equals("(Lorg/bukkit/plugin/Plugin;)V")
					&& (m.access & Opcodes.ACC_STATIC) == 0) {
				LabelNode go = new LabelNode();
				InsnList add = new InsnList();
				add.add(new VarInsnNode(Opcodes.ALOAD, 1));
				add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "isModPlugin",
						"(Ljava/lang/Object;)Z", false));
				add.add(new JumpInsnNode(Opcodes.IFEQ, go));
				add.add(new InsnNode(Opcodes.RETURN));
				add.add(go);
				add.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
				m.instructions.insert(add);
				return true;
			}
		}
		return false;
	}
}