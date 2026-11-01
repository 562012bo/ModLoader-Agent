package net.fabricmc.loader.impl.transformer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Instead of dropping a mixin whose {@code @Shadow} field/method does not exist in Paper/Leaf (which breaks the mod
 * and everything that depends on it), this makes the mixin APPLY: a {@code @Shadow} member that is missing from
 * every target class (and its supertypes) is turned into a normal member that Mixin merges into the target.
 * Fields become real (default-initialised) fields; abstract shadow methods get a body returning the default value.
 * Disable with -Dankhangbo.mixin.materializeShadows=false.
 */
final class ShadowMaterializer {
	static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.mixin.materializeShadows"));

	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final Set<String> STRIP = new HashSet<>(java.util.Arrays.asList(SHADOW,
			"Lorg/spongepowered/asm/mixin/Final;", "Lorg/spongepowered/asm/mixin/Mutable;"));
	// Off by default: turning an abstract @Accessor into a default method makes Mixin classify the interface mixin as
	// SubType.Interface, which then demands an interface target ("ServerLevel is not an interface"). Missing fields are
	// re-added instead (ServerLevelEntityManagerFieldTransformer). Enable with -Dankhangbo.mixin.stubAccessors=true.
	private static final boolean STUB_ACCESSORS = "true".equalsIgnoreCase(System.getProperty("ankhangbo.mixin.stubAccessors"));
	private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final byte[] SHADOW_MARK = "org/spongepowered/asm/mixin/Shadow".getBytes(StandardCharsets.ISO_8859_1);
	private static final byte[] ACCESSOR_MARK = "org/spongepowered/asm/mixin/gen/Accessor".getBytes(StandardCharsets.ISO_8859_1);

	private static final class Members {
		final Set<String> fields = new HashSet<>();
		final Set<String> methods = new HashSet<>();
	}

	private static final Members UNKNOWN = new Members();
	private static final Map<String, Members> CACHE = new ConcurrentHashMap<>();

	private ShadowMaterializer() { }

	static byte[] apply(String name, byte[] bytes) {
		if (!ENABLED || bytes == null) return bytes;

		boolean hasShadow = indexOf(bytes, SHADOW_MARK) >= 0;
		boolean hasAccessor = STUB_ACCESSORS && indexOf(bytes, ACCESSOR_MARK) >= 0;
		if (!hasShadow && !hasAccessor) return bytes;

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);

			boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
			// interface mixins (accessor interfaces) only ever carry @Accessor, never @Shadow
			if (isInterface && !hasAccessor) return bytes;

			List<String> targets = mixinTargets(node);
			if (targets.isEmpty()) return bytes;

			List<Members> members = new ArrayList<>();

			for (String t : targets) {
				Members m = membersOf(t);
				if (m == UNKNOWN) return bytes; // cannot prove it is missing -> leave untouched
				members.add(m);
			}

			boolean changed = false;

			for (FieldNode f : node.fields) {
				if (isInterface) break;
				AnnotationNode shadow = find(f.visibleAnnotations, f.invisibleAnnotations);
				if (shadow == null) continue;

				String real = realName(f.name, shadow);
				if (existsAnywhere(members, "F", real + ":" + f.desc, aliases(shadow), f.desc, true)) continue;

				Log.warn(LogCategory.GAME_PATCH, "@Shadow field %s.%s%s is missing in %s: creating it as a plain field so the mixin still applies", name, f.name, f.desc, targets);
				String old = f.name;
				f.name = real;
				f.access &= ~(Opcodes.ACC_FINAL);
				strip(f.visibleAnnotations);
				strip(f.invisibleAnnotations);

				for (MethodNode m : node.methods) {
					if (m.instructions == null) continue;
					for (AbstractInsnNode in : m.instructions) {
						if (in instanceof FieldInsnNode) {
							FieldInsnNode fi = (FieldInsnNode) in;
							if (fi.owner.equals(node.name) && fi.name.equals(old) && fi.desc.equals(f.desc)) fi.name = real;
						}
					}
				}

				changed = true;
			}

