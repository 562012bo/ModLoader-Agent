package net.fabricmc.loader.impl.transformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Plain ASM patches of game/plugin classes (bridges for members Paper/Leaf removed, thread-safety, null-safety, visibility).
 * These are NOT mappings: {@link PaperModSignatureCompat} only rewrites mods according to mappings.tiny, and calls
 * {@link #apply} once per loaded class. All patches here are always on.
 */
public final class AsmPatches {
	private AsmPatches() { }

	/** runs every patch below on one class */
	public static byte[] apply(String name, byte[] bytes) {
		bytes = applyMissingFieldBridges(name, bytes);
		bytes = applyMissingMethodBridges(name, bytes);
		bytes = applyFerriteCoreThreadSafety(name, bytes);
		bytes = applyCraftTagNullSafety(name, bytes);
		bytes = applyMakePublic(name, bytes);
		bytes = applyMaterialNullSafety(name, bytes);
		return bytes;
	}

	/**
	 * Paper/Leaf's Moonrise chunk system removed ServerLevel.entityManager, but mods still declare an {@code @Accessor}/{@code @Shadow}
	 * for it (Chunky's ServerLevelMixin#getEntityManager) and Mixin aborts the whole bootstrap with InvalidAccessorException.
	 * Re-add the field (always null; the real entity storage lives in Moonrise now) so such mixins apply.
	 * Rows: class, field name, field descriptor.
	 */
	private static final String[][] MISSING_FIELD_BRIDGES = {
		{"net.minecraft.server.level.ServerLevel", "entityManager", "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;"},
	};

	/**
	 * Methods that vanilla/Fabric mods still reference but Paper/Leaf removed or changed. Re-added as thin bridges so that mods'
	 * {@code @Shadow} / injectors find them (ShadowMaterializer treats these as existing, see {@link #hasMethodBridge}).
	 * Rows: class (dotted), method name, descriptor.
	 *
	 * <p>ExperienceOrb.canMerge(ExperienceOrb,int,int): Leaf's "Cache XP orb merge parameters" patch replaced it with
	 * canMerge(orb, id, value, groupsPerArea). Clumps' MixinExperienceOrb @Shadows the old one; without a real method
	 * ShadowMaterializer had to merge a stub, and ServerCore's @ModifyConstant on canMerge then failed with
	 * "cannot inject into ... merged by com.blamejared.clumps.mixin.MixinExperienceOrb".
	 */
	private static final String[][] MISSING_METHOD_BRIDGES = {
		{"net.minecraft.world.entity.ExperienceOrb", "canMerge", "(Lnet/minecraft/world/entity/ExperienceOrb;II)Z"},
	};

	/** true if a bridge for this (internal class name, method name + descriptor) is added by {@link #applyMissingMethodBridges} */
	static boolean hasMethodBridge(String internalName, String nameAndDesc) {
		String dotted = internalName.replace('/', '.');

		for (String[] row : MISSING_METHOD_BRIDGES) {
			if (row[0].equals(dotted) && (row[1] + row[2]).equals(nameAndDesc)) return true;
		}

		return false;
	}

	/**
	 * FerriteCore's BlockStateCacheImpl keeps its de-duplication maps in plain (not thread-safe) fastutil hash maps, because
	 * vanilla builds block state caches on one thread. Leaf runs Blocks.&lt;clinit&gt; -> initCache in a PARALLEL stream, so two threads
	 * rehash the same map at once: "ArrayIndexOutOfBoundsException: Index 512 out of bounds for length 257" in
	 * Object2ObjectOpenCustomHashMap.rehash. Making every deduplicate* method synchronized serialises access to those maps.
	 */
	private static byte[] applyFerriteCoreThreadSafety(String name, byte[] bytes) {
		if (!"malte0811.ferritecore.impl.BlockStateCacheImpl".equals(name.replace('/', '.'))
				|| "false".equalsIgnoreCase(System.getProperty("ankhangbo.ferriteCoreThreadSafe"))) {
			return bytes;
		}

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int changed = 0;

			for (MethodNode m : node.methods) {
				if (!m.name.startsWith("deduplicate") || (m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) continue;
				m.access |= Opcodes.ACC_SYNCHRONIZED;
				changed++;
			}

			if (changed == 0) return bytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			Log.info(LogCategory.GAME_PATCH, "Made %d deduplicate* method(s) of FerriteCore's BlockStateCacheImpl synchronized (Leaf builds block state caches in parallel)", changed);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "FerriteCore thread-safety patch failed for %s: %s", name, t);
			return bytes;
		}
	}

	/**
	 * Members that vanilla/Fabric mods call but Paper/Leaf made more restrictive. Rows: class (dotted), method name, descriptor.
	 *
	 * <p>ChunkMap.TrackedEntity.updatePlayers(List): public in vanilla, but Leaf's "Multithreaded Tracker" patch made it private, so
	 * Very Many Players (NearbyEntityTracking.tickStaging) died every tick with
	 * "IllegalAccessError ... tried to access private method ChunkMap$TrackedEntity.updatePlayers(java.util.List)".
	 */
	private static final String[][] MAKE_PUBLIC = {
		{"net.minecraft.server.level.ChunkMap$TrackedEntity", "updatePlayers", "(Ljava/util/List;)V"},
	};

	private static byte[] applyMakePublic(String name, byte[] bytes) {
		String dotted = name.replace('/', '.');
		boolean candidate = false;

		for (String[] row : MAKE_PUBLIC) {
			if (row[0].equals(dotted)) { candidate = true; break; }
		}

		if (!candidate || "false".equalsIgnoreCase(System.getProperty("ankhangbo.makePublic"))) return bytes;

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int changed = 0;

			for (String[] row : MAKE_PUBLIC) {
				if (!row[0].equals(dotted)) continue;

				for (MethodNode m : node.methods) {
					if (m.name.equals(row[1]) && m.desc.equals(row[2]) && (m.access & Opcodes.ACC_PUBLIC) == 0) {
						m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
						changed++;
					}
				}
			}

			if (changed == 0) return bytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			Log.info(LogCategory.GAME_PATCH, "PATCHED %s (made %d method(s) public again: they are private in Leaf but mods call them)", name, changed);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "make-public patch failed for %s: %s", name, t);
			return bytes;
		}
	}

	/** target of the rewritten returns, see {@link #applyMaterialNullSafety}; must stay public (called from game classes) */
	public static Object nonNullMaterial(Object m, Object air) {
		return m == null ? air : m;
	}

	/** no compile-time dependency on Bukkit: Material.AIR is recognised by class + name */
	public static boolean isAirMaterial(Object o) {
		return o instanceof Enum && "AIR".equals(((Enum<?>) o).name()) && "org.bukkit.Material".equals(o.getClass().getName());
	}

	/**
	 * CraftMagicNumbers.getMaterial(Block|Item) returns null for modded blocks/items (no Bukkit Material exists; the Item map even
	 * stores key->null so getOrDefault(..., AIR) still yields null). Plugins then NPE everywhere (WorldGuard, CoreProtect on
	 * ItemStack.getType()/BlockData.getMaterial()). Return Material.AIR instead, which is what Bukkit already does for unknown items.
	 */
	private static byte[] applyMaterialNullSafety(String name, byte[] bytes) {
		if (!"org.bukkit.craftbukkit.util.CraftMagicNumbers".equals(name.replace('/', '.'))
				|| "false".equalsIgnoreCase(System.getProperty("ankhangbo.materialNullSafe"))) {
			return bytes;
		}

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int changed = 0;

			for (MethodNode m : node.methods) {
				if (!m.name.equals("getMaterial") || !m.desc.equals("(Lnet/minecraft/world/level/block/Block;)Lorg/bukkit/Material;")
						&& !m.desc.equals("(Lnet/minecraft/world/item/Item;)Lorg/bukkit/Material;")) continue;
				if ((m.access & Opcodes.ACC_STATIC) == 0 || m.instructions == null) continue;

				for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn.getOpcode() == Opcodes.ARETURN) {
						org.objectweb.asm.tree.InsnList add = new org.objectweb.asm.tree.InsnList();
						add.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, "org/bukkit/Material", "AIR", "Lorg/bukkit/Material;"));
						add.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
								"net/fabricmc/loader/impl/transformer/AsmPatches", "nonNullMaterial",
								"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
						add.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "org/bukkit/Material"));
						m.instructions.insertBefore(insn, add);
						changed++;
					}
				}
			}

			if (changed == 0) return bytes;

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			Log.info(LogCategory.GAME_PATCH, "PATCHED %s (getMaterial(Block/Item) returns AIR instead of null for modded content)", name);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "material null-safety patch failed for %s: %s", name, t);
			return bytes;
		}
	}

	private static final java.util.Set<String> CRAFT_TAG_CLASSES = new java.util.HashSet<>(java.util.Arrays.asList(
			"org.bukkit.craftbukkit.tag.CraftBlockTag", "org.bukkit.craftbukkit.tag.CraftItemTag", "org.bukkit.craftbukkit.tag.CraftEntityTag",
			"org.bukkit.craftbukkit.tag.CraftFluidTag", "org.bukkit.craftbukkit.tag.CraftDamageTag"));

	/** target of the rewritten call, see {@link #applyCraftTagNullSafety}; must stay public (called from game classes) */
	public static java.util.stream.Collector<Object, ?, java.util.Set<Object>> nonNullUnmodifiableSet() {
		return java.util.stream.Collectors.filtering(o -> o != null && !isAirMaterial(o), java.util.stream.Collectors.toUnmodifiableSet());
	}

	/**
	 * Bukkit tag views (Tag#getValues) map every vanilla/modded entry to a Bukkit enum constant and collect them with
	 * Collectors.toUnmodifiableSet(), which throws NPE when a MODDED block/item/entity has no Bukkit counterpart (minecraftToBukkit
	 * returns null). CoreProtect hit it in Bukkit_v1_20.initializeTaggedBlocks (CraftBlockTag.getValues -> "pe" is null) and its onEnable
	 * aborted. Drop the null entries instead: modded content simply is not part of the Bukkit view of the tag.
	 */
	private static byte[] applyCraftTagNullSafety(String name, byte[] bytes) {
		if (!CRAFT_TAG_CLASSES.contains(name.replace('/', '.')) || "false".equalsIgnoreCase(System.getProperty("ankhangbo.craftTagNullSafe"))) {
			return bytes;
		}

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int changed = 0;

			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;

				for (AbstractInsnNode in : m.instructions) {
					if (in instanceof org.objectweb.asm.tree.MethodInsnNode) {
						org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) in;

						if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.owner.equals("java/util/stream/Collectors") && mi.name.equals("toUnmodifiableSet")) {
							mi.owner = "net/fabricmc/loader/impl/transformer/AsmPatches";
							mi.name = "nonNullUnmodifiableSet";
							mi.itf = false;
							changed++;
						}
					}
				}
			}

			if (changed == 0) return bytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			Log.info(LogCategory.GAME_PATCH, "PATCHED %s (Tag#getValues drops entries with no Bukkit counterpart instead of throwing NPE, e.g. CoreProtect)", name);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "craft tag null-safety patch failed for %s: %s", name, t);
			return bytes;
		}
	}

	private static byte[] applyMissingMethodBridges(String name, byte[] bytes) {
		if ("false".equalsIgnoreCase(System.getProperty("ankhangbo.missingMethodBridges"))) return bytes;
		if (!"net.minecraft.world.entity.ExperienceOrb".equals(name.replace('/', '.'))) return bytes;

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			String bridgeDesc = "(Lnet/minecraft/world/entity/ExperienceOrb;II)Z";
			String realDesc = "(Lnet/minecraft/world/entity/ExperienceOrb;III)Z";
			boolean hasBridge = false;
			boolean hasReal = false;
			boolean hasGroups = false;

			for (MethodNode m : node.methods) {
				if (!m.name.equals("canMerge")) continue;
				if (m.desc.equals(bridgeDesc)) hasBridge = true;
				if (m.desc.equals(realDesc)) hasReal = true;
			}

			for (org.objectweb.asm.tree.FieldNode f : node.fields) {
				if (f.name.equals("ORB_GROUPS_PER_AREA") && f.desc.equals("I")) hasGroups = true;
			}

			if (hasBridge || !hasReal || !hasGroups) return bytes;

			MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "canMerge", bridgeDesc, null, null);
			org.objectweb.asm.tree.InsnList il = new org.objectweb.asm.tree.InsnList();
			il.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
			il.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 1));
			il.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 2));
			il.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "ORB_GROUPS_PER_AREA", "I"));
			il.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "canMerge", realDesc, false));
			il.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
			bridge.instructions = il;
			bridge.maxStack = 4;
			bridge.maxLocals = 3;
			node.methods.add(bridge);
			Log.info(LogCategory.GAME_PATCH, "Added bridge %s.canMerge%s -> %s (Leaf's Cache XP orb merge parameters)", name, bridgeDesc, realDesc);

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "missing-method bridge failed for %s: %s", name, t);
			return bytes;
		}
	}

	private static byte[] applyMissingFieldBridges(String name, byte[] bytes) {
		if ("false".equalsIgnoreCase(System.getProperty("ankhangbo.missingFieldBridges"))) return bytes;
		String dotted = name.replace('/', '.');
		ClassNode node = null;
		int added = 0;

		for (String[] row : MISSING_FIELD_BRIDGES) {
			if (!row[0].equals(dotted)) continue;

			try {
				if (node == null) {
					node = new ClassNode();
					new ClassReader(bytes).accept(node, 0);
				}

				boolean has = false;
				for (org.objectweb.asm.tree.FieldNode f : node.fields) {
					if (f.name.equals(row[1])) { has = true; break; }
				}

				if (has) continue;

				node.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE, row[1], row[2], null, null));
				added++;
				Log.info(LogCategory.GAME_PATCH, "Added missing field %s.%s : %s (null; removed by Paper/Leaf, still referenced by mods' @Accessor/@Shadow)", name, row[1], row[2]);
			} catch (Throwable t) {
				Log.warn(LogCategory.GAME_PATCH, "missing-field bridge failed for %s: %s", name, t);
				return bytes;
			}
		}

		if (added == 0) return bytes;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}