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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes {@code org.bukkit.craftbukkit.entity.CraftEntity} instantiable as a generic, fully-working
 * Bukkit wrapper for an entity Bukkit has no specific class for, and makes
 * {@code CraftEntity.getEntity(CraftServer, Entity)} use it instead of throwing
 * {@code AssertionError} for a modded entity type.
 *
 * <p>Companion to {@link CraftEntityTypeMinecraftToBukkitNullSafeTransformer}: once
 * {@code CraftEntityType.minecraftToBukkit} can return null for a modded entity type,
 * {@code CraftEntityTypes.getEntityTypeData(null)} safely returns null too (it's an
 * {@code EnumMap} lookup, and unlike {@code put}, {@code EnumMap.get(null)} never throws), so
 * execution reaches {@code getEntity}'s final {@code throw new AssertionError("Unknown entity ...")}
 * instead of the original {@code IllegalArgumentException}. This transformer replaces that throw
 * with a working fallback instead.
 *
 * <p>{@code CraftEntity} is declared {@code abstract}, but - confirmed against Leaf 26.1.2's real
 * decompiled source - it declares zero abstract methods of its own: it's the base wrapper for every
 * more specific {@code Craft*Entity} class and already implements 100% of Bukkit's {@code Entity}
 * interface itself generically (position, velocity, persistent data, teleport, etc.); marking it
 * {@code abstract} is purely an API design choice to steer callers toward the more specific
 * subclasses, not a sign of missing implementation. So stripping the {@code ACC_ABSTRACT} flag from
 * the class and instantiating {@code CraftEntity} directly is safe: it behaves exactly like the
 * fully generic entity wrapper it already is under the hood, just without any entity-type-specific
 * extras (which don't exist for a type Bukkit has no specific class for anyway). This mirrors the
 * same trick already used for {@code GrowingPlantHeadBlock#getMaxGrowthAge()} elsewhere in this
 * project, applied to a whole class instead of one method.
 *
 * <p>Plugins that call type-specific methods (e.g. cast the result to a more specific interface)
 * on an entity of a type Bukkit has never heard of would still fail - there's no way around that
 * without Bukkit shipping an actual class for the type - but the server itself, and any code that
 * only uses the generic {@code Entity} interface, now works instead of crashing.
 *
 * <p><b>Boats, living entities, horse-family entities, tameable animals, and projectiles are
 * special cases</b>: vanilla code frequently casts a fresh entity's Bukkit wrapper to a narrower
 * interface right after creating it - boats to {@code Vehicle} every tick
 * ({@code AbstractBoat#tick()}), any mob to {@code LivingEntity} when it spawns
 * ({@code CraftEventFactory#callCreatureSpawnEvent}, reached from spawn eggs, natural spawning,
 * {@code /summon}, etc.), any horse-family entity to {@code org.bukkit.entity.AbstractHorse} the
 * moment it's constructed ({@code AbstractHorse#createInventory()}), any tameable animal to
 * {@code org.bukkit.entity.Tameable} whenever it dies ({@code TamableAnimal#die()}), and any
 * projectile to {@code org.bukkit.entity.Projectile} the moment it's about to hit anything
 * ({@code Projectile#preHitTargetOrDeflectSelf(...)}, via
 * {@code CraftEventFactory#callProjectileHitEvent} - so this one shows up whenever a modded
 * projectile reaches a block, an entity, or simply expires). Plain {@code CraftEntity} implements
 * none of these narrower interfaces, so without a special case each of these would just trade the
 * original crash for a {@code ClassCastException} instead. This transformer therefore checks, in
 * order, {@code entity instanceof AbstractHorse} (returning a {@code CraftAbstractHorse}, see
 * {@link CraftAbstractHorseGenericFallbackTransformer}) and {@code entity instanceof TamableAnimal}
 * (returning a {@code CraftTameableAnimal}, which - like {@code CraftLivingEntity} - isn't declared
 * {@code abstract}, so it needs no companion transformer to strip anything) - both checked FIRST
 * and separately from the broader {@code LivingEntity} check, since both IS-A {@code LivingEntity}
 * too - then {@code entity instanceof Projectile} (returning a {@code CraftProjectile}, see
 * {@link CraftProjectileGenericFallbackTransformer} - disjoint from {@code LivingEntity}, so its
 * position relative to that check doesn't matter, only that it comes before the final fallback),
 * {@code entity instanceof LivingEntity} (returning a {@code CraftLivingEntity}), and
 * {@code entity instanceof AbstractBoat} (returning a {@code CraftBoat}, see
 * {@link CraftBoatGenericFallbackTransformer}) before falling through to the plain generic case.
 */
public final class CraftEntityGenericFallbackTransformer implements ClassFileTransformer {
	private static final Logger LOGGER = Logger.getLogger(
			"ankhangbo.fabricloader.asm.CraftEntityGenericFallbackTransformer");

	private static final String TARGET_CLASS = "org/bukkit/craftbukkit/entity/CraftEntity";
	private static final String TARGET_METHOD = "getEntity";
	private static final String TARGET_DESC = "(Lorg/bukkit/craftbukkit/CraftServer;"
			+ "Lnet/minecraft/world/entity/Entity;)Lorg/bukkit/craftbukkit/entity/CraftEntity;";
	private static final String EXCEPTION_TYPE = "java/lang/AssertionError";
	private static final String CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/Entity;)V";

	private static final String ABSTRACT_BOAT_CLASS = "net/minecraft/world/entity/vehicle/boat/AbstractBoat";
	private static final String CRAFT_BOAT_CLASS = "org/bukkit/craftbukkit/entity/CraftBoat";
	private static final String CRAFT_BOAT_CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;)V";

	private static final String LIVING_ENTITY_CLASS = "net/minecraft/world/entity/LivingEntity";
	private static final String CRAFT_LIVING_ENTITY_CLASS = "org/bukkit/craftbukkit/entity/CraftLivingEntity";
	private static final String CRAFT_LIVING_ENTITY_CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/LivingEntity;)V";

	private static final String ABSTRACT_HORSE_CLASS = "net/minecraft/world/entity/animal/equine/AbstractHorse";
	private static final String CRAFT_ABSTRACT_HORSE_CLASS = "org/bukkit/craftbukkit/entity/CraftAbstractHorse";
	private static final String CRAFT_ABSTRACT_HORSE_CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/animal/equine/AbstractHorse;)V";

	private static final String TAMABLE_ANIMAL_CLASS = "net/minecraft/world/entity/TamableAnimal";
	private static final String CRAFT_TAMEABLE_ANIMAL_CLASS = "org/bukkit/craftbukkit/entity/CraftTameableAnimal";
	private static final String CRAFT_TAMEABLE_ANIMAL_CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/TamableAnimal;)V";

	private static final String PROJECTILE_CLASS = "net/minecraft/world/entity/projectile/Projectile";
	private static final String CRAFT_PROJECTILE_CLASS = "org/bukkit/craftbukkit/entity/CraftProjectile";
	private static final String CRAFT_PROJECTILE_CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/projectile/Projectile;)V";

	private static final String ITEM_ENTITY_CLASS = "net/minecraft/world/entity/item/ItemEntity";
	private static final String CRAFT_ITEM_CLASS = "org/bukkit/craftbukkit/entity/CraftItem";
	private static final String CRAFT_ITEM_CTOR_DESC =
			"(Lorg/bukkit/craftbukkit/CraftServer;Lnet/minecraft/world/entity/item/ItemEntity;)V";

	// Static method: server=0, entity=1 (both reference types, one slot each).
	private static final int SERVER_PARAM_INDEX = 0;
	private static final int ENTITY_PARAM_INDEX = 1;

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

			if ((classNode.access & Opcodes.ACC_ABSTRACT) != 0) {
				classNode.access &= ~Opcodes.ACC_ABSTRACT;
				patched = true;
			}

			for (MethodNode method : (List<MethodNode>) classNode.methods) {
				if (!method.name.equals(TARGET_METHOD) || !method.desc.equals(TARGET_DESC)) {
					continue;
				}

				AbstractInsnNode newInsn = null;
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn instanceof TypeInsnNode type
							&& type.getOpcode() == Opcodes.NEW
							&& type.desc.equals(EXCEPTION_TYPE)) {
						newInsn = insn;
						break;
					}
				}
				AbstractInsnNode athrow = null;
				if (newInsn != null) {
					for (AbstractInsnNode insn = newInsn; insn != null; insn = insn.getNext()) {
						if (insn.getOpcode() == Opcodes.ATHROW) {
							athrow = insn;
							break;
						}
					}
				}
				if (newInsn == null || athrow == null) {
					LOGGER.warning("Could not locate the 'Unknown entity' throw in "
							+ className + "#" + TARGET_METHOD + " - leaving that part unpatched");
					continue;
				}

				// This is a top-level "throw" statement (not nested inside another expression),
				// so the operand stack is guaranteed empty right before it - same situation as
				// TreeGrowerUnknownFeatureTransformer, safe to replace with straight-line code.
				// Every branch below ends in ARETURN, so there's no merge point afterwards either -
				// exactly the shape that avoided the VerifyError from the boat-placement fix.
				LabelNode notHorse = new LabelNode();
				LabelNode notTameable = new LabelNode();
				LabelNode notProjectile = new LabelNode();
				LabelNode notLiving = new LabelNode();
				LabelNode notBoat = new LabelNode();
				LabelNode notItem = new LabelNode();
				InsnList replacement = new InsnList();

				// 0) Modded entity that extends a vanilla class (TF BighornSheep extends Sheep, ...): use the Craft wrapper of its nearest
				//    vanilla superclass, so that Paper's casts to org.bukkit.entity.Sheep/Cow/Zombie... succeed. Single-predecessor branch:
				//    the non-null path ends in ARETURN, the null path continues with the original chain below (stack: [null] -> POP).
				LabelNode noWrapper = new LabelNode();
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "ankhangbo/fabricloader/compat/CraftEntityResolver",
						"byHierarchy", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new JumpInsnNode(Opcodes.IFNULL, noWrapper));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/bukkit/craftbukkit/entity/CraftEntity"));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(noWrapper);
				replacement.add(new InsnNode(Opcodes.POP));

				// AbstractHorse and TamableAnimal are checked first, and separately from (rather
				// than after) the LivingEntity check below, because both IS-A LivingEntity - if
				// the broader LivingEntity check ran first, it would always match first and these
				// branches would never be reached for any modded horse-family or tameable-animal
				// entity. Projectile is disjoint from LivingEntity (a projectile is a plain
				// Entity), so its position relative to the LivingEntity check doesn't matter, but
				// it must still come before the final plain-CraftEntity fallback.
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.INSTANCEOF, ABSTRACT_HORSE_CLASS));
				replacement.add(new JumpInsnNode(Opcodes.IFEQ, notHorse));
				replacement.add(new TypeInsnNode(Opcodes.NEW, CRAFT_ABSTRACT_HORSE_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, ABSTRACT_HORSE_CLASS));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_ABSTRACT_HORSE_CLASS, "<init>",
						CRAFT_ABSTRACT_HORSE_CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(notHorse);

				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.INSTANCEOF, TAMABLE_ANIMAL_CLASS));
				replacement.add(new JumpInsnNode(Opcodes.IFEQ, notTameable));
				replacement.add(new TypeInsnNode(Opcodes.NEW, CRAFT_TAMEABLE_ANIMAL_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, TAMABLE_ANIMAL_CLASS));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_TAMEABLE_ANIMAL_CLASS, "<init>",
						CRAFT_TAMEABLE_ANIMAL_CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(notTameable);

				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.INSTANCEOF, PROJECTILE_CLASS));
				replacement.add(new JumpInsnNode(Opcodes.IFEQ, notProjectile));
				replacement.add(new TypeInsnNode(Opcodes.NEW, CRAFT_PROJECTILE_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, PROJECTILE_CLASS));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_PROJECTILE_CLASS, "<init>",
						CRAFT_PROJECTILE_CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(notProjectile);

				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.INSTANCEOF, LIVING_ENTITY_CLASS));
				replacement.add(new JumpInsnNode(Opcodes.IFEQ, notLiving));
				replacement.add(new TypeInsnNode(Opcodes.NEW, CRAFT_LIVING_ENTITY_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, LIVING_ENTITY_CLASS));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_LIVING_ENTITY_CLASS, "<init>",
						CRAFT_LIVING_ENTITY_CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(notLiving);

				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.INSTANCEOF, ABSTRACT_BOAT_CLASS));
				replacement.add(new JumpInsnNode(Opcodes.IFEQ, notBoat));
				replacement.add(new TypeInsnNode(Opcodes.NEW, CRAFT_BOAT_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, ABSTRACT_BOAT_CLASS));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_BOAT_CLASS, "<init>",
						CRAFT_BOAT_CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(notBoat);

				// ItemEntity (also any modded subclass / modded EntityType of it, e.g. Naturalist's ants dropping food):
				// CraftEventFactory#callItemSpawnEvent casts entity.getBukkitEntity() to org.bukkit.entity.Item.
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.INSTANCEOF, ITEM_ENTITY_CLASS));
				replacement.add(new JumpInsnNode(Opcodes.IFEQ, notItem));
				replacement.add(new TypeInsnNode(Opcodes.NEW, CRAFT_ITEM_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_ENTITY_CLASS));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CRAFT_ITEM_CLASS, "<init>",
						CRAFT_ITEM_CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				replacement.add(notItem);

				replacement.add(new TypeInsnNode(Opcodes.NEW, TARGET_CLASS));
				replacement.add(new InsnNode(Opcodes.DUP));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, SERVER_PARAM_INDEX));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, ENTITY_PARAM_INDEX));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, TARGET_CLASS, "<init>", CTOR_DESC, false));
				replacement.add(new InsnNode(Opcodes.ARETURN));
				method.instructions.insertBefore(newInsn, replacement);

				AbstractInsnNode cursor = newInsn;
				while (cursor != null) {
					AbstractInsnNode next = cursor.getNext();
					method.instructions.remove(cursor);
					if (cursor == athrow) {
						break;
					}
					cursor = next;
				}

				patched = true;
			}

			if (!patched) {
				return null;
			}

			ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
			classNode.accept(writer);

			LOGGER.fine(() -> "Made " + className + " instantiable and made " + TARGET_METHOD
					+ " fall back to a generic instance for unmapped (modded) entity types.");
			return writer.toByteArray();
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Failed to patch " + className
					+ " with a generic entity fallback - leaving it unpatched", t);
			return null;
		}
	}
}