			for (MethodNode m : node.methods) {
				if (isInterface) break;
				AnnotationNode shadow = find(m.visibleAnnotations, m.invisibleAnnotations);
				if (shadow == null) continue;

				String real = realName(m.name, shadow);
				if (existsAnywhere(members, "M", real + m.desc, aliases(shadow), m.desc, false)) continue;

				Log.warn(LogCategory.GAME_PATCH, "@Shadow method %s.%s%s is missing in %s: creating a stub (default return value) so the mixin still applies", name, m.name, m.desc, targets);
				String old = m.name;
				m.name = real;
				m.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
				strip(m.visibleAnnotations);
				strip(m.invisibleAnnotations);

				Type[] args = Type.getArgumentTypes(m.desc);
				Type ret = Type.getReturnType(m.desc);
				m.instructions.clear();
				m.tryCatchBlocks = new ArrayList<>();
				m.localVariables = null;
				switch (ret.getSort()) {
				case Type.VOID: m.instructions.add(new InsnNode(Opcodes.RETURN)); break;
				case Type.LONG: m.instructions.add(new InsnNode(Opcodes.LCONST_0)); m.instructions.add(new InsnNode(Opcodes.LRETURN)); break;
				case Type.FLOAT: m.instructions.add(new InsnNode(Opcodes.FCONST_0)); m.instructions.add(new InsnNode(Opcodes.FRETURN)); break;
				case Type.DOUBLE: m.instructions.add(new InsnNode(Opcodes.DCONST_0)); m.instructions.add(new InsnNode(Opcodes.DRETURN)); break;
				case Type.OBJECT: case Type.ARRAY: m.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); m.instructions.add(new InsnNode(Opcodes.ARETURN)); break;
				default: m.instructions.add(new InsnNode(Opcodes.ICONST_0)); m.instructions.add(new InsnNode(Opcodes.IRETURN)); break;
				}

				int locals = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
				for (Type a : args) locals += a.getSize();
				m.maxLocals = locals;
				m.maxStack = 2;

				for (MethodNode other : node.methods) {
					if (other.instructions == null) continue;
					for (AbstractInsnNode in : other.instructions) {
						if (in instanceof MethodInsnNode) {
							MethodInsnNode mi = (MethodInsnNode) in;
							if (mi.owner.equals(node.name) && mi.name.equals(old) && mi.desc.equals(m.desc)) mi.name = real;
						}
					}
				}

				changed = true;
			}

			// @Accessor whose target FIELD does not exist in Paper/Leaf (e.g. Chunky's ServerLevelMixin#getEntityManager: Moonrise
			// removed ServerLevel.entityManager). Mixin would throw InvalidAccessorException ("No candidates were found matching
			// entityManager:...") and abort the whole server bootstrap. Turn it into a plain method with a default return value.
			for (MethodNode m : node.methods) {
				AnnotationNode accessor = findAccessor(m.visibleAnnotations, m.invisibleAnnotations);
				if (accessor == null || (m.access & Opcodes.ACC_STATIC) != 0) continue;

				Type[] args = Type.getArgumentTypes(m.desc);
				Type ret = Type.getReturnType(m.desc);
				boolean getter = args.length == 0 && ret.getSort() != Type.VOID;
				boolean setter = args.length == 1 && ret.getSort() == Type.VOID;
				if (!getter && !setter) continue;

				String field = accessorFieldName(m.name, accessor, getter);
				if (field == null) continue;

				String fieldDesc = getter ? ret.getDescriptor() : args[0].getDescriptor();
				boolean found = false;
				for (Members mem : members) {
					if (mem.fields.contains(field + ":" + fieldDesc)) { found = true; break; }
				}
				if (found) continue;

				Log.warn(LogCategory.GAME_PATCH, "@Accessor %s.%s%s targets field '%s' which is missing in %s: replacing it with a stub (%s) so the mixin still applies",
						name, m.name, m.desc, field, targets, getter ? "returns default value" : "no-op");
				strip(m.visibleAnnotations, ACCESSOR);
				strip(m.invisibleAnnotations, ACCESSOR);
				m.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
				m.access |= Opcodes.ACC_PUBLIC;
				m.instructions.clear();
				m.tryCatchBlocks = new ArrayList<>();
				m.localVariables = null;

				switch (ret.getSort()) {
				case Type.VOID: m.instructions.add(new InsnNode(Opcodes.RETURN)); break;
				case Type.LONG: m.instructions.add(new InsnNode(Opcodes.LCONST_0)); m.instructions.add(new InsnNode(Opcodes.LRETURN)); break;
				case Type.FLOAT: m.instructions.add(new InsnNode(Opcodes.FCONST_0)); m.instructions.add(new InsnNode(Opcodes.FRETURN)); break;
				case Type.DOUBLE: m.instructions.add(new InsnNode(Opcodes.DCONST_0)); m.instructions.add(new InsnNode(Opcodes.DRETURN)); break;
				case Type.OBJECT: case Type.ARRAY: m.instructions.add(new InsnNode(Opcodes.ACONST_NULL)); m.instructions.add(new InsnNode(Opcodes.ARETURN)); break;
				default: m.instructions.add(new InsnNode(Opcodes.ICONST_0)); m.instructions.add(new InsnNode(Opcodes.IRETURN)); break;
				}

				int locals = 1;
				for (Type a : args) locals += a.getSize();
				m.maxLocals = locals;
				m.maxStack = 2;
				changed = true;
			}

			if (!changed) return bytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "Could not materialize missing @Shadow members in %s: %s", name, t);
			return bytes;
		}
	}

	private static boolean existsAnywhere(List<Members> members, String kind, String key, List<String> aliases, String desc, boolean field) {
		// present in at least one target (or alias present) -> leave it alone, Mixin handles it
		for (Members m : members) {
			Set<String> set = field ? m.fields : m.methods;
			if (set.contains(key)) return true;

			for (String a : aliases) {
				if (set.contains(field ? a + ":" + desc : a + desc)) return true;
			}
		}

		return false;
	}

	private static String realName(String name, AnnotationNode shadow) {
		String prefix = "shadow$";

		if (shadow.values != null) {
			for (int i = 0; i + 1 < shadow.values.size(); i += 2) {
				if ("prefix".equals(shadow.values.get(i))) prefix = (String) shadow.values.get(i + 1);
			}
		}

		return !prefix.isEmpty() && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
	}

	@SuppressWarnings("unchecked")
	private static List<String> aliases(AnnotationNode shadow) {
		List<String> out = new ArrayList<>();

		if (shadow.values != null) {
			for (int i = 0; i + 1 < shadow.values.size(); i += 2) {
				if ("aliases".equals(shadow.values.get(i))) out.addAll((List<String>) shadow.values.get(i + 1));
			}
		}

		return out;
	}

	private static AnnotationNode find(List<AnnotationNode> a, List<AnnotationNode> b) {
		for (List<AnnotationNode> list : java.util.Arrays.asList(a, b)) {
			if (list == null) continue;
			for (AnnotationNode an : list) if (SHADOW.equals(an.desc)) return an;
		}

		return null;
	}

	private static AnnotationNode findAccessor(List<AnnotationNode> a, List<AnnotationNode> b) {
		for (List<AnnotationNode> list : java.util.Arrays.asList(a, b)) {
			if (list == null) continue;
			for (AnnotationNode an : list) if (ACCESSOR.equals(an.desc)) return an;
		}

		return null;
	}

	/** explicit {@code @Accessor("field")} value, else inferred from getX/isX/setX like Mixin does */
	private static String accessorFieldName(String methodName, AnnotationNode accessor, boolean getter) {
		if (accessor.values != null) {
			for (int i = 0; i + 1 < accessor.values.size(); i += 2) {
				if ("value".equals(accessor.values.get(i)) && accessor.values.get(i + 1) instanceof String) {
					String v = (String) accessor.values.get(i + 1);
					if (!v.isEmpty()) return v;
				}
			}
		}

		String base;
		if (getter && methodName.startsWith("get") && methodName.length() > 3) base = methodName.substring(3);
		else if (getter && methodName.startsWith("is") && methodName.length() > 2) base = methodName.substring(2);
		else if (!getter && methodName.startsWith("set") && methodName.length() > 3) base = methodName.substring(3);
		else return null;

		return Character.toLowerCase(base.charAt(0)) + base.substring(1);
	}

	private static void strip(List<AnnotationNode> list, String desc) {
		if (list != null) list.removeIf(an -> desc.equals(an.desc));
	}

	private static void strip(List<AnnotationNode> list) {
		if (list != null) list.removeIf(an -> STRIP.contains(an.desc));
	}

	@SuppressWarnings("unchecked")
	private static List<String> mixinTargets(ClassNode node) {
		List<String> out = new ArrayList<>();

		for (List<AnnotationNode> list : java.util.Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
			if (list == null) continue;

			for (AnnotationNode an : list) {
				if (!MIXIN.equals(an.desc) || an.values == null) continue;

				for (int i = 0; i + 1 < an.values.size(); i += 2) {
					String key = (String) an.values.get(i);
					Object v = an.values.get(i + 1);

					if ("value".equals(key)) {
						for (Type t : (List<Type>) v) out.add(t.getInternalName());
					} else if ("targets".equals(key)) {
						for (String s : (List<String>) v) out.add(s.replace('.', '/'));
					}
				}
			}
		}

		return out;
	}

	private static Members membersOf(String internalName) {
		Members cached = CACHE.get(internalName);
		if (cached != null) return cached;

		Members result = new Members();
		ClassNode node = readClass(internalName);

		if (node == null) {
			result = UNKNOWN;
		} else {
			for (FieldNode f : node.fields) result.fields.add(f.name + ":" + f.desc);
			for (MethodNode m : node.methods) result.methods.add(m.name + m.desc);
			// bridges that PaperModSignatureCompat adds to the real game class later (raw bytes above do not have them yet)
			for (String[] row : new String[][] {{"canMerge", "(Lnet/minecraft/world/entity/ExperienceOrb;II)Z"}}) {
				if (ankhangbo.fabricloader.asm.ExperienceOrbCanMergeBridgeTransformer.hasMethodBridge(internalName, row[0] + row[1])) result.methods.add(row[0] + row[1]);
			}

			List<String> parents = new ArrayList<>();
			if (node.superName != null) parents.add(node.superName);
			parents.addAll(node.interfaces);

			for (String p : parents) {
				Members pm = membersOf(p);

				if (pm == UNKNOWN) {
					result = UNKNOWN;
					break;
				}

				result.fields.addAll(pm.fields);
				result.methods.addAll(pm.methods);
			}
		}

		if (result != UNKNOWN) CACHE.put(internalName, result); // do not remember a failed lookup (launcher may not be ready yet)
		return result;
	}

	private static ClassNode readClass(String internalName) {
		byte[] data = null;

		try {
			data = FabricLauncherBase.getLauncher().getClassByteArray(internalName.replace('/', '.'), false);
		} catch (Throwable ignored) {
			// fall through
		}

		if (data == null) {
			try (InputStream in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
				if (in != null) data = in.readAllBytes();
			} catch (Throwable ignored) {
				// fall through
			}
		}

		if (data == null) return null;

		ClassNode node = new ClassNode();
		new ClassReader(data).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	private static int indexOf(byte[] data, byte[] pat) {
		outer:
		for (int i = 0; i <= data.length - pat.length; i++) {
			for (int j = 0; j < pat.length; j++) if (data[i + j] != pat[j]) continue outer;
			return i;
		}

		return -1;
	}
}