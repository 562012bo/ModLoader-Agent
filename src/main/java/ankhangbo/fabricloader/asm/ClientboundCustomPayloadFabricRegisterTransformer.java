package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
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
 * Fixes the join failure
 * {@code EncoderException: Failed to encode packet 'clientbound/minecraft:custom_payload' (minecraft:register)
 * Caused by: ClassCastException: DiscardedPayload cannot be cast to RegistrationPayload}
 * WITHOUT dropping the packet.
 *
 * <p>Paper builds the plugin-channel announcement as a {@code DiscardedPayload} with id
 * {@code minecraft:register}; Fabric's networking API registered a codec for that id that only accepts its own
 * {@code RegistrationPayload}. At the start of {@code ClientboundCustomPayloadPacket(CustomPacketPayload)} the
 * parameter is replaced by {@code FabricPayloadBridge.convert(payload)}, which swaps just that one payload type
 * (same bytes on the wire, correct Java type). Every other payload is returned untouched, so this covers every
 * place that creates such a packet (join, plugin messages, configuration phase), not only one call site.
 *
 * <p>Straight-line code placed before the super() call: it only touches the parameter, never {@code this}, which
 * the JVM verifier allows, and needs no stack-map frame.
 */
public final class ClientboundCustomPayloadFabricRegisterTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.ClientboundCustomPayloadFabricRegisterTransformer");

	private static final String TARGET = "net/minecraft/network/protocol/common/ClientboundCustomPayloadPacket";
	private static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	private static final String BRIDGE = "ankhangbo/fabricloader/compat/FabricPayloadBridge";

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
				if (m.name.equals("<init>") && m.desc.equals("(L" + PAYLOAD + ";)V")) {
					InsnList add = new InsnList();
					add.add(new VarInsnNode(Opcodes.ALOAD, 1));
					add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "convert",
							"(Ljava/lang/Object;)Ljava/lang/Object;", false));
					add.add(new TypeInsnNode(Opcodes.CHECKCAST, PAYLOAD));
					add.add(new VarInsnNode(Opcodes.ASTORE, 1));
					m.instructions.insert(add);
					patched = true;
				}
			}
			if (!patched) {
				LOGGER.warning("ClientboundCustomPayloadPacket(CustomPacketPayload) not found - NOT patched");
				return null;
			}
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(w);
			LOGGER.info("PATCHED ClientboundCustomPayloadPacket (Paper minecraft:register -> Fabric RegistrationPayload)");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}