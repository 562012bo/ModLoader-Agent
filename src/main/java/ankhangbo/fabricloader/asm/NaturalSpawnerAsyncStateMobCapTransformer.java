package ankhangbo.fabricloader.asm;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
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
 * Leaf's async mob spawning builds its {@code NaturalSpawner.SpawnState} in {@code createStateAsync(int, Iterable, ServerLevel)}
 * with a {@code null} {@code LocalMobCapCalculator} (it counts per player itself). Vanilla always has one, so mods that touch
 * {@code SpawnState.localMobCapCalculator} crash the whole server tick, e.g. ServerCore's redirect in
 * {@code getFilteredSpawningCategories}: {@code Cannot read field "chunkMap" because "state.localMobCapCalculator" is null}.
 *
 * <p>Fix: pass {@code new LocalMobCapCalculator(level.getChunkSource().chunkMap)} instead of {@code null}. Leaf's own code already
 * null-checks it ({@code afterSpawn}) so it simply keeps a per-player count too; {@code ChunkMap.getPlayersCloseForSpawning}
 * is implemented by Moonrise. Disable with {@code -Dankhangbo.asyncSpawnStateMobCap=false}.
 */
public final class NaturalSpawnerAsyncStateMobCapTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.asm.NaturalSpawnerAsyncStateMobCapTransformer");
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.asyncSpawnStateMobCap"));

	private static final String TARGET = "net/minecraft/world/level/NaturalSpawner";
	private static final String SPAWN_STATE = "net/minecraft/world/level/NaturalSpawner$SpawnState";
	private static final String CALC = "net/minecraft/world/level/LocalMobCapCalculator";
	private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
	private static final String CHUNK_CACHE = "net/minecraft/server/level/ServerChunkCache";
	private static final String CHUNK_MAP = "net/minecraft/server/level/ChunkMap";
	private static final String METHOD = "createStateAsync";
	private static final String METHOD_DESC = "(ILjava/lang/Iterable;L" + SERVER_LEVEL + ";)L" + SPAWN_STATE + ";";

	@Override
	@SuppressWarnings("unchecked")
	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
			ProtectionDomain protectionDomain, byte[] classfileBuffer) {
		if (!ENABLED || !TARGET.equals(className)) return null;

		try {
			ClassNode cn = new ClassNode();
			new ClassReader(classfileBuffer).accept(cn, 0);

			for (MethodNode m : (List<MethodNode>) cn.methods) {
				if (!m.name.equals(METHOD) || !m.desc.equals(METHOD_DESC)) continue;

				// the new SpawnState(...) call: its LocalMobCapCalculator argument is the 4th, i.e. the ACONST_NULL before the last argument
				MethodInsnNode ctor = null;
				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
						MethodInsnNode call = (MethodInsnNode) insn;
						if (call.owner.equals(SPAWN_STATE) && call.name.equals("<init>") && call.desc.contains("L" + CALC + ";")) ctor = call;
					}
				}
				if (ctor == null) break;

				AbstractInsnNode nullInsn = null;
				for (AbstractInsnNode n = ctor.getPrevious(); n != null; n = n.getPrevious()) {
					if (n.getOpcode() == Opcodes.ACONST_NULL) {
						nullInsn = n;
						break;
					}
					if (n.getOpcode() == Opcodes.NEW && ((TypeInsnNode) n).desc.equals(SPAWN_STATE)) break;
				}
				if (nullInsn == null) break;

				InsnList calc = new InsnList();
				calc.add(new TypeInsnNode(Opcodes.NEW, CALC));
				calc.add(new InsnNode(Opcodes.DUP));
				calc.add(new VarInsnNode(Opcodes.ALOAD, 2)); // ServerLevel level (static method: 0=int, 1=Iterable, 2=level)
				calc.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, "getChunkSource", "()L" + CHUNK_CACHE + ";", false));
				calc.add(new FieldInsnNode(Opcodes.GETFIELD, CHUNK_CACHE, "chunkMap", "L" + CHUNK_MAP + ";"));
				calc.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CALC, "<init>", "(L" + CHUNK_MAP + ";)V", false));
				m.instructions.insertBefore(nullInsn, calc);
				m.instructions.remove(nullInsn);

				ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
				cn.accept(w);
				LOGGER.info("PATCHED " + className + "#" + METHOD + " (SpawnState gets a LocalMobCapCalculator instead of null, for ServerCore & co)");
				return w.toByteArray();
			}

			LOGGER.warning(className + "#" + METHOD + ": expected 'new SpawnState(..., null, ...)' not found - NOT patched");
			return null;
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className, t);
			return null;
		}
	}
}