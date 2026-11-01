package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
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
 * Leaf/Paper replaced vanilla's {@code ChunkHolder} future machinery by Moonrise's {@code NewChunkHolder}; the vanilla
 * methods are still there (so mods compile/link) but their bodies are just {@code throw new UnsupportedOperationException()}.
 * Mods written for vanilla/Fabric call them, e.g. ServerCore's {@code ChunkManager.getChunkFromHolder} ->
 * {@code ChunkHolder.getFullChunkFuture()}, which (through {@code Level.updateNeighbourForOutputSignal}) throws on every
 * block entity that calls {@code setChanged()}: "BlockEntity threw exception ... UnsupportedOperationException".
 *
 * <p>This rewrites every such stub so it delegates to the Moonrise state of {@code this.newChunkHolder}:
 * <ul>
 * <li>{@code get(Full|Ticking|EntityTicking)ChunkFuture()} - an already completed future: {@code ChunkResult.of(chunk)} when the
 * chunk is at that level now, otherwise vanilla's {@code UNLOADED_LEVEL_CHUNK_FUTURE} (what mods get from {@code getNow(..)}
 * for a chunk that is not loaded yet)</li>
 * <li>{@code getSendSyncFuture()}/{@code getSaveSyncFuture()} - completed future, {@code addSendDependency}/{@code addSaveDependency}/
 * {@code updateFutures}/{@code callEventIfUnloading}/{@code setQueueLevel}/... - no-ops (Moonrise sends/saves/unloads on its own)</li>
 * <li>{@code getQueueLevel()} - the ticket level; {@code isReadyForSaving()} - true; {@code wasAccessibleSinceLastSave()} - false</li>
 * </ul>
 * Only methods whose body is exactly the stub (a {@code new UnsupportedOperationException} + {@code athrow}) are touched.
 * Disable with {@code -Dankhangbo.chunkHolderBridge=false}.
 */
public final class ChunkHolderMoonriseBridgeTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.ChunkHolderMoonriseBridgeTransformer");
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.chunkHolderBridge"));

	private static final String TARGET = "net/minecraft/server/level/ChunkHolder";
	private static final String NEW_HOLDER = "ca/spottedleaf/moonrise/patches/chunk_system/scheduling/NewChunkHolder";
	private static final String LEVEL_CHUNK = "net/minecraft/world/level/chunk/LevelChunk";
	private static final String CHUNK_RESULT = "net/minecraft/server/level/ChunkResult";
	private static final String CF = "java/util/concurrent/CompletableFuture";
	private static final String CF_DESC = "L" + CF + ";";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!ENABLED || !TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			boolean hasNewHolder = false;
			for (org.objectweb.asm.tree.FieldNode f : (List<org.objectweb.asm.tree.FieldNode>) cn.fields) {
				if (f.name.equals("newChunkHolder")) hasNewHolder = true;
			}
			if (!hasNewHolder) {
				LOGGER.warning(TARGET + ": no newChunkHolder field - NOT patched (not a Moonrise build?)");
				return null;
			}

			int patched = 0;
			StringBuilder names = new StringBuilder();

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (!isStub(m)) continue;

				InsnList body = bodyFor(m.name, m.desc);
				if (body == null) continue;

				m.instructions = body;
				m.tryCatchBlocks.clear();
				if (m.localVariables != null) m.localVariables.clear();
				m.maxStack = 0;
				m.maxLocals = 0;
				patched++;
				if (names.length() > 0) names.append(", ");
				names.append(m.name);
			}

			if (patched == 0) return null;

			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS); // no COMPUTE_FRAMES: frames are written by hand below
			cn.accept(w);
			LOGGER.info("PATCHED " + className + " (" + patched + " Unsupported-stub method(s) now delegate to Moonrise's NewChunkHolder: " + names + ")");
			return w.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}

	/** body is exactly: new UnsupportedOperationException; dup; invokespecial <init>()V; athrow (ignoring labels/lines/frames) */
	private static boolean isStub(MethodNode m) {
		if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || m.instructions == null) return false;

		boolean sawNew = false;
		boolean sawThrow = false;
		int real = 0;

		for (AbstractInsnNode insn : m.instructions.toArray()) {
			if (insn.getOpcode() < 0) continue;
			real++;
			if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW
					&& ((TypeInsnNode) insn).desc.equals("java/lang/UnsupportedOperationException")) sawNew = true;
			if (insn.getOpcode() == Opcodes.ATHROW) sawThrow = true;
		}

		return sawNew && sawThrow && real <= 5;
	}

	private static InsnList bodyFor(String name, String desc) {
		switch (name + desc) {
			case "getFullChunkFuture()" + CF_DESC:
				return futureOf("getFullChunkNow", null);
			case "getTickingChunkFuture()" + CF_DESC:
				return futureOf("getTickingChunk", null);
			case "getEntityTickingChunkFuture()" + CF_DESC:
				return futureOf("getTickingChunk", "isEntityTickingReady");
			case "getSendSyncFuture()" + CF_DESC:
			case "getSaveSyncFuture()" + CF_DESC:
				return completedNull();
			case "isReadyForSaving()Z":
				return constant(Opcodes.ICONST_1, Opcodes.IRETURN);
			case "wasAccessibleSinceLastSave()Z":
				return constant(Opcodes.ICONST_0, Opcodes.IRETURN);
			case "getQueueLevel()I": {
				InsnList l = new InsnList();
				l.add(new VarInsnNode(Opcodes.ALOAD, 0));
				l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET, "getTicketLevel", "()I", false));
				l.add(new InsnNode(Opcodes.IRETURN));
				return l;
			}
			default:
				// void no-ops: addSendDependency/addSaveDependency(CompletableFuture), setQueueLevel(int), refreshAccessibility(),
				// scheduleFullChunkPromotion(..), demoteFullChunk(..), callEventIfUnloading(ChunkMap), updateFutures(ChunkMap, Executor)
				if (desc.endsWith(")V")) {
					InsnList l = new InsnList();
					l.add(new InsnNode(Opcodes.RETURN));
					return l;
				}
				return null;
		}
	}

	private static InsnList constant(int push, int ret) {
		InsnList l = new InsnList();
		l.add(new InsnNode(push));
		l.add(new InsnNode(ret));
		return l;
	}

	private static InsnList completedNull() {
		InsnList l = new InsnList();
		l.add(new InsnNode(Opcodes.ACONST_NULL));
		l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CF, "completedFuture", "(Ljava/lang/Object;)" + CF_DESC, false));
		l.add(new InsnNode(Opcodes.ARETURN));
		return l;
	}

	/**
	 * {@code [if (!this.newChunkHolder.<readyCheck>()) return UNLOADED_LEVEL_CHUNK_FUTURE;]
	 * LevelChunk c = this.<chunkGetter>(); return c == null ? UNLOADED_LEVEL_CHUNK_FUTURE : completedFuture(ChunkResult.of(c));}
	 */
	private static InsnList futureOf(String chunkGetter, String readyCheck) {
		InsnList l = new InsnList();
		LabelNode notNull = new LabelNode();
		LabelNode notReady = new LabelNode();

		if (readyCheck != null) {
			l.add(new VarInsnNode(Opcodes.ALOAD, 0));
			l.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET, "newChunkHolder", "L" + NEW_HOLDER + ";"));
			l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, NEW_HOLDER, readyCheck, "()Z", false));
			l.add(new JumpInsnNode(Opcodes.IFEQ, notReady));
		}

		l.add(new VarInsnNode(Opcodes.ALOAD, 0));
		l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET, chunkGetter, "()L" + LEVEL_CHUNK + ";", false));
		l.add(new InsnNode(Opcodes.DUP));
		l.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
		l.add(new InsnNode(Opcodes.POP));
		l.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, "UNLOADED_LEVEL_CHUNK_FUTURE", CF_DESC));
		l.add(new InsnNode(Opcodes.ARETURN));

		l.add(notNull);
		l.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] { LEVEL_CHUNK }));
		// ChunkResult is an INTERFACE in Leaf/Paper (static interface method => InterfaceMethodref, itf = true)
		l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CHUNK_RESULT, "of", "(Ljava/lang/Object;)L" + CHUNK_RESULT + ";", true));
		l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CF, "completedFuture", "(Ljava/lang/Object;)" + CF_DESC, false));
		l.add(new InsnNode(Opcodes.ARETURN));

		if (readyCheck != null) {
			l.add(notReady);
			l.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
			l.add(new FieldInsnNode(Opcodes.GETSTATIC, TARGET, "UNLOADED_LEVEL_CHUNK_FUTURE", CF_DESC));
			l.add(new InsnNode(Opcodes.ARETURN));
		}

		return l;
	}
}