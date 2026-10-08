/*
 * Paper compatibility patch.
 *
 * Mods' own compiled @Mixin classes are written against vanilla Minecraft's real method
 * signatures. Paper/CraftBukkit's own source patches sometimes change a method's parameter types
 * outright (not just obfuscated names, which mappings handle just fine — an actual structural
 * change, e.g. net.minecraft.server.Main.main(String[]) -> main(OptionSet), see
 * net/minecraft/server/Main.java.patch). Mixin's @Inject matches its target by exact
 * name+descriptor with no coercion, so every mod with a callback written against the OLD
 * (vanilla) descriptor fails to apply against Paper, even though a same-named method genuinely
 * exists.
 *
 * An earlier approach tried reshaping net.minecraft.server.Main itself (adding a synthetic
 * vanilla-shaped "bridge" method) to give such mods something to attach to — but this
 * reintroduced Mixin's own target-selector ambiguity the moment TWO same-named "main" methods
 * existed on one class (Mixin's plain-name resolution, used whenever a mod's
 * @Inject(method = "main") doesn't specify a descriptor — the common case — does not reliably
 * prefer one over the other, and in practice ended up resolving neither: "Scanned 0 target(s)").
 *
 * This system takes the opposite, more surgical approach: leave every real game class completely
 * untouched (so Mixin's own target resolution against them stays perfectly unambiguous), and
 * instead directly rewrite each MOD'S OWN compiled class — for genuine structural changes
 * (SignaturePatch, below), retargeting a callback's descriptor and reconstructing an equivalent
 * value of the original argument at method entry; for simple renames (ClassFieldMethodRemap,
 * below — the far more common case now that Fabric's own mappings are gone as of 26.1: mods'
 * compiled bytecode calls/references vanilla identifiers LITERALLY, with no obfuscation layer to
 * fall back on, so any identifier Paper renamed needs an explicit 1:1 substitution wherever a mod
 * references it — not just inside @Mixin annotations, but any method call, field access, or type
 * reference anywhere in the mod's own bytecode), using ASM's own Remapper/ClassRemapper framework
 * (org.objectweb.asm.commons — general-purpose, applies consistently across an entire class's
 * constant pool, not something this project reimplements by hand).
 *
 * A third shape shows up too: Paper sometimes APPENDS brand-new trailing parameters onto a
 * method instead of renaming/retyping an existing one (e.g. LivingEntity.addEffect(MobEffectInstance,
 * Entity) -> addEffect(MobEffectInstance, Entity, EntityPotionEffectEvent.Cause, boolean)). That's
 * handled by TrailingArgsPatch/retargetTrailingArgs below: the mod callback's descriptor is grown
 * to match, and every local variable slot after the original parameters (the callback parameter,
 * plus any locals the callback body declares) is shifted up to compensate — the new parameter
 * slots themselves are simply left unread, which is safe since the unmodified callback body never
 * referenced them.
 *
 * A fourth shape shows up when a fork (e.g. Leaf's Moonrise chunk-system rewrite) doesn't just
 * rename/regrow a method, but relocates the ENTIRE mechanism a mod's @Mixin targets onto a
 * completely different real class. No amount of descriptor patching on the OLD class helps here
 * (the method/field is simply gone) — MixinRetargetPatch, further below, instead rewrites the
 * @Mixin annotation's own target class to point at the new one, always paired with a
 * MethodBodyPatch that rebuilds the injector's body from scratch to match the new class's real
 * shape (fields/params available there are usually different from what the mod originally wrote
 * against).
 *
 * Adding support for a newly-discovered Paper rename or structural change is just adding one more
 * line (or CLASS/METHOD/FIELD block) to mappings/mappings.tiny (bundled inside this agent's own
 * jar) — see loadMappings() below for the exact file format. Nothing in this class needs to
 * change or be recompiled.
 */
package net.fabricmc.loader.impl.transformer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class PaperModSignatureCompat {

	private static final ThreadLocal<Object> OPEN_MENU_FACTORY = new ThreadLocal<>();
	
	public static void setOpenMenuFactory(Object factory) {
		OPEN_MENU_FACTORY.set(factory);
	}
	
	public static Object getOpenMenuFactory() {
		Object f = OPEN_MENU_FACTORY.get();
		OPEN_MENU_FACTORY.remove();
		return f;
	}

	private static final class SafeClassWriter extends ClassWriter {
		SafeClassWriter(int flags) {
			super(flags);
		}

		@Override
		protected String getCommonSuperClass(final String type1, final String type2) {
			try {
				return super.getCommonSuperClass(type1, type2);
			} catch (TypeNotPresentException | LinkageError e) {
				Log.warn(LogCategory.GAME_PATCH, "Could not resolve common superclass "
						+ "of %s and %s while recomputing stack map frames (a class/"
						+ "interface referenced here isn't visible to the patcher's "
						+ "own classloader) — falling back to java/lang/Object", type1, type2);
				return "java/lang/Object";
			}
		}
	}
	
	static final class SignaturePatch {
		final String targetClassInternalName;
		final String oldArgPrefix;
		final String newArgPrefix;
		final String reconstructorOwner;
		final String reconstructorName;
		final String reconstructorDesc;
		final String targetMethodName;

		SignaturePatch(String targetClassInternalName, String oldArgPrefix, String newArgPrefix,
				String reconstructorOwner, String reconstructorName, String reconstructorDesc,
				String targetMethodName) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldArgPrefix = oldArgPrefix;
			this.newArgPrefix = newArgPrefix;
			this.reconstructorOwner = reconstructorOwner;
			this.reconstructorName = reconstructorName;
			this.reconstructorDesc = reconstructorDesc;
			this.targetMethodName = (targetMethodName == null || targetMethodName.isEmpty()) ? null : targetMethodName;
		}
	}

	private static final String[] CALLBACK_TYPE_DESCS = {
			"Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
			"Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;",
	};

	private static String findExtendedCallbackSuffix(String desc, String oldPrefix) {
		if (!desc.startsWith(oldPrefix)) return null;
		String rest = desc.substring(oldPrefix.length());

		for (String cbType : CALLBACK_TYPE_DESCS) {
			if (rest.startsWith(cbType) && rest.endsWith(")V")) {
				return rest;
			}
		}
		return null;
	}

	static final class TrailingArgsPatch {
		final String targetClassInternalName;
		final String oldRealArgsDesc;
		final String insertedArgsDesc;
		final String targetMethodName;

		TrailingArgsPatch(String targetClassInternalName, String oldRealArgsDesc, String insertedArgsDesc,
				String targetMethodName) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldRealArgsDesc = oldRealArgsDesc;
			this.insertedArgsDesc = insertedArgsDesc;
			this.targetMethodName = (targetMethodName == null || targetMethodName.isEmpty()) ? null : targetMethodName;
		}
	}

	static final class LeadingArgsPatch {
		final String targetClassInternalName;
		final String oldRealArgsDesc;
		final String insertedArgsDesc;
		final String targetMethodName;

		LeadingArgsPatch(String targetClassInternalName, String oldRealArgsDesc, String insertedArgsDesc,
				String targetMethodName) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldRealArgsDesc = oldRealArgsDesc;
			this.insertedArgsDesc = insertedArgsDesc;
			this.targetMethodName = (targetMethodName == null || targetMethodName.isEmpty()) ? null : targetMethodName;
		}
	}

	static final class ShadowFieldTypePatch {
		final String targetClassInternalName;
		final String fieldName;
		final String oldTypeDesc;
		final String newTypeDesc;

		ShadowFieldTypePatch(String targetClassInternalName, String fieldName, String oldTypeDesc, String newTypeDesc) {
			this.targetClassInternalName = targetClassInternalName;
			this.fieldName = fieldName;
			this.oldTypeDesc = oldTypeDesc;
			this.newTypeDesc = newTypeDesc;
		}
	}

	static final class ShadowFieldRedirectPatch {
		final String targetClassInternalName;
		final String fieldName;
		final String fieldTypeDesc;
		final String getterOwner;
		final String getterName;
		final String getterDesc;

		ShadowFieldRedirectPatch(String targetClassInternalName, String fieldName, String fieldTypeDesc,
				String getterOwner, String getterName, String getterDesc) {
			this.targetClassInternalName = targetClassInternalName;
			this.fieldName = fieldName;
			this.fieldTypeDesc = fieldTypeDesc;
			this.getterOwner = getterOwner;
			this.getterName = getterName;
			this.getterDesc = getterDesc;
		}
	}

	static final class CallbackInfoReturnablePatch {
		final String targetClassInternalName;
		final String targetMethodName;

		CallbackInfoReturnablePatch(String targetClassInternalName, String targetMethodName) {
			this.targetClassInternalName = targetClassInternalName;
			this.targetMethodName = (targetMethodName == null || targetMethodName.isEmpty()) ? null : targetMethodName;
		}
	}

	static final class CallGrowthPatch {
		final String targetClassInternalName;
		final String methodName;
		final String oldParamsDesc;
		final List<String> argSpecs;

		CallGrowthPatch(String targetClassInternalName, String methodName, String oldParamsDesc, List<String> argSpecs) {
			this.targetClassInternalName = targetClassInternalName;
			this.methodName = methodName;
			this.oldParamsDesc = oldParamsDesc;
			this.argSpecs = argSpecs;
		}
	}

	static final class AnnotationSelectorPatch {
		final String targetClassInternalName;
		final String oldSelector;
		final String newSelector;
		final Integer newIndex;
		final java.util.Map<String, String> options;

		AnnotationSelectorPatch(String targetClassInternalName, String oldSelector, String newSelector,
				Integer newIndex, java.util.Map<String, String> options) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldSelector = oldSelector;
			this.newSelector = newSelector;
			this.newIndex = newIndex;
			this.options = options;
		}
	}

	static final class MixinRetargetPatch {
		final String mixinClassInternalName;
		final String oldTargetClassInternalName;
		final String newTargetClassInternalName;

		MixinRetargetPatch(String mixinClassInternalName, String oldTargetClassInternalName,
				String newTargetClassInternalName) {
			this.mixinClassInternalName = "ALL".equals(mixinClassInternalName) ? null : mixinClassInternalName;
			this.oldTargetClassInternalName = oldTargetClassInternalName;
			this.newTargetClassInternalName = newTargetClassInternalName;
		}
	}

	static final class BridgeFieldPatch {
		final String targetClassInternalName;
		final String fieldName;
		final String vanillaTypeDesc;
		final String actualTypeDesc;
		final String getterOwner;
		final String getterName;
		final String getterDesc;

		BridgeFieldPatch(String targetClassInternalName, String fieldName,
				String vanillaTypeDesc, String actualTypeDesc,
				String getterOwner, String getterName, String getterDesc) {
			this.targetClassInternalName = targetClassInternalName;
			this.fieldName = fieldName;
			this.vanillaTypeDesc = vanillaTypeDesc;
			this.actualTypeDesc = actualTypeDesc;
			this.getterOwner = getterOwner;
			this.getterName = getterName;
			this.getterDesc = getterDesc;
		}
	}

	private static final String CALLBACK_INFO_DESC = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
	private static final String CALLBACK_INFO_RETURNABLE_DESC = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V";

	static final class AsmPatch {
		final String oldDesc;
		final String newDesc;

		AsmPatch(String oldDesc, String newDesc) {
			this.oldDesc = oldDesc;
			this.newDesc = newDesc;
		}
	}

	/**
	 * Patch ASM tổng quát: thay thế TOÀN BỘ 1 method (annotation + descriptor + thân) chỉ bằng dữ
	 * liệu khai trong mappings.tiny — không cần viết thêm bất kỳ hàm Java riêng nào cho từng case
	 * (khác các "helper" như handleSetRespawnPosition/handleRemoveAllEffects, vốn cần code Java
	 * riêng mỗi lần). Hỗ trợ annotation với SỐ LƯỢNG PHẦN TỬ TUỲ Ý (kể cả nhiều giá trị cho cùng 1
	 * key, tự động gộp thành mảng — đúng cách Mixin luôn compile "method=" thành String[] dù nguồn
	 * viết dạng scalar hay {..}), 1 sub-annotation @At lồng bên trong (cũng tuỳ ý phần tử), PARAM_ELEM
	 * để gắn parameter annotations (@Local/@Share sugar của MixinExtras), và thân method viết bằng
	 * chuỗi INSTR y hệt methodBodyPatch (dùng lại parseInstruction có sẵn).
	 */
	static final class AsmMethodPatch {
		final String targetClassInternalName;
		final String oldSelector;
		final String requiredOldAnnotationDesc;
		final String newJavaName;
		final String newDesc;
		final String newAnnotationDesc;
		final List<String[]> elements;
		final List<String[]> atElements;
		final List<String[]> paramAnnotations;
		final List<String> instructions;
		AsmMethodPatch(String targetClassInternalName, String oldSelector, String requiredOldAnnotationDesc,
				String newJavaName, String newDesc, String newAnnotationDesc,
				List<String[]> elements, List<String[]> atElements,
				List<String[]> paramAnnotations, List<String> instructions) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldSelector = oldSelector;
			this.requiredOldAnnotationDesc = ("-".equals(requiredOldAnnotationDesc)) ? null : requiredOldAnnotationDesc;
			this.newJavaName = (newJavaName == null || "-".equals(newJavaName)) ? null : newJavaName;
			this.newDesc = newDesc;
			this.newAnnotationDesc = newAnnotationDesc;
			this.elements = elements;
			this.atElements = atElements;
			this.paramAnnotations = paramAnnotations;
			this.instructions = instructions;
		}
	}

	static final class MethodBodyPatch {
		final String targetClassInternalName;
		final String oldMethodName;
		final String newMethodName;
		final String newAnnotationDesc;
		final String newAtValue;
		final String newDesc;
		final String annotationMethodDesc;
		final String requiredOldAnnotationDesc;
		final List<String> instructions;

		MethodBodyPatch(String targetClassInternalName, String oldMethodName, String newMethodName,
				String newAnnotationDesc, String newAtValue, String newDesc,
				String annotationMethodDesc, String requiredOldAnnotationDesc,
				List<String> instructions) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldMethodName = oldMethodName;
			this.newMethodName = newMethodName;
			this.newAnnotationDesc = newAnnotationDesc;
			this.newAtValue = newAtValue;
			this.newDesc = newDesc;
			this.annotationMethodDesc = annotationMethodDesc;
			this.requiredOldAnnotationDesc = requiredOldAnnotationDesc;
			this.instructions = instructions;
		}
	}

	static final class WrapMethodPatch {
		final String targetClassInternalName;
		final String oldMethodName;
		final String newMethodName;
		final String newMethodDesc;
		final String annotationMethodDesc;
		final List<String> instructions;

		WrapMethodPatch(String targetClassInternalName, String oldMethodName, String newMethodName,
				String newMethodDesc, String annotationMethodDesc, List<String> instructions) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldMethodName = oldMethodName;
			this.newMethodName = newMethodName;
			this.newMethodDesc = newMethodDesc;
			this.annotationMethodDesc = annotationMethodDesc;
			this.instructions = instructions;
		}
	}

	static final class RedirectTrailingArgsPatch {
		final String targetClassInternalName;
		final String oldRealArgsDesc;
		final String oldReturnDesc;
		final String insertedArgsDesc;
		final String targetMethodName;
		final String invokeCallbackOwner;
		final String invokeCallbackName;
		final String invokeCallbackDesc;

		RedirectTrailingArgsPatch(String targetClassInternalName, String oldRealArgsDesc,
				String oldReturnDesc, String insertedArgsDesc, String targetMethodName,
				String invokeCallbackOwner, String invokeCallbackName, String invokeCallbackDesc) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldRealArgsDesc = oldRealArgsDesc;
			this.oldReturnDesc = oldReturnDesc;
			this.insertedArgsDesc = insertedArgsDesc;
			this.targetMethodName = (targetMethodName == null || targetMethodName.isEmpty()) ? null : targetMethodName;
			this.invokeCallbackOwner = invokeCallbackOwner;
			this.invokeCallbackName = invokeCallbackName;
			this.invokeCallbackDesc = invokeCallbackDesc;
		}
	}

	static final class HelperCallPatch {
		final String targetClassInternalName;
		final String oldMethodName;
		final String newAnnotationDesc;
		final String newMethodDesc;
		final String newMethodSignature;
		final String annotationMethodDesc;
		final String helperOwner;
		final String helperName;
		final String helperDesc;

		HelperCallPatch(String targetClassInternalName, String oldMethodName, String newAnnotationDesc,
				String newMethodDesc, String newMethodSignature, String annotationMethodDesc,
				String helperOwner, String helperName, String helperDesc) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldMethodName = oldMethodName;
			this.newAnnotationDesc = newAnnotationDesc;
			this.newMethodDesc = newMethodDesc;
			this.newMethodSignature = newMethodSignature;
			this.annotationMethodDesc = annotationMethodDesc;
			this.helperOwner = helperOwner;
			this.helperName = helperName;
			this.helperDesc = helperDesc;
		}
	}

	static final class OperationCallGrowthPatch {
		final String targetClassInternalName;
		final String methodSelectorName;
		final List<String> argSpecs;

		OperationCallGrowthPatch(String targetClassInternalName, String methodSelectorName, List<String> argSpecs) {
			this.targetClassInternalName = targetClassInternalName;
			this.methodSelectorName = methodSelectorName;
			this.argSpecs = argSpecs;
		}
	}

	static final class InsertParamPatch {
		final String targetClassInternalName;
		final String oldSelector;
		final String requiredOldAnnotationDesc;
		final int paramIndex;
		final String insertedTypeDesc;
		final String newSelector;

		InsertParamPatch(String targetClassInternalName, String oldSelector, String requiredOldAnnotationDesc,
				int paramIndex, String insertedTypeDesc, String newSelector) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldSelector = oldSelector;
			this.requiredOldAnnotationDesc = requiredOldAnnotationDesc;
			this.paramIndex = paramIndex;
			this.insertedTypeDesc = insertedTypeDesc;
			this.newSelector = (newSelector == null || "-".equals(newSelector)) ? null : newSelector;
		}
	}

	static final class InjectorRewritePatch {
		final String targetClassInternalName;
		final String oldSelector;
		final String requiredOldAnnotationDesc;
		final String requiredOldAtTargetContains;
		final String newAnnotationDesc;
		final String newSelector;
		final String newAtValue;
		final String newAtTarget;
		final String newDesc;
		final String helperOwner;
		final String helperName;
		final String helperDesc;

		InjectorRewritePatch(String targetClassInternalName, String oldSelector, String requiredOldAnnotationDesc,
				String requiredOldAtTargetContains,
				String newAnnotationDesc, String newSelector, String newAtValue, String newAtTarget,
				String newDesc, String helperOwner, String helperName, String helperDesc) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldSelector = oldSelector;
			this.requiredOldAnnotationDesc = requiredOldAnnotationDesc;
			this.requiredOldAtTargetContains = requiredOldAtTargetContains;
			this.newAnnotationDesc = newAnnotationDesc;
			this.newSelector = (newSelector == null || "-".equals(newSelector)) ? oldSelector : newSelector;
			this.newAtValue = "-".equals(newAtValue) ? null : newAtValue;
			this.newAtTarget = "-".equals(newAtTarget) ? null : newAtTarget;
			this.newDesc = newDesc;
			this.helperOwner = helperOwner;
			this.helperName = helperName;
			this.helperDesc = helperDesc;
		}
	}

	static final class SplitWrapOperationPatch {
		static final class Variant {
			final String newSelector;
			final String newDesc;
			final String helperOwner;
			final String helperName;
			final String helperDesc;

			Variant(String newSelector, String newDesc, String helperOwner, String helperName, String helperDesc) {
				this.newSelector = newSelector;
				this.newDesc = newDesc;
				this.helperOwner = helperOwner;
				this.helperName = helperName;
				this.helperDesc = helperDesc;
			}
		}

		final String targetClassInternalName;
		final String oldMethodName;
		final String newAnnotationDesc;
		final String atValue;
		final String atTarget;
		final List<Variant> variants;

		SplitWrapOperationPatch(String targetClassInternalName, String oldMethodName, String newAnnotationDesc,
				String atValue, String atTarget, List<Variant> variants) {
			this.targetClassInternalName = targetClassInternalName;
			this.oldMethodName = oldMethodName;
			this.newAnnotationDesc = newAnnotationDesc;
			this.atValue = "-".equals(atValue) ? null : atValue;
			this.atTarget = "-".equals(atTarget) ? null : atTarget;
			this.variants = variants;
		}
	}

	private static final List<HelperCallPatch> HELPER_CALL_PATCHES;
	private static final List<InjectorRewritePatch> INJECTOR_REWRITE_PATCHES;
	private static final List<SplitWrapOperationPatch> SPLIT_WRAP_OPERATION_PATCHES;
	private static final List<InsertParamPatch> INSERT_PARAM_PATCHES;
	private static final List<AsmMethodPatch> ASM_METHOD_PATCHES;
	private static final List<BridgeFieldPatch> BRIDGE_FIELD_PATCHES;

	private static final String MAPPINGS_RESOURCE_PATH = "mappings/mappings.tiny";

	private static final List<SignaturePatch> SIGNATURE_PATCHES;
	private static final List<TrailingArgsPatch> TRAILING_ARGS_PATCHES;
	private static final List<LeadingArgsPatch> LEADING_ARGS_PATCHES;
	private static final List<ShadowFieldTypePatch> SHADOW_FIELD_TYPE_PATCHES;
	private static final List<ShadowFieldRedirectPatch> SHADOW_FIELD_REDIRECT_PATCHES;
	private static final List<CallbackInfoReturnablePatch> CIR_PATCHES;
	private static final List<CallGrowthPatch> CALL_GROWTH_PATCHES;
	private static final List<AsmPatch> ASM_PATCHES;
	private static final List<AnnotationSelectorPatch> ANNOTATION_SELECTOR_PATCHES;
	private static final List<MixinRetargetPatch> MIXIN_RETARGET_PATCHES;
	private static final List<MethodBodyPatch> METHOD_BODY_PATCHES;
	private static final List<WrapMethodPatch> WRAP_METHOD_PATCHES;
	private static final List<RedirectTrailingArgsPatch> REDIRECT_TRAILING_ARGS_PATCHES;
	private static final List<OperationCallGrowthPatch> OPERATION_CALL_GROWTH_PATCHES;
	private static final Map<String, String> CLASS_MAP;
	private static final Map<String, String> METHOD_NAME_MAP;
	private static final Map<String, String> FIELD_NAME_MAP;

	static {
		Loaded loaded = loadMappings();
		SIGNATURE_PATCHES = loaded.signaturePatches;
		TRAILING_ARGS_PATCHES = loaded.trailingArgsPatches;
		LEADING_ARGS_PATCHES = loaded.leadingArgsPatches;
		SHADOW_FIELD_TYPE_PATCHES = loaded.shadowFieldTypePatches;
		SHADOW_FIELD_REDIRECT_PATCHES = loaded.shadowFieldRedirectPatches;
		CIR_PATCHES = loaded.cirPatches;
		CALL_GROWTH_PATCHES = loaded.callGrowthPatches;
		ASM_PATCHES = loaded.asmPatches;
		ANNOTATION_SELECTOR_PATCHES = loaded.annotationSelectorPatches;
		MIXIN_RETARGET_PATCHES = loaded.mixinRetargetPatches;
		METHOD_BODY_PATCHES = loaded.methodBodyPatches;
		WRAP_METHOD_PATCHES = loaded.wrapMethodPatches;
		REDIRECT_TRAILING_ARGS_PATCHES = loaded.redirectTrailingArgsPatches;
		HELPER_CALL_PATCHES = loaded.helperCallPatches;
		OPERATION_CALL_GROWTH_PATCHES = loaded.operationCallGrowthPatches;
		INJECTOR_REWRITE_PATCHES = loaded.injectorRewritePatches;
		INSERT_PARAM_PATCHES = loaded.insertParamPatches;
		ASM_METHOD_PATCHES = loaded.asmMethodPatches; 
		SPLIT_WRAP_OPERATION_PATCHES = loaded.splitWrapOperationPatches;
		BRIDGE_FIELD_PATCHES = loaded.bridgeFieldPatches;
		CLASS_MAP = loaded.classMap;
		METHOD_NAME_MAP = loaded.methodNameMap;
		FIELD_NAME_MAP = loaded.fieldNameMap;
	}

	private static final String MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

	private PaperModSignatureCompat() {
	}

	private static final class Loaded {
		final List<SignaturePatch> signaturePatches;
		final List<TrailingArgsPatch> trailingArgsPatches;
		final List<LeadingArgsPatch> leadingArgsPatches;
		final List<ShadowFieldTypePatch> shadowFieldTypePatches;
		final List<ShadowFieldRedirectPatch> shadowFieldRedirectPatches;
		final List<CallbackInfoReturnablePatch> cirPatches;
		final List<CallGrowthPatch> callGrowthPatches;
		final List<AsmPatch> asmPatches;
		final List<AnnotationSelectorPatch> annotationSelectorPatches;
		final List<MixinRetargetPatch> mixinRetargetPatches;
		final List<MethodBodyPatch> methodBodyPatches;
		final List<WrapMethodPatch> wrapMethodPatches;
		final List<RedirectTrailingArgsPatch> redirectTrailingArgsPatches;
		final List<HelperCallPatch> helperCallPatches;
		final List<OperationCallGrowthPatch> operationCallGrowthPatches;
		final List<InjectorRewritePatch> injectorRewritePatches;
		final List<InsertParamPatch> insertParamPatches;
		final List<AsmMethodPatch> asmMethodPatches;
		final List<SplitWrapOperationPatch> splitWrapOperationPatches;
		final List<BridgeFieldPatch> bridgeFieldPatches;
		final Map<String, String> classMap;
		final Map<String, String> methodNameMap;
		final Map<String, String> fieldNameMap;

		Loaded(List<SignaturePatch> signaturePatches, List<TrailingArgsPatch> trailingArgsPatches,
				List<LeadingArgsPatch> leadingArgsPatches, List<ShadowFieldTypePatch> shadowFieldTypePatches,
				List<ShadowFieldRedirectPatch> shadowFieldRedirectPatches,
				List<CallbackInfoReturnablePatch> cirPatches, List<CallGrowthPatch> callGrowthPatches,
				List<AsmPatch> asmPatches, List<AnnotationSelectorPatch> annotationSelectorPatches,
				List<MixinRetargetPatch> mixinRetargetPatches,
				List<MethodBodyPatch> methodBodyPatches,
				List<WrapMethodPatch> wrapMethodPatches,
				List<RedirectTrailingArgsPatch> redirectTrailingArgsPatches,
				List<HelperCallPatch> helperCallPatches,
				List<OperationCallGrowthPatch> operationCallGrowthPatches,
				List<InjectorRewritePatch> injectorRewritePatches,
				List<InsertParamPatch> insertParamPatches,
				List<AsmMethodPatch> asmMethodPatches,
				List<SplitWrapOperationPatch> splitWrapOperationPatches,
				List<BridgeFieldPatch> bridgeFieldPatches,
				Map<String, String> classMap, Map<String, String> methodNameMap,
				Map<String, String> fieldNameMap) {
			this.signaturePatches = signaturePatches;
			this.trailingArgsPatches = trailingArgsPatches;
			this.leadingArgsPatches = leadingArgsPatches;
			this.shadowFieldTypePatches = shadowFieldTypePatches;
			this.shadowFieldRedirectPatches = shadowFieldRedirectPatches;
			this.cirPatches = cirPatches;
			this.callGrowthPatches = callGrowthPatches;
			this.asmPatches = asmPatches;
			this.annotationSelectorPatches = annotationSelectorPatches;
			this.mixinRetargetPatches = mixinRetargetPatches;
			this.methodBodyPatches = methodBodyPatches;
			this.wrapMethodPatches = wrapMethodPatches;
			this.redirectTrailingArgsPatches = redirectTrailingArgsPatches;
			this.helperCallPatches = helperCallPatches;
			this.operationCallGrowthPatches = operationCallGrowthPatches;
			this.injectorRewritePatches = injectorRewritePatches;
			this.insertParamPatches = insertParamPatches;
			this.asmMethodPatches = asmMethodPatches;
			this.splitWrapOperationPatches = splitWrapOperationPatches;
			this.bridgeFieldPatches = bridgeFieldPatches;
			this.classMap = classMap;
			this.methodNameMap = methodNameMap;
			this.fieldNameMap = fieldNameMap;
		}
	}

	private static Loaded loadMappings() {
		List<SignaturePatch> signaturePatches = new ArrayList<>();
		List<TrailingArgsPatch> trailingArgsPatches = new ArrayList<>();
		List<LeadingArgsPatch> leadingArgsPatches = new ArrayList<>();
		List<ShadowFieldTypePatch> shadowFieldTypePatches = new ArrayList<>();
		List<ShadowFieldRedirectPatch> shadowFieldRedirectPatches = new ArrayList<>();
		List<CallbackInfoReturnablePatch> cirPatches = new ArrayList<>();
		List<CallGrowthPatch> callGrowthPatches = new ArrayList<>();
		List<AsmPatch> asmPatches = new ArrayList<>();
		List<AnnotationSelectorPatch> annotationSelectorPatches = new ArrayList<>();
		List<MixinRetargetPatch> mixinRetargetPatches = new ArrayList<>();
		List<MethodBodyPatch> methodBodyPatches = new ArrayList<>();
		List<String> methodBodyPatchInstructions = new ArrayList<>();
		String[] currentMethodBodyPatchHeader = null;
		String[] currentAsmMethodPatchHeader = null;
		String asmMethodPatchJavaName = null;
		String asmMethodPatchDesc = null;
		String asmMethodPatchAnnotation = null;
		List<String[]> asmMethodPatchElements = new ArrayList<>();
		List<String[]> asmMethodPatchAtElements = new ArrayList<>();
		List<String[]> asmMethodPatchParamAnnotations = new ArrayList<>();
		List<String> asmMethodPatchInstructions = new ArrayList<>();
		List<WrapMethodPatch> wrapMethodPatches = new ArrayList<>();
		List<String> wrapMethodPatchInstructions = new ArrayList<>();
		String[] currentWrapMethodPatchHeader = null;
		List<HelperCallPatch> helperCallPatches = new ArrayList<>();
		List<RedirectTrailingArgsPatch> redirectTrailingArgsPatches = new ArrayList<>();
		List<OperationCallGrowthPatch> operationCallGrowthPatches = new ArrayList<>();
		List<InjectorRewritePatch> injectorRewritePatches = new ArrayList<>();
		List<InsertParamPatch> insertParamPatches = new ArrayList<>();
		List<AsmMethodPatch> asmMethodPatches = new ArrayList<>();
		List<SplitWrapOperationPatch> splitWrapOperationPatches = new ArrayList<>();
		List<BridgeFieldPatch> bridgeFieldPatches = new ArrayList<>();
		String[] currentSplitWrapOperationHeader = null;
		List<SplitWrapOperationPatch.Variant> currentSplitWrapOperationVariants = new ArrayList<>();
		Map<String, String> classMap = new HashMap<>();
		Map<String, String> methodNameMap = new HashMap<>();
		Map<String, String> fieldNameMap = new HashMap<>();

		try (InputStream in = PaperModSignatureCompat.class.getClassLoader().getResourceAsStream(MAPPINGS_RESOURCE_PATH)) {
			if (in == null) {
				Log.warn(LogCategory.GAME_PATCH, "%s not found on the classpath — no mod "
						+ "compat patches will be applied", MAPPINGS_RESOURCE_PATH);
				return new Loaded(signaturePatches, trailingArgsPatches, leadingArgsPatches, shadowFieldTypePatches,
						shadowFieldRedirectPatches,
						cirPatches, callGrowthPatches, asmPatches, annotationSelectorPatches,
						mixinRetargetPatches,
						methodBodyPatches,
						wrapMethodPatches,
						redirectTrailingArgsPatches,
						helperCallPatches,
						operationCallGrowthPatches,
						injectorRewritePatches,
						insertParamPatches,
						asmMethodPatches,
						splitWrapOperationPatches,
						bridgeFieldPatches,
						classMap, methodNameMap, fieldNameMap);
			}

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String rawLine;
				int lineNo = 0;
				String currentClassOld = null;

				while ((rawLine = reader.readLine()) != null) {
					lineNo++;
					boolean indented = rawLine.startsWith("\t") || rawLine.startsWith("    ");
					String line = rawLine.trim();
					if (line.isEmpty() || line.startsWith("#")) continue;

					String[] parts = line.split("\t");

					if (!indented && currentMethodBodyPatchHeader != null && !"INSTR".equals(parts[0])) {
						methodBodyPatches.add(new MethodBodyPatch(
								currentMethodBodyPatchHeader[0], currentMethodBodyPatchHeader[1],
								currentMethodBodyPatchHeader[2], currentMethodBodyPatchHeader[3],
								currentMethodBodyPatchHeader[4], currentMethodBodyPatchHeader[5],
								currentMethodBodyPatchHeader.length > 6 ? currentMethodBodyPatchHeader[6] : null,
								currentMethodBodyPatchHeader.length > 7 ? currentMethodBodyPatchHeader[7] : null,
								new ArrayList<>(methodBodyPatchInstructions)));
						currentMethodBodyPatchHeader = null;
						methodBodyPatchInstructions.clear();
					}
					if (!indented && currentWrapMethodPatchHeader != null && !"INSTR".equals(parts[0])) {
						wrapMethodPatches.add(new WrapMethodPatch(
								currentWrapMethodPatchHeader[0], currentWrapMethodPatchHeader[1],
								currentWrapMethodPatchHeader[2], currentWrapMethodPatchHeader[3],
								currentWrapMethodPatchHeader[4],
								new ArrayList<>(wrapMethodPatchInstructions)));
						currentWrapMethodPatchHeader = null;
						wrapMethodPatchInstructions.clear();
					}
					if (!indented && currentSplitWrapOperationHeader != null && !"VARIANT".equals(parts[0])) {
						List<SplitWrapOperationPatch.Variant> variantsCopy = new ArrayList<>(currentSplitWrapOperationVariants);
						splitWrapOperationPatches.add(new SplitWrapOperationPatch(
								currentSplitWrapOperationHeader[0], currentSplitWrapOperationHeader[1],
								currentSplitWrapOperationHeader[2], currentSplitWrapOperationHeader[3],
								currentSplitWrapOperationHeader[4], variantsCopy));
						currentSplitWrapOperationHeader = null;
						currentSplitWrapOperationVariants.clear();
					}

					if (!indented && currentAsmMethodPatchHeader != null
							&& !"JAVA_NAME".equals(parts[0]) && !"DESC".equals(parts[0])
							&& !"ANNOTATION".equals(parts[0]) && !"ELEM".equals(parts[0])
							&& !"AT_ELEM".equals(parts[0]) && !"PARAM_ELEM".equals(parts[0])
							&& !"INSTR".equals(parts[0])) {
						if (asmMethodPatchAnnotation == null || asmMethodPatchDesc == null) {
							Log.warn(LogCategory.GAME_PATCH, "asmMethodPatch block for %s#%s ended "
									+ "without ANNOTATION and/or DESC — skipping this patch",
									currentAsmMethodPatchHeader[0], currentAsmMethodPatchHeader[1]);
						} else {
							asmMethodPatches.add(new AsmMethodPatch(
									currentAsmMethodPatchHeader[0], currentAsmMethodPatchHeader[1],
									currentAsmMethodPatchHeader[2],
									asmMethodPatchJavaName, asmMethodPatchDesc, asmMethodPatchAnnotation,
									new ArrayList<>(asmMethodPatchElements),
									new ArrayList<>(asmMethodPatchAtElements),
									new ArrayList<>(asmMethodPatchParamAnnotations),
									new ArrayList<>(asmMethodPatchInstructions)));
						}
						currentAsmMethodPatchHeader = null;
						asmMethodPatchJavaName = null;
						asmMethodPatchDesc = null;
						asmMethodPatchAnnotation = null;
						asmMethodPatchElements.clear();
						asmMethodPatchAtElements.clear();
						asmMethodPatchParamAnnotations.clear();
						asmMethodPatchInstructions.clear();
					}

					if (!indented) {
						if ("CLASS".equals(parts[0]) && parts.length == 3) {
							currentClassOld = parts[1];
							classMap.put(parts[1], parts[2]);
						} else if ("signaturePatch".equals(parts[0]) && (parts.length == 7 || parts.length == 8)) {
							currentClassOld = null;
							String targetMethod = parts.length == 8 ? parts[7] : null;
							signaturePatches.add(new SignaturePatch(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], targetMethod));
						} else if ("trailingArgsPatch".equals(parts[0]) && (parts.length == 4 || parts.length == 5)) {
							currentClassOld = null;
							String targetMethod = parts.length == 5 ? parts[4] : null;
							trailingArgsPatches.add(new TrailingArgsPatch(parts[1], parts[2], parts[3], targetMethod));
						} else if ("leadingArgsPatch".equals(parts[0]) && (parts.length == 4 || parts.length == 5)) {
							currentClassOld = null;
							String targetMethod = parts.length == 5 ? parts[4] : null;
							leadingArgsPatches.add(new LeadingArgsPatch(parts[1], parts[2], parts[3], targetMethod));
						} else if ("shadowFieldTypePatch".equals(parts[0]) && parts.length == 5) {
							currentClassOld = null;
							shadowFieldTypePatches.add(new ShadowFieldTypePatch(parts[1], parts[2], parts[3], parts[4]));
						} else if ("shadowFieldRedirectPatch".equals(parts[0]) && parts.length == 7) {
							currentClassOld = null;
							shadowFieldRedirectPatches.add(new ShadowFieldRedirectPatch(
									parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]));
						} else if ("callbackInfoReturnablePatch".equals(parts[0]) && parts.length == 3) {
							currentClassOld = null;
							cirPatches.add(new CallbackInfoReturnablePatch(parts[1], parts[2]));
						} else if ("callGrowthPatch".equals(parts[0]) && parts.length >= 5) {
							currentClassOld = null;
							List<String> argSpecs = new ArrayList<>();
							for (int i = 4; i < parts.length; i++) argSpecs.add(parts[i]);
							callGrowthPatches.add(new CallGrowthPatch(parts[1], parts[2], parts[3], argSpecs));
						} else if ("ASM".equals(parts[0]) && parts.length == 3) {
							currentClassOld = null;
							asmPatches.add(new AsmPatch(parts[1], parts[2]));
						} else if ("annotationSelectorPatch".equals(parts[0]) && parts.length >= 4 && parts.length <= 6) {
							currentClassOld = null;
							Integer idx = null;
							if (parts.length >= 5 && !"-".equals(parts[4])) {
								try {
									idx = Integer.valueOf(parts[4]);
								} catch (NumberFormatException e) {
									Log.warn(LogCategory.GAME_PATCH, "annotationSelectorPatch line %d: "
											+ "invalid index \"%s\" — ignoring index", lineNo, parts[4]);
								}
							}
							java.util.Map<String, String> opts = new java.util.HashMap<>();
							if (parts.length == 6) {
								for (String opt : parts[5].split(",")) {
									opt = opt.trim();
									if (opt.isEmpty()) continue;
									int eq = opt.indexOf('=');
									if (eq >= 0) opts.put(opt.substring(0, eq), opt.substring(eq + 1));
									else opts.put(opt, "true");
								}
							}
							annotationSelectorPatches.add(new AnnotationSelectorPatch(parts[1], parts[2], parts[3], idx, opts));
						} else if ("mixinRetargetPatch".equals(parts[0]) && parts.length == 4) {
							currentClassOld = null;
							mixinRetargetPatches.add(new MixinRetargetPatch(parts[1], parts[2], parts[3]));
						} else if ("bridgeMethodPatch".equals(parts[0]) && parts.length == 5) {
							currentClassOld = null; // consumed by GameBridges, which re-reads this file
						} else if ("methodBodyPatch".equals(parts[0]) && (parts.length == 7 || parts.length == 8 || parts.length == 9)) {
							currentClassOld = null;
							currentMethodBodyPatchHeader = new String[] {
								parts[1], parts[2], parts[3], parts[4],
								"-".equals(parts[5]) ? null : parts[5],
								parts[6],
								parts.length >= 8 && !"-".equals(parts[7]) ? parts[7] : null,
								parts.length == 9 && !"-".equals(parts[8]) ? parts[8] : null
							};
							methodBodyPatchInstructions.clear();
						} else if ("wrapMethodPatch".equals(parts[0]) && parts.length == 6) {
							currentClassOld = null;
							currentWrapMethodPatchHeader = new String[] {
								parts[1], parts[2], parts[3], parts[4], parts[5]
							};
							wrapMethodPatchInstructions.clear();
						} else if ("redirectTrailingArgsPatch".equals(parts[0]) && (parts.length == 8 || parts.length == 9)) {
							currentClassOld = null;
							redirectTrailingArgsPatches.add(new RedirectTrailingArgsPatch(
									parts[1], parts[2], parts[3], parts[4],
									"-".equals(parts[5]) ? null : parts[5],
									parts[6], parts[7],
									parts.length > 8 ? parts[8] : "()V"));
						} else if ("helperCallPatch".equals(parts[0]) && parts.length == 10) {
							currentClassOld = null;
							helperCallPatches.add(new HelperCallPatch(
									parts[1], parts[2], parts[3], parts[4],
									"-".equals(parts[5]) ? null : parts[5],
									"-".equals(parts[6]) ? null : parts[6],
									parts[7], parts[8], parts[9]));
						} else if ("operationCallGrowthPatch".equals(parts[0]) && parts.length >= 4) {
							currentClassOld = null;
							List<String> argSpecs = new ArrayList<>();
							for (int i = 3; i < parts.length; i++) argSpecs.add(parts[i]);
							operationCallGrowthPatches.add(new OperationCallGrowthPatch(parts[1], parts[2], argSpecs));
						} else if ("injectorRewritePatch".equals(parts[0]) && parts.length == 13) {
							currentClassOld = null;
							injectorRewritePatches.add(new InjectorRewritePatch(
									parts[1], parts[2],
									"-".equals(parts[3]) ? null : parts[3],
									"-".equals(parts[4]) ? null : parts[4],
									parts[5],
									"-".equals(parts[6]) ? null : parts[6],
									"-".equals(parts[7]) ? null : parts[7],
									"-".equals(parts[8]) ? null : parts[8],
									parts[9],
									parts[10],
									parts[11],
									parts[12]));
						} else if ("insertParamPatch".equals(parts[0]) && (parts.length == 6 || parts.length == 7)) {
							currentClassOld = null;
							try {
								int paramIndex = Integer.parseInt(parts[4]);
								insertParamPatches.add(new InsertParamPatch(
										parts[1], parts[2],
										"-".equals(parts[3]) ? null : parts[3],
										paramIndex, parts[5],
										parts.length == 7 ? parts[6] : null));
							} catch (NumberFormatException e) {
								Log.warn(LogCategory.GAME_PATCH, "insertParamPatch line %d: invalid "
										+ "paramIndex \"%s\" — skipping", lineNo, parts[4]);
							}
						} else if ("asmMethodPatch".equals(parts[0]) && parts.length == 4) {
							currentClassOld = null;
							currentAsmMethodPatchHeader = new String[] { parts[1], parts[2], parts[3] };
							asmMethodPatchJavaName = null;
							asmMethodPatchDesc = null;
							asmMethodPatchAnnotation = null;
							asmMethodPatchElements.clear();
							asmMethodPatchAtElements.clear();
							asmMethodPatchParamAnnotations.clear();
							asmMethodPatchInstructions.clear();
						} else if ("splitWrapOperationPatch".equals(parts[0]) && parts.length == 6) {
							currentClassOld = null;
							currentSplitWrapOperationHeader = new String[] {
								parts[1], parts[2], parts[3], parts[4], parts[5]
							};
							currentSplitWrapOperationVariants.clear();
						} else if ("bridgeFieldPatch".equals(parts[0]) && parts.length == 8) {
							currentClassOld = null;
							bridgeFieldPatches.add(new BridgeFieldPatch(
									parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7]));
						} else {
							Log.warn(LogCategory.GAME_PATCH, "Skipping malformed %s line %d: %s",
									MAPPINGS_RESOURCE_PATH, lineNo, line);
						}
					} else if (currentMethodBodyPatchHeader != null && "INSTR".equals(parts[0]) && parts.length >= 2) {
						StringBuilder sb = new StringBuilder(parts[1]);
						for (int k = 2; k < parts.length; k++) {
							sb.append(' ').append(parts[k]);
						}
						methodBodyPatchInstructions.add(sb.toString());
					} else if (currentWrapMethodPatchHeader != null && "INSTR".equals(parts[0]) && parts.length >= 2) {
						StringBuilder sb = new StringBuilder(parts[1]);
						for (int k = 2; k < parts.length; k++) {
							sb.append(' ').append(parts[k]);
						}
						wrapMethodPatchInstructions.add(sb.toString());
					} else if (currentAsmMethodPatchHeader != null && "JAVA_NAME".equals(parts[0]) && parts.length == 2) {
						asmMethodPatchJavaName = parts[1];
					} else if (currentAsmMethodPatchHeader != null && "DESC".equals(parts[0]) && parts.length == 2) {
						asmMethodPatchDesc = parts[1];
					} else if (currentAsmMethodPatchHeader != null && "ANNOTATION".equals(parts[0]) && parts.length == 2) {
						asmMethodPatchAnnotation = parts[1];
					} else if (currentAsmMethodPatchHeader != null && "ELEM".equals(parts[0]) && parts.length == 3) {
						asmMethodPatchElements.add(new String[] { parts[1], parts[2] });
					} else if (currentAsmMethodPatchHeader != null && "AT_ELEM".equals(parts[0]) && parts.length == 3) {
						asmMethodPatchAtElements.add(new String[] { parts[1], parts[2] });
					// Cú pháp: PARAM_ELEM <paramIndex> <annotationDesc> <key> <value>
					} else if (currentAsmMethodPatchHeader != null && "PARAM_ELEM".equals(parts[0]) && parts.length == 5) {
						asmMethodPatchParamAnnotations.add(new String[] { parts[1], parts[2], parts[3], parts[4] });
					} else if (currentAsmMethodPatchHeader != null && "INSTR".equals(parts[0]) && parts.length >= 2) {
						StringBuilder sb = new StringBuilder(parts[1]);
						for (int k = 2; k < parts.length; k++) {
							sb.append(' ').append(parts[k]);
						}
						asmMethodPatchInstructions.add(sb.toString());
					} else if (currentSplitWrapOperationHeader != null && "VARIANT".equals(parts[0]) && parts.length == 6) {
						currentSplitWrapOperationVariants.add(new SplitWrapOperationPatch.Variant(
								parts[1], parts[2], parts[3], parts[4], parts[5]));
					} else if (currentClassOld == null) {
						Log.warn(LogCategory.GAME_PATCH, "Skipping %s line %d (METHOD/FIELD with no "
								+ "preceding CLASS line): %s", MAPPINGS_RESOURCE_PATH, lineNo, line);
					} else if ("METHOD".equals(parts[0]) && parts.length == 5) {
						methodNameMap.put(currentClassOld + "." + parts[2] + parts[1], parts[4]);
					} else if ("FIELD".equals(parts[0]) && parts.length == 5) {
						fieldNameMap.put(currentClassOld + "." + parts[2] + parts[1], parts[4]);
					} else {
						Log.warn(LogCategory.GAME_PATCH, "Skipping malformed %s line %d: %s",
								MAPPINGS_RESOURCE_PATH, lineNo, line);
					}
				}
			}

			if (currentMethodBodyPatchHeader != null) {
				methodBodyPatches.add(new MethodBodyPatch(
						currentMethodBodyPatchHeader[0], currentMethodBodyPatchHeader[1],
						currentMethodBodyPatchHeader[2], currentMethodBodyPatchHeader[3],
						currentMethodBodyPatchHeader[4], currentMethodBodyPatchHeader[5],
						currentMethodBodyPatchHeader.length > 6 ? currentMethodBodyPatchHeader[6] : null,
						currentMethodBodyPatchHeader.length > 7 ? currentMethodBodyPatchHeader[7] : null,
						new ArrayList<>(methodBodyPatchInstructions)));
				currentMethodBodyPatchHeader = null;
				methodBodyPatchInstructions.clear();
			}
			if (currentWrapMethodPatchHeader != null) {
				wrapMethodPatches.add(new WrapMethodPatch(
						currentWrapMethodPatchHeader[0], currentWrapMethodPatchHeader[1],
						currentWrapMethodPatchHeader[2], currentWrapMethodPatchHeader[3],
						currentWrapMethodPatchHeader[4],
						new ArrayList<>(wrapMethodPatchInstructions)));
				currentWrapMethodPatchHeader = null;
				wrapMethodPatchInstructions.clear();
			}
			if (currentAsmMethodPatchHeader != null) {
				if (asmMethodPatchAnnotation == null || asmMethodPatchDesc == null) {
					Log.warn(LogCategory.GAME_PATCH, "asmMethodPatch block for %s#%s at EOF ended "
							+ "without ANNOTATION and/or DESC — skipping this patch",
							currentAsmMethodPatchHeader[0], currentAsmMethodPatchHeader[1]);
				} else {
					asmMethodPatches.add(new AsmMethodPatch(
							currentAsmMethodPatchHeader[0], currentAsmMethodPatchHeader[1],
							currentAsmMethodPatchHeader[2],
							asmMethodPatchJavaName, asmMethodPatchDesc, asmMethodPatchAnnotation,
							new ArrayList<>(asmMethodPatchElements),
							new ArrayList<>(asmMethodPatchAtElements),
							new ArrayList<>(asmMethodPatchParamAnnotations),
							new ArrayList<>(asmMethodPatchInstructions)));
				}
				currentAsmMethodPatchHeader = null;
				asmMethodPatchElements.clear();
				asmMethodPatchAtElements.clear();
				asmMethodPatchParamAnnotations.clear();
				asmMethodPatchInstructions.clear();
			}

			if (currentSplitWrapOperationHeader != null) {
				splitWrapOperationPatches.add(new SplitWrapOperationPatch(
						currentSplitWrapOperationHeader[0], currentSplitWrapOperationHeader[1],
						currentSplitWrapOperationHeader[2], currentSplitWrapOperationHeader[3],
						currentSplitWrapOperationHeader[4], new ArrayList<>(currentSplitWrapOperationVariants)));
				currentSplitWrapOperationHeader = null;
				currentSplitWrapOperationVariants.clear();
			}

			Log.info(LogCategory.GAME_PATCH, "Loaded from %s: %d class rename(s), %d method "
					+ "rename(s), %d field rename(s), %d signature patch(es), %d trailing-args "
					+ "patch(es), %d leading-args patch(es), %d shadow-field-type patch(es), %d "
					+ "shadow-field-redirect patch(es), %d "
					+ "CallbackInfoReturnable patch(es), %d call-growth patch(es), %d ASM patch(es), "
					+ "%d annotation-selector patch(es), %d mixin-retarget patch(es), %d method-body patch(es), "
					+ "%d wrap-method patch(es), %d helper-call patch(es), %d operation-call-growth patch(es), "
					+ "%d injector-rewrite patch(es), %d insert-param patch(es), %d bridge-field patch(es)",
					MAPPINGS_RESOURCE_PATH, classMap.size(), methodNameMap.size(), fieldNameMap.size(),
					signaturePatches.size(), trailingArgsPatches.size(), leadingArgsPatches.size(),
					shadowFieldTypePatches.size(), shadowFieldRedirectPatches.size(),
					cirPatches.size(), callGrowthPatches.size(), asmPatches.size(),
					annotationSelectorPatches.size(), mixinRetargetPatches.size(), methodBodyPatches.size(),
					wrapMethodPatches.size(), helperCallPatches.size(), operationCallGrowthPatches.size(),
					injectorRewritePatches.size(), insertParamPatches.size(), bridgeFieldPatches.size());
		} catch (IOException e) {
			Log.warn(LogCategory.GAME_PATCH, "Failed to read " + MAPPINGS_RESOURCE_PATH, e);
		}

		return new Loaded(signaturePatches, trailingArgsPatches, leadingArgsPatches, shadowFieldTypePatches,
				shadowFieldRedirectPatches,
				cirPatches, callGrowthPatches, asmPatches, annotationSelectorPatches,
				mixinRetargetPatches,
				methodBodyPatches,
				wrapMethodPatches,
				redirectTrailingArgsPatches,
				helperCallPatches,
				operationCallGrowthPatches,
				injectorRewritePatches,
				insertParamPatches,
				asmMethodPatches,
				splitWrapOperationPatches,
				bridgeFieldPatches,
				classMap, methodNameMap, fieldNameMap);
	}

	public static byte[] fixIfNeeded(String name, byte[] bytes) {
		bytes = applyGameBridges(name, bytes);
		bytes = applyGeneralRemap(bytes);

		for (SignaturePatch patch : SIGNATURE_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyIfMixinTargets(name, bytes, patch);
			}
		}

		for (TrailingArgsPatch patch : TRAILING_ARGS_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyIfMixinTargetsTrailing(name, bytes, patch);
			}
		}

		for (LeadingArgsPatch patch : LEADING_ARGS_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyIfMixinTargetsLeading(name, bytes, patch);
			}
		}

		for (ShadowFieldTypePatch patch : SHADOW_FIELD_TYPE_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyShadowFieldTypePatch(name, bytes, patch);
			}
		}

		for (ShadowFieldRedirectPatch patch : SHADOW_FIELD_REDIRECT_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyShadowFieldRedirectPatch(name, bytes, patch);
			}
		}

		for (CallbackInfoReturnablePatch patch : CIR_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyCallbackInfoReturnablePatch(name, bytes, patch);
			}
		}

		for (CallGrowthPatch patch : CALL_GROWTH_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyCallGrowthPatch(name, bytes, patch);
			}
		}

		for (AsmPatch patch : ASM_PATCHES) {
			if (mightReferenceClass(bytes, patch.oldDesc)) {
				bytes = applyAsmPatch(name, bytes, patch);
			}
		}

		for (AnnotationSelectorPatch patch : ANNOTATION_SELECTOR_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyAnnotationSelectorPatch(name, bytes, patch);
			}
		}

		for (MixinRetargetPatch patch : MIXIN_RETARGET_PATCHES) {
			bytes = applyMixinRetargetPatch(name, bytes, patch);
		}

		for (MethodBodyPatch patch : METHOD_BODY_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyMethodBodyPatch(name, bytes, patch);
			}
		}

		for (WrapMethodPatch patch : WRAP_METHOD_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyWrapMethodPatch(name, bytes, patch);
			}
		}

		for (RedirectTrailingArgsPatch patch : REDIRECT_TRAILING_ARGS_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyRedirectTrailingArgsPatch(name, bytes, patch);
			}
		}

		for (HelperCallPatch patch : HELPER_CALL_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyHelperCallPatch(name, bytes, patch);
			}
		}

		for (InsertParamPatch patch : INSERT_PARAM_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyInsertParamPatch(name, bytes, patch);
			}
		}

		for (OperationCallGrowthPatch patch : OPERATION_CALL_GROWTH_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyOperationCallGrowthPatch(name, bytes, patch);
			}
		}

		for (InjectorRewritePatch patch : INJECTOR_REWRITE_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyInjectorRewrite(name, bytes, patch);
			}
		}

		for (SplitWrapOperationPatch patch : SPLIT_WRAP_OPERATION_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applySplitWrapOperationPatch(name, bytes, patch);
			}
		}

		for (AsmMethodPatch patch : ASM_METHOD_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyAsmMethodPatch(name, bytes, patch);
			}
		}

		for (BridgeFieldPatch patch : BRIDGE_FIELD_PATCHES) {
			if (mightReferenceClass(bytes, patch.targetClassInternalName)
					|| mightReferenceClass(bytes, patch.targetClassInternalName.replace('/', '.'))) {
				bytes = applyBridgeFieldPatch(name, bytes, patch);
			}
		}

		bytes = normalizeRedirectAt(name, bytes);

		// Safety net: last, so every other patch above has already had its chance to make the mixin fit.
		bytes = relaxInjectorRequirements(name, bytes);

		return bytes;
	}

	/**
	 * bridgeMethodPatch (mappings.tiny): {@code bridgeMethodPatch <class> <method> <vanillaDesc> <actualDesc>}.
	 *
	 * <p>Paper/CraftBukkit changed some vanilla method DESCRIPTORS (typically {@code void} -> {@code int/boolean}, e.g.
	 * ServerPlayer.nextContainerCounter()V -> ()I). A mod's {@code @Invoker}/{@code @Shadow}/{@code @Accessor} still names the
	 * vanilla descriptor, Mixin finds no such method, and - unlike {@code @Inject} - these can't be made optional with
	 * require=0, so the server dies in bootstrap ("No candidates were found matching nextContainerCounter()V").
 *
	 * <p>This runs on the PRE-Mixin bytes of the game class (fixIfNeeded is called for every class Knot loads) and adds a
	 * public synthetic bridge {@code method vanillaDesc} that calls the real {@code method actualDesc} and discards its result.
	 * Only "vanilla returns void" bridges with identical parameters are supported. Nothing is added if the class already has
	 * the vanilla descriptor or lacks the real method.
	 */
	private static final class GameBridges {
		static final Map<String, List<String[]>> BY_CLASS = load();

		private static Map<String, List<String[]>> load() {
			Map<String, List<String[]>> map = new HashMap<>();

			try (InputStream in = PaperModSignatureCompat.class.getClassLoader().getResourceAsStream(MAPPINGS_RESOURCE_PATH)) {
				if (in != null) {
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
						String raw;

						while ((raw = reader.readLine()) != null) {
							String line = raw.trim();
							if (line.isEmpty() || line.startsWith("#")) continue;
							String[] parts = line.split("\t");

							if ("bridgeMethodPatch".equals(parts[0]) && parts.length == 5) {
								map.computeIfAbsent(parts[1].replace('/', '.'), k -> new ArrayList<>())
										.add(new String[] { parts[2], parts[3], parts[4] });
							}
						}
					}
				}
			} catch (Throwable t) {
				Log.warn(LogCategory.GAME_PATCH, "Could not read bridgeMethodPatch entries: %s", t);
			}

			return map;
		}
	}

	private static byte[] applyGameBridges(String name, byte[] bytes) {
		List<String[]> list = GameBridges.BY_CLASS.get(name.replace('/', '.'));
		if (list == null) return bytes;

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int added = 0;

			for (String[] p : list) {
				String mName = p[0];
				String wantDesc = p[1];
				String actualDesc = p[2];
				boolean hasActual = false;
				boolean hasWanted = false;
				int actualAccess = 0;

				for (MethodNode m : node.methods) {
					if (!m.name.equals(mName)) continue;

					if (m.desc.equals(actualDesc)) {
						hasActual = true;
						actualAccess = m.access;
					} else if (m.desc.equals(wantDesc)) {
						hasWanted = true;
					}
				}

				if (!hasActual || hasWanted) continue;

				org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(wantDesc);

				if (!java.util.Arrays.equals(args, org.objectweb.asm.Type.getArgumentTypes(actualDesc))
						|| org.objectweb.asm.Type.getReturnType(wantDesc).getSort() != org.objectweb.asm.Type.VOID) {
					Log.warn(LogCategory.GAME_PATCH, "bridgeMethodPatch %s.%s%s -> %s unsupported "
							+ "(only void bridges with identical parameters)", name, mName, wantDesc, actualDesc);
					continue;
				}

				boolean isStatic = (actualAccess & Opcodes.ACC_STATIC) != 0;
				MethodNode bridge = new MethodNode(
						Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (isStatic ? Opcodes.ACC_STATIC : 0),
						mName, wantDesc, null, null);
				org.objectweb.asm.tree.InsnList il = new org.objectweb.asm.tree.InsnList();
				int slot = 0;

				if (!isStatic) {
					il.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
					slot = 1;
				}

				for (org.objectweb.asm.Type a : args) {
					il.add(new org.objectweb.asm.tree.VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot));
					slot += a.getSize();
				}

				int op = isStatic ? Opcodes.INVOKESTATIC
						: (actualAccess & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
				il.add(new org.objectweb.asm.tree.MethodInsnNode(op, node.name, mName, actualDesc,
						(node.access & Opcodes.ACC_INTERFACE) != 0));
				org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(actualDesc);

				if (ret.getSort() != org.objectweb.asm.Type.VOID) {
					il.add(new org.objectweb.asm.tree.InsnNode(ret.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
				}

				il.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
				bridge.instructions = il;
				node.methods.add(bridge);
				added++;
				Log.info(LogCategory.GAME_PATCH, "Added bridge %s.%s%s -> %s (the vanilla descriptor that mods' "
						+ "@Invoker/@Shadow expect)", name, mName, wantDesc, actualDesc);
			}

			if (added == 0) return bytes;

			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "bridgeMethodPatch failed for %s: %s", name, t);
			return bytes;
		}
	}

	/**
	 * MixinExtras' FactoryRedirectWrapperMixinTransformer does {@code AnnotationNode at = Annotations.getValue(redirect, "at")}
	 * for EVERY {@code @Redirect} of every mixin that targets a class, before any injection happens. {@code Redirect.at} is a
	 * single {@code @At}, but a mixin whose annotation was written (or rewritten by a bytecode patch) with {@code at} as an
	 * ARRAY stores a List there, so that cast throws {@code ClassCastException: ArrayList cannot be cast to AnnotationNode}
	 * and the target class (here ServerGamePacketListenerImpl, i.e. all of Fabric networking) fails to load, taking every mod
	 * that touches ServerPlayConnectionEvents down with it.
	 *
	 * <p>A one-element list is replaced by its element (what the author meant). Each fix is logged with the offending
	 * mixin class and method so the mod that does this can be identified. Always on; independent of the relax switch.
	 */
	private static byte[] normalizeRedirectAt(String name, byte[] bytes) {
		if (!mightReferenceClass(bytes, "org/spongepowered/asm/mixin/injection/Redirect")) {
			return bytes;
		}

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int fixed = 0;

			for (MethodNode method : node.methods) {
				for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
					if (list == null) continue;

					for (AnnotationNode a : list) {
						if (!"Lorg/spongepowered/asm/mixin/injection/Redirect;".equals(a.desc) || a.values == null) continue;

						for (int i = 0; i + 1 < a.values.size(); i += 2) {
							if (!"at".equals(a.values.get(i)) || !(a.values.get(i + 1) instanceof List)) continue;

							List<?> atList = (List<?>) a.values.get(i + 1);

							if (atList.size() == 1 && atList.get(0) instanceof AnnotationNode) {
								a.values.set(i + 1, atList.get(0));
								fixed++;
								Log.info(LogCategory.GAME_PATCH, "@Redirect.at was an array in %s#%s%s - unwrapped it "
										+ "(MixinExtras would have thrown ClassCastException)", name, method.name, method.desc);
							} else {
								Log.warn(LogCategory.GAME_PATCH, "@Redirect.at in %s#%s%s is a list of %d elements - "
										+ "Mixin cannot use that; this mixin will fail", name, method.name, method.desc, atList.size());
							}
						}
					}
				}
			}

			if (fixed == 0) return bytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "Could not normalize @Redirect.at in %s: %s", name, t);
			return bytes;
		}
	}

	/**
	 * Set -Dankhangbo.mixin.relax=false to restore Mixin's strict behaviour (a single injector that no longer
	 * matches Paper/Leaf then aborts the whole server start with an InjectionError, as before).
	 */
	private static final boolean RELAX_MIXIN_REQUIRE = !"false".equalsIgnoreCase(System.getProperty("ankhangbo.mixin.relax"));

	private static int relaxedMixinClasses;

	/**
	 * Mixin's default is "require = 1" (Fabric API sets defaultRequire=1 in its configs): if an injector finds nothing
	 * to inject into, Mixin throws and the whole class - often a core class such as TagLoader - fails to load, which
	 * crashes the server. Paper/Leaf rewrite enough vanilla code that this happens for some mixin after every
 Fabric API/Leaf update.
	 *
	 * <p>This sets {@code require = 0} (and {@code expect = 1} when the mod didn't give one) on every injector of
	 * every {@code @Mixin} class, so a non-matching injector no longer aborts the start: Mixin instead logs a WARN
	 * "Injection validation failed: ... expected 1 invocation(s) but 0 succeeded" and the rest of the mixin and of the
	 * class keeps working. Only that one feature of that one mod is skipped. @Accessor/@Invoker/@Shadow are untouched.
	 */
	private static byte[] relaxInjectorRequirements(String name, byte[] bytes) {
		if (!RELAX_MIXIN_REQUIRE || !mightReferenceClass(bytes, "org/spongepowered/asm/mixin/Mixin")) {
			return bytes;
		}

		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);

			if (!hasAnnotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")
					&& !hasAnnotation(node.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;")) {
				return bytes;
			}

			int changed = 0;

			for (MethodNode method : node.methods) {
				changed += relaxAnnotations(method.invisibleAnnotations);
				changed += relaxAnnotations(method.visibleAnnotations);
			}

			if (changed == 0) {
				return bytes;
			}

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);

			if (relaxedMixinClasses++ == 0) {
				Log.info(LogCategory.GAME_PATCH, "Mixin injectors are non-fatal (require=0, expect=1): a non-matching "
						+ "injector now only logs 'Injection validation failed' instead of crashing the server "
						+ "(disable with -Dankhangbo.mixin.relax=false)");
			}

			return writer.toByteArray();
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "Could not relax mixin injectors in %s: %s", name, t);
			return bytes;
		}
	}

	private static boolean hasAnnotation(List<AnnotationNode> list, String desc) {
		if (list != null) {
			for (AnnotationNode a : list) {
				if (desc.equals(a.desc)) return true;
			}
		}

		return false;
	}

	private static boolean isInjectorAnnotation(String desc) {
		if (desc == null) return false;

		if (desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
			return desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
					|| desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")
					|| desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;")
					|| desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArgs;")
					|| desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;")
					|| desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;");
		}

		// MixinExtras: WrapOperation, WrapMethod, WrapWithCondition, ModifyExpressionValue, ModifyReceiver, ModifyReturnValue
		return desc.startsWith("Lcom/llamalad7/mixinextras/injector/") && !desc.endsWith("/Operation;");
	}

	private static int relaxAnnotations(List<AnnotationNode> list) {
		int changed = 0;

		if (list == null) return 0;

		for (AnnotationNode a : list) {
			if (!isInjectorAnnotation(a.desc)) continue;

			List<Object> values = a.values != null ? a.values : new ArrayList<>();
			boolean hasRequire = false;
			boolean hasExpect = false;

			for (int i = 0; i + 1 < values.size(); i += 2) {
				Object key = values.get(i);

				if ("require".equals(key)) {
					values.set(i + 1, 0);
					hasRequire = true;
				} else if ("expect".equals(key)) {
					hasExpect = true;
				}
			}

			if (!hasRequire) {
				values.add("require");
				values.add(0);
			}

			if (!hasExpect) {
				values.add("expect");
				values.add(1);
			}

			a.values = values;
			changed++;
		}

		return changed;
	}

	private static byte[] applyAsmPatch(String name, byte[] bytes, AsmPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		boolean changed = false;

		for (MethodNode method : node.methods) {
			// Patch descriptor erased
			if (method.desc.equals(patch.oldDesc)) {
				method.desc = patch.newDesc;
				changed = true;
			}

			// Patch generic signature (THÊM ĐOẠN NÀY)
			if (method.signature != null && method.signature.equals(patch.oldDesc)) {
				method.signature = patch.newDesc;
				changed = true;
			}

			// Patch call site
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode)) continue;
				MethodInsnNode call = (MethodInsnNode) insn;
				if (call.desc.equals(patch.oldDesc)) {
					call.desc = patch.newDesc;
					changed = true;
				}
			}
		}

		if (!changed) return bytes;

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyAnnotationSelectorPatch(String name, byte[] bytes, AnnotationSelectorPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;
		List<AnnotationNode> touchedAnnotations = new ArrayList<>();
		List<MethodNode> touchedMethods = new ArrayList<>();

		for (MethodNode method : node.methods) {
			List<AnnotationNode> all = new ArrayList<>();
			if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
			if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);

			for (AnnotationNode annotation : all) {
				if (annotation.values == null) continue;

				boolean annotationTouched = false;

				for (int i = 0; i < annotation.values.size(); i += 2) {
					String key = (String) annotation.values.get(i);
					Object raw = annotation.values.get(i + 1);

					if ("method".equals(key)) {
						if (raw instanceof String) {
							if (patch.oldSelector.equals(raw)) {
								annotation.values.set(i + 1, patch.newSelector);
								changed = true;
								annotationTouched = true;
							}
						} else if (raw instanceof List) {
							@SuppressWarnings("unchecked")
							List<Object> list = (List<Object>) raw;
							for (int j = 0; j < list.size(); j++) {
								Object v = list.get(j);
								if (v instanceof String && patch.oldSelector.equals(v)) {
									list.set(j, patch.newSelector);
									changed = true;
									annotationTouched = true;
								}
							}
						}
					} else if ("at".equals(key) && raw instanceof AnnotationNode) {
						AnnotationNode atNode = (AnnotationNode) raw;
						if (atNode.values != null) {
							for (int j = 0; j < atNode.values.size(); j += 2) {
								if ("target".equals(atNode.values.get(j))) {
									Object targetRaw = atNode.values.get(j + 1);
									if (targetRaw instanceof String && patch.oldSelector.equals(targetRaw)) {
										atNode.values.set(j + 1, patch.newSelector);
										changed = true;
										annotationTouched = true;
									}
								}
							}
						}
					} else if ("at".equals(key) && raw instanceof List) {
						@SuppressWarnings("unchecked")
						List<Object> atList = (List<Object>) raw;
						for (Object atObj : atList) {
							if (!(atObj instanceof AnnotationNode)) continue;
							AnnotationNode atNode = (AnnotationNode) atObj;
							if (atNode.values == null) continue;
							for (int j = 0; j < atNode.values.size(); j += 2) {
								if ("target".equals(atNode.values.get(j))) {
									Object targetRaw = atNode.values.get(j + 1);
									if (targetRaw instanceof String && patch.oldSelector.equals(targetRaw)) {
										atNode.values.set(j + 1, patch.newSelector);
										changed = true;
										annotationTouched = true;
									}
								}
							}
						}
					}
				}

				if (annotationTouched) {
					touchedAnnotations.add(annotation);
					if (!touchedMethods.contains(method)) {
						touchedMethods.add(method);
					}
				}
			}
		}

		if (patch.options.containsKey("forceTouch")) {
			for (MethodNode method : node.methods) {
				List<AnnotationNode> all = new ArrayList<>();
				if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
				if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
				for (AnnotationNode annotation : all) {
					if (annotation.values == null) continue;
					boolean hasMethodElement = false;
					for (int i = 0; i < annotation.values.size(); i += 2) {
						if ("method".equals(annotation.values.get(i))) {
							hasMethodElement = true;
							break;
						}
					}
					if (hasMethodElement && !touchedAnnotations.contains(annotation)) {
						touchedAnnotations.add(annotation);
						if (!touchedMethods.contains(method)) {
							touchedMethods.add(method);
						}
						changed = true;
					}
				}
			}
		}

		if (patch.newIndex != null) {
			for (AnnotationNode annotation : touchedAnnotations) {
				boolean foundIndex = false;
				for (int i = 0; i < annotation.values.size(); i += 2) {
					if ("index".equals(annotation.values.get(i))) {
						annotation.values.set(i + 1, patch.newIndex);
						foundIndex = true;
						break;
					}
				}
				if (!foundIndex) {
					annotation.values.add("index");
					annotation.values.add(patch.newIndex);
				}
				changed = true;
			}
		}

		if (!patch.options.isEmpty()) {
			String atValue = patch.options.get("atValue");
			boolean removeAtTarget = patch.options.containsKey("removeAtTarget");
			String methodDesc = patch.options.get("methodDesc");
			String methodOverride = patch.options.get("methodOverride");
			String retypeParamSpec = patch.options.get("retypeParam");

			Integer atOrdinal = null;
			if (patch.options.containsKey("atOrdinal")) {
				try {
					atOrdinal = Integer.valueOf(patch.options.get("atOrdinal"));
				} catch (NumberFormatException e) {
					Log.warn(LogCategory.GAME_PATCH, "annotationSelectorPatch: invalid atOrdinal=\"%s\" — ignoring",
							patch.options.get("atOrdinal"));
				}
			}
			boolean removeAtOrdinal = patch.options.containsKey("removeAtOrdinal");

			for (AnnotationNode annotation : touchedAnnotations) {
				if (atValue != null || removeAtTarget || atOrdinal != null || removeAtOrdinal) {
					for (int i = 0; i < annotation.values.size(); i += 2) {
						String key = (String) annotation.values.get(i);
						Object raw = annotation.values.get(i + 1);
						if ("at".equals(key) && raw instanceof AnnotationNode) {
							applyOptionsToAt((AnnotationNode) raw, atValue, removeAtTarget, atOrdinal, removeAtOrdinal);
						} else if ("at".equals(key) && raw instanceof List) {
							for (Object atObj : (List<?>) raw) {
								if (atObj instanceof AnnotationNode) {
									applyOptionsToAt((AnnotationNode) atObj, atValue, removeAtTarget, atOrdinal, removeAtOrdinal);
								}
							}
						}
					}
				}

				if (methodDesc != null) {
					for (int i = 0; i < annotation.values.size(); i += 2) {
						if ("method".equals(annotation.values.get(i))) {
							Object rawM = annotation.values.get(i + 1);
							if (rawM instanceof String && patch.newSelector.equals(rawM)) {
								annotation.values.set(i + 1, patch.newSelector + methodDesc);
								changed = true;
							}
						}
					}
				}

				if (methodOverride != null) {
					for (int i = 0; i < annotation.values.size(); i += 2) {
						if (!"method".equals(annotation.values.get(i))) continue;
						Object rawM = annotation.values.get(i + 1);
						if (rawM instanceof String) {
							annotation.values.set(i + 1, methodOverride);
							changed = true;
						} else if (rawM instanceof List) {
							@SuppressWarnings("unchecked")
							List<Object> list = (List<Object>) rawM;
							for (int j = 0; j < list.size(); j++) {
								list.set(j, methodOverride);
							}
							changed = true;
						}
					}
				}
			}

			if (retypeParamSpec != null) {
				int colon = retypeParamSpec.indexOf(':');
				if (colon < 0) {
					Log.warn(LogCategory.GAME_PATCH, "annotationSelectorPatch: malformed retypeParam=\"%s\" "
							+ "— expected <index>:<newTypeDescriptor> — ignoring", retypeParamSpec);
				} else {
					try {
						int paramIndex = Integer.parseInt(retypeParamSpec.substring(0, colon));
						String newParamDesc = retypeParamSpec.substring(colon + 1);
						for (MethodNode m : touchedMethods) {
							if (retypeMethodParam(m, paramIndex, newParamDesc)) {
								changed = true;
							}
						}
					} catch (NumberFormatException e) {
						Log.warn(LogCategory.GAME_PATCH, "annotationSelectorPatch: invalid retypeParam "
								+ "index in \"%s\" — ignoring", retypeParamSpec);
					}
				}
			}
			
			String renameLocalSpec = patch.options.get("renameLocal");
			if (renameLocalSpec != null) {
				int colon = renameLocalSpec.indexOf(':');
				if (colon >= 0) {
					String oldName = renameLocalSpec.substring(0, colon);
					String newName = renameLocalSpec.substring(colon + 1);
					for (MethodNode m : touchedMethods) {
						if (renameLocalInAnnotations(m, oldName, newName)) {
							changed = true;
						}
					}
				}
			}
			
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Rewrote %s's own @Mixin selector (method= or at.target=) for %s "
				+ "from \"%s\" to \"%s\"%s",
				name, patch.targetClassInternalName.replace('/', '.'), patch.oldSelector, patch.newSelector,
				patch.newIndex != null ? (", and set index=" + patch.newIndex) : "");

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean retypeMethodParam(MethodNode method, int paramIndex, String newParamDesc) {
		Type[] args = Type.getArgumentTypes(method.desc);
		if (paramIndex < 0 || paramIndex >= args.length) {
			Log.warn(LogCategory.GAME_PATCH, "retypeMethodParam: index %d out of range (method %s has "
					+ "%d param(s)) — skipping", paramIndex, method.name, args.length);
			return false;
		}
		Type newType = Type.getType(newParamDesc);
		if (args[paramIndex].getSize() != newType.getSize()) {
			Log.warn(LogCategory.GAME_PATCH, "retypeMethodParam: size mismatch for %s param #%d "
					+ "(old=%s new=%s) — skipping to avoid corrupting local variable slots",
					method.name, paramIndex, args[paramIndex].getDescriptor(), newParamDesc);
			return false;
		}
		args[paramIndex] = newType;
		StringBuilder sb = new StringBuilder("(");
		for (Type t : args) sb.append(t.getDescriptor());
		sb.append(")").append(Type.getReturnType(method.desc).getDescriptor());
		method.desc = sb.toString();
		return true;
	}

	private static void applyOptionsToAt(AnnotationNode atNode, String atValue, boolean removeAtTarget,
			Integer atOrdinal, boolean removeAtOrdinal) {
		if (atNode.values == null) atNode.values = new ArrayList<>();

		if (atValue != null) {
			boolean found = false;
			for (int i = 0; i < atNode.values.size(); i += 2) {
				if ("value".equals(atNode.values.get(i))) {
					atNode.values.set(i + 1, atValue);
					found = true;
					break;
				}
			}
			if (!found) {
				atNode.values.add("value");
				atNode.values.add(atValue);
			}
		}

		if (removeAtTarget) {
			for (int i = atNode.values.size() - 2; i >= 0; i -= 2) {
				if ("target".equals(atNode.values.get(i))) {
					atNode.values.remove(i);
					atNode.values.remove(i);
				}
			}
		}

		if (atOrdinal != null) {
			boolean found = false;
			for (int i = 0; i < atNode.values.size(); i += 2) {
				if ("ordinal".equals(atNode.values.get(i))) {
					atNode.values.set(i + 1, atOrdinal);
					found = true;
					break;
				}
			}
			if (!found) {
				atNode.values.add("ordinal");
				atNode.values.add(atOrdinal);
			}
		}

		if (removeAtOrdinal) {
			for (int i = atNode.values.size() - 2; i >= 0; i -= 2) {
				if ("ordinal".equals(atNode.values.get(i))) {
					atNode.values.remove(i);
					atNode.values.remove(i);
				}
			}
		}
	}

	private static byte[] applyMixinRetargetPatch(String name, byte[] bytes, MixinRetargetPatch patch) {
		if (!mightReferenceClass(bytes, patch.oldTargetClassInternalName)
				&& !mightReferenceClass(bytes, patch.oldTargetClassInternalName.replace('/', '.'))) {
			return bytes;
		}

		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.oldTargetClassInternalName)) {
			return bytes;
		}

		if (patch.mixinClassInternalName != null && !patch.mixinClassInternalName.equals(node.name)) {
			return bytes;
		}

		boolean changed = false;
		List<AnnotationNode> allClassAnnotations = new ArrayList<>();
		if (node.visibleAnnotations != null) allClassAnnotations.addAll(node.visibleAnnotations);
		if (node.invisibleAnnotations != null) allClassAnnotations.addAll(node.invisibleAnnotations);

		for (AnnotationNode annotation : allClassAnnotations) {
			if (!MIXIN_ANNOTATION_DESC.equals(annotation.desc) || annotation.values == null) continue;

			List<Object> existingTargets = null;
			List<Object> valueList = null;

			for (int i = 0; i < annotation.values.size(); i += 2) {
				Object key = annotation.values.get(i);
				Object raw = annotation.values.get(i + 1);
				if (!(raw instanceof List)) continue;
				if ("targets".equals(key)) {
					existingTargets = castList(raw);
				} else if ("value".equals(key)) {
					valueList = castList(raw);
				}
			}

			if (valueList != null) {
				valueList.removeIf(v -> v instanceof Type
						&& patch.oldTargetClassInternalName.equals(((Type) v).getInternalName()));
			}

			String oldDotName = patch.oldTargetClassInternalName.replace('/', '.');
			if (existingTargets != null) {
				existingTargets.removeIf(v -> v instanceof String
						&& oldDotName.equals(((String) v).replace('/', '.')));
			}

			String newDotName = patch.newTargetClassInternalName.replace('/', '.');
			if (existingTargets != null) {
				existingTargets.add(newDotName);
			} else {
				List<Object> newTargets = new ArrayList<>();
				newTargets.add(newDotName);
				annotation.values.add("targets");
				annotation.values.add(newTargets);
			}

			changed = true;
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Retargeted %s's own @Mixin from %s to %s", name,
				patch.oldTargetClassInternalName.replace('/', '.'),
				patch.newTargetClassInternalName.replace('/', '.'));

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	@SuppressWarnings("unchecked")
	private static List<Object> castList(Object raw) {
		return (List<Object>) raw;
	}

	private static byte[] applyCallGrowthPatch(String name, byte[] bytes, CallGrowthPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		String oldParamsPrefix = "(" + patch.oldParamsDesc + ")";
		boolean changed = false;

		for (MethodNode method : node.methods) {
			if (method.name.equals(patch.methodName) && method.desc.startsWith(oldParamsPrefix)) {
				String returnDesc = method.desc.substring(oldParamsPrefix.length());
				method.desc = "(" + patch.oldParamsDesc + insertedParamsDescOf(patch) + ")" + returnDesc;
				changed = true;
			}
		}

		for (MethodNode method : node.methods) {
			InsnList instructions = method.instructions;
			for (AbstractInsnNode insn : instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode)) continue;
				MethodInsnNode call = (MethodInsnNode) insn;
				if (!call.name.equals(patch.methodName) || !call.desc.startsWith(oldParamsPrefix)) continue;
				boolean ownerMatches = call.owner.equals(patch.targetClassInternalName) || call.owner.equals(node.name);
				if (!ownerMatches) continue;

				String returnDesc = call.desc.substring(oldParamsPrefix.length());

				InsnList pushInsns = new InsnList();
				int lastArgLocalSlot = -1;

				for (String spec : patch.argSpecs) {
					if (spec.startsWith("null:")) {
						pushInsns.add(new InsnNode(Opcodes.ACONST_NULL));
					} else if (spec.equals("false")) {
						pushInsns.add(new InsnNode(Opcodes.ICONST_0));
					} else if (spec.equals("true")) {
						pushInsns.add(new InsnNode(Opcodes.ICONST_1));
					} else if (spec.startsWith("field:")) {
						String[] fieldParts = spec.split(":");
						String fieldOwner = fieldParts[1];
						String fieldName = fieldParts[2];
						String fieldDesc = fieldParts[3];

						if (lastArgLocalSlot < 0) {
							lastArgLocalSlot = method.maxLocals;
							method.maxLocals += 1;
							pushInsns.add(new InsnNode(Opcodes.DUP));
							pushInsns.add(new VarInsnNode(Opcodes.ASTORE, lastArgLocalSlot));
						}
						pushInsns.add(new VarInsnNode(Opcodes.ALOAD, lastArgLocalSlot));
						pushInsns.add(new FieldInsnNode(Opcodes.GETFIELD, fieldOwner, fieldName, fieldDesc));
					} else {
						Log.warn(LogCategory.GAME_PATCH, "Unknown callGrowthPatch argSpec %s for %s.%s "
								+ "(from %s) — leaving this call site untouched", spec,
								patch.targetClassInternalName.replace('/', '.'), patch.methodName, name);
						pushInsns.clear();
						break;
					}
				}

				if (pushInsns.size() == 0 && !patch.argSpecs.isEmpty()) continue;

				instructions.insertBefore(call, pushInsns);
				call.desc = oldParamsPrefix.substring(0, oldParamsPrefix.length() - 1)
						+ insertedParamsDescOf(patch) + ")" + returnDesc;
				changed = true;
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Grew %s's own call site(s)/@Shadow stub for %s.%s(...) with "
				+ "Paper's new trailing parameter(s)", name, patch.targetClassInternalName.replace('/', '.'),
				patch.methodName);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static String insertedParamsDescOf(CallGrowthPatch patch) {
		StringBuilder sb = new StringBuilder();
		for (String spec : patch.argSpecs) {
			if (spec.equals("false") || spec.equals("true")) {
				sb.append("Z");
			} else if (spec.startsWith("field:")) {
				sb.append(spec.split(":")[3]);
			} else if (spec.startsWith("null:")) {
				sb.append(spec.substring("null:".length()));
			} else {
				throw new IllegalStateException("Unrecognized callGrowthPatch argSpec: " + spec);
			}
		}
		return sb.toString();
	}

	private static byte[] applyShadowFieldTypePatch(String name, byte[] bytes, ShadowFieldTypePatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		for (FieldNode field : node.fields) {
			if (field.name.equals(patch.fieldName) && field.desc.equals(patch.oldTypeDesc)) {
				field.desc = patch.newTypeDesc;
				changed = true;
			}
		}

		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof FieldInsnNode)) continue;
				FieldInsnNode fieldInsn = (FieldInsnNode) insn;
				if (fieldInsn.name.equals(patch.fieldName) && fieldInsn.desc.equals(patch.oldTypeDesc)) {
					fieldInsn.desc = patch.newTypeDesc;
					changed = true;
				}
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Widened %s's own @Shadow field %s#%s from %s to %s "
				+ "(and every reference to it in this class)", name,
				patch.targetClassInternalName.replace('/', '.'), patch.fieldName, patch.oldTypeDesc,
				patch.newTypeDesc);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyShadowFieldRedirectPatch(String name, byte[] bytes, ShadowFieldRedirectPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		FieldNode shadowField = null;
		for (FieldNode field : node.fields) {
			if (field.name.equals(patch.fieldName) && field.desc.equals(patch.fieldTypeDesc)) {
				shadowField = field;
				break;
			}
		}
		if (shadowField != null) {
			node.fields.remove(shadowField);
			changed = true;
		}

		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof FieldInsnNode)) continue;
				FieldInsnNode f = (FieldInsnNode) insn;
				if (!f.name.equals(patch.fieldName) || !f.desc.equals(patch.fieldTypeDesc)) continue;
				boolean ownerMatches = f.owner.equals(node.name) || f.owner.equals(patch.targetClassInternalName);
				if (!ownerMatches) continue;

				if (f.getOpcode() == Opcodes.GETFIELD) {
					MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC,
							patch.getterOwner, patch.getterName, patch.getterDesc, false);
					method.instructions.insert(f, call);
					method.instructions.remove(f);
					changed = true;
				} else {
					Log.warn(LogCategory.GAME_PATCH, "shadowFieldRedirectPatch: %s writes to "
							+ "redirected field %s#%s (PUTFIELD) — not supported, leaving untouched",
							name, patch.targetClassInternalName.replace('/', '.'), patch.fieldName);
				}
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Removed %s's own @Shadow field declaration %s#%s and "
				+ "redirected its reads through %s.%s(...)",
				name, patch.targetClassInternalName.replace('/', '.'), patch.fieldName,
				patch.getterOwner.replace('/', '.'), patch.getterName);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyCallbackInfoReturnablePatch(String name, byte[] bytes, CallbackInfoReturnablePatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		for (MethodNode method : node.methods) {
			if (!method.desc.endsWith(CALLBACK_INFO_DESC)) continue;
			if (patch.targetMethodName != null && !injectorTargetsMethodName(method, patch.targetMethodName)) continue;

			method.desc = method.desc.substring(0, method.desc.length() - CALLBACK_INFO_DESC.length()) + CALLBACK_INFO_RETURNABLE_DESC;
			changed = true;
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Widened %s's own @Mixin callback(s) for %s's now-non-void "
				+ "%s(...) from CallbackInfo to CallbackInfoReturnable", name,
				patch.targetClassInternalName.replace('/', '.'), patch.targetMethodName);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean injectorTargetsMethodName(MethodNode method, String expectedName) {
		List<AnnotationNode> all = new ArrayList<>();
		if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);

		boolean sawAnyMethodElement = false;

		for (AnnotationNode annotation : all) {
			if (annotation.values == null) continue;

			for (int i = 0; i < annotation.values.size(); i += 2) {
				if (!"method".equals(annotation.values.get(i))) continue;
				Object raw = annotation.values.get(i + 1);

				List<?> selectors = raw instanceof List ? (List<?>) raw : java.util.Collections.singletonList(raw);
				for (Object selectorObj : selectors) {
					if (!(selectorObj instanceof String)) continue;
					sawAnyMethodElement = true;
					String selector = (String) selectorObj;
					String bare = selector;
					int parenIdx = bare.indexOf('(');
					if (parenIdx >= 0) bare = bare.substring(0, parenIdx);
					bare = bare.replace("/^", "").replace("$/", "").replace("/", "");
					if (bare.equals(expectedName)) {
						return true;
					}
				}
			}
		}

		return !sawAnyMethodElement;
	}

	private static byte[] applyGeneralRemap(byte[] bytes) {
		if (CLASS_MAP.isEmpty() && METHOD_NAME_MAP.isEmpty() && FIELD_NAME_MAP.isEmpty()) {
			return bytes;
		}

		ClassReader reader = new ClassReader(bytes);
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

		Remapper remapper = new Remapper() {
			@Override
			public String map(String internalName) {
				return CLASS_MAP.getOrDefault(internalName, internalName);
			}

			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return METHOD_NAME_MAP.getOrDefault(owner + "." + name + descriptor, name);
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				return FIELD_NAME_MAP.getOrDefault(owner + "." + name + descriptor, name);
			}
		};

		reader.accept(new ClassRemapper(writer, remapper), 0);
		return writer.toByteArray();
	}

	private static byte[] applyIfMixinTargets(String name, byte[] bytes, SignaturePatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		for (MethodNode method : node.methods) {
			if (!method.desc.startsWith(patch.oldArgPrefix)) continue;
			if (patch.targetMethodName != null && !injectorTargetsMethodName(method, patch.targetMethodName)) continue;

			boolean hasCallbackType = false;
			for (String cbType : CALLBACK_TYPE_DESCS) {
				if (method.desc.contains(cbType)) {
					hasCallbackType = true;
					break;
				}
			}

			if (hasCallbackType) {
				retarget(method, patch);
				changed = true;
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Retargeted %s's own @Mixin callback(s) for %s "
				+ "from %s... to %s..., reconstructing the equivalent original argument at "
				+ "method entry", name, patch.targetClassInternalName.replace('/', '.'),
				patch.oldArgPrefix, patch.newArgPrefix);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyIfMixinTargetsTrailing(String name, byte[] bytes, TrailingArgsPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		for (MethodNode method : node.methods) {
			String oldPrefix = "(" + patch.oldRealArgsDesc;
			if (!method.desc.startsWith(oldPrefix)) continue;
			if (patch.targetMethodName != null && !injectorTargetsMethodName(method, patch.targetMethodName)) continue;

			String extendedSuffix = findExtendedCallbackSuffix(method.desc, oldPrefix);
			if (extendedSuffix != null) {
				retargetTrailingArgs(method, patch, extendedSuffix);
				changed = true;
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Retargeted %s's own @Mixin callback(s) for %s, "
				+ "inserting Paper's new trailing parameter(s) (%s) and shifting local variable "
				+ "slots to compensate", name, patch.targetClassInternalName.replace('/', '.'),
				patch.insertedArgsDesc);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyIfMixinTargetsLeading(String name, byte[] bytes, LeadingArgsPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		for (MethodNode method : node.methods) {
			String oldPrefix = "(" + patch.oldRealArgsDesc;
			if (!method.desc.startsWith(oldPrefix)) continue;
			if (patch.targetMethodName != null && !injectorTargetsMethodName(method, patch.targetMethodName)) continue;

			if (findExtendedCallbackSuffix(method.desc, oldPrefix) != null) {
				retargetLeadingArgs(method, patch);
				changed = true;
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Retargeted %s's own @Mixin callback(s) for %s, "
				+ "inserting Paper's new LEADING parameter(s) (%s) and shifting local variable "
				+ "slots to compensate", name, patch.targetClassInternalName.replace('/', '.'),
				patch.insertedArgsDesc);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyHelperCallPatch(String name, byte[] bytes, HelperCallPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		MethodNode oldMethod = null;
		for (MethodNode m : node.methods) {
			if (injectorTargetsMethodName(m, patch.oldMethodName) && !isDefaultTrue(m)) {
				if (hasWrapOperationAnnotation(m)) {
					oldMethod = m;
					break;
				}
			}
		}
		if (oldMethod == null) {
			return bytes;
		}

		int access = oldMethod.access;
		String methodName = oldMethod.name;
		node.methods.remove(oldMethod);

		MethodNode newMethod = new MethodNode(
				access,
				methodName,
				patch.newMethodDesc,
				null,
				null);

		if (patch.newMethodSignature != null && !patch.newMethodSignature.isEmpty() && !"-".equals(patch.newMethodSignature)) {
			newMethod.signature = patch.newMethodSignature;
		}

		InsnList body = new InsnList();
		Type[] args = Type.getArgumentTypes(patch.newMethodDesc);
		int slot = 0;
		if ((access & Opcodes.ACC_STATIC) == 0) {
			body.add(new VarInsnNode(Opcodes.ALOAD, slot++));
		}
		for (Type t : args) {
			int opcode;
			switch (t.getSort()) {
				case Type.BOOLEAN: case Type.INT: case Type.BYTE:
				case Type.CHAR: case Type.SHORT: opcode = Opcodes.ILOAD; break;
				case Type.LONG:  opcode = Opcodes.LLOAD;  break;
				case Type.FLOAT: opcode = Opcodes.FLOAD;  break;
				case Type.DOUBLE: opcode = Opcodes.DLOAD; break;
				default: opcode = Opcodes.ALOAD; break;
			}
			body.add(new VarInsnNode(opcode, slot));
			slot += t.getSize();
		}
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				patch.helperOwner, patch.helperName, patch.helperDesc, false));

		Type returnType = Type.getReturnType(patch.newMethodDesc);
		int returnOpcode;
		switch (returnType.getSort()) {
			case Type.VOID:    returnOpcode = Opcodes.RETURN;    break;
			case Type.BOOLEAN:
			case Type.INT:
			case Type.BYTE:
			case Type.CHAR:
			case Type.SHORT:   returnOpcode = Opcodes.IRETURN;   break;
			case Type.LONG:    returnOpcode = Opcodes.LRETURN;   break;
			case Type.FLOAT:   returnOpcode = Opcodes.FRETURN;   break;
			case Type.DOUBLE:  returnOpcode = Opcodes.DRETURN;   break;
			default:           returnOpcode = Opcodes.ARETURN;   break;
		}
		body.add(new InsnNode(returnOpcode));

		newMethod.instructions = body;

		AnnotationNode newAnnotation = new AnnotationNode(patch.newAnnotationDesc);
		String methodValue = patch.annotationMethodDesc != null
				? patch.oldMethodName + patch.annotationMethodDesc
				: patch.oldMethodName;
		newAnnotation.visit("method", methodValue);
		newAnnotation.visitEnd();
		newMethod.visibleAnnotations = new ArrayList<>();
		newMethod.visibleAnnotations.add(newAnnotation);

		node.methods.add(newMethod);

		Log.info(LogCategory.GAME_PATCH, "Replaced %s's own %s(...) with helper call to %s.%s "
				+ "(desc=%s, signature=%s, annotationMethod=%s)",
				name, patch.oldMethodName, patch.helperOwner.replace('/', '.'), patch.helperName,
				patch.newMethodDesc, patch.newMethodSignature, methodValue);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyOperationCallGrowthPatch(String name, byte[] bytes, OperationCallGrowthPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		boolean changed = false;

		for (MethodNode method : node.methods) {
			if (!injectorTargetsMethodName(method, patch.methodSelectorName)) continue;
			if (!hasWrapOperationAnnotation(method)) continue;

			String markerId = "opGrowth:" + patch.targetClassInternalName + "#" + patch.methodSelectorName
					+ "#" + String.join(",", patch.argSpecs);
			if (hasPatchMarker(method, markerId)) {
				continue;
			}

			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode)) continue;
				MethodInsnNode call = (MethodInsnNode) insn;
				if (call.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
				if (!"call".equals(call.name)) continue;
				if (!call.owner.contains("wrapoperation/Operation")) continue;

				AbstractInsnNode cursor = call.getPrevious();
				TypeInsnNode arrayTypeInsn = null;
				AbstractInsnNode sizeInsn = null;
				while (cursor != null) {
					if (cursor instanceof TypeInsnNode
							&& cursor.getOpcode() == Opcodes.ANEWARRAY
							&& "java/lang/Object".equals(((TypeInsnNode) cursor).desc)) {
						arrayTypeInsn = (TypeInsnNode) cursor;
						sizeInsn = cursor.getPrevious();
						break;
					}
					cursor = cursor.getPrevious();
				}

				if (arrayTypeInsn == null || sizeInsn == null) {
					Log.warn(LogCategory.GAME_PATCH, "operationCallGrowthPatch: could not locate the "
							+ "Object[] array feeding %s's Operation.call(...) — leaving untouched", name);
					continue;
				}

				int oldSize = constIntValue(sizeInsn);
				if (oldSize < 0) {
					Log.warn(LogCategory.GAME_PATCH, "operationCallGrowthPatch: array size push before "
							+ "ANEWARRAY wasn't a recognizable int constant in %s — leaving untouched", name);
					continue;
				}
				int newSize = oldSize + patch.argSpecs.size();

				method.instructions.set(sizeInsn, constIntInsn(newSize));

				InsnList extra = new InsnList();
				int idx = oldSize;
				boolean specOk = true;
				for (String spec : patch.argSpecs) {
					extra.add(new InsnNode(Opcodes.DUP));
					extra.add(constIntInsn(idx));
					InsnList pushed = pushArgSpecInsnList(spec);
					if (pushed == null) {
						Log.warn(LogCategory.GAME_PATCH, "operationCallGrowthPatch: unknown argSpec "
								+ "\"%s\" for %s — leaving untouched", spec, name);
						specOk = false;
						break;
					}
					extra.add(pushed);
					extra.add(new InsnNode(Opcodes.AASTORE));
					idx++;
				}
				if (!specOk) {
					method.instructions.set(sizeInsn, constIntInsn(oldSize));
					continue;
				}

				method.instructions.insertBefore(call, extra);
				changed = true;
				addPatchMarker(method, markerId);
			}
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Grew %s's own Operation.call(...) invocation inside its "
				+ "@WrapOperation callback for %s with %d new trailing argument(s), to match Paper's "
				+ "grown real target method", name, patch.methodSelectorName, patch.argSpecs.size());

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static int constIntValue(AbstractInsnNode insn) {
		if (insn instanceof InsnNode) {
			switch (insn.getOpcode()) {
				case Opcodes.ICONST_0: return 0;
				case Opcodes.ICONST_1: return 1;
				case Opcodes.ICONST_2: return 2;
				case Opcodes.ICONST_3: return 3;
				case Opcodes.ICONST_4: return 4;
				case Opcodes.ICONST_5: return 5;
				default: return -1;
			}
		}
		if (insn instanceof IntInsnNode) {
			return ((IntInsnNode) insn).operand;
		}
		if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Integer) {
			return (Integer) ((LdcInsnNode) insn).cst;
		}
		return -1;
	}

	private static AbstractInsnNode constIntInsn(int value) {
		switch (value) {
			case 0: return new InsnNode(Opcodes.ICONST_0);
			case 1: return new InsnNode(Opcodes.ICONST_1);
			case 2: return new InsnNode(Opcodes.ICONST_2);
			case 3: return new InsnNode(Opcodes.ICONST_3);
			case 4: return new InsnNode(Opcodes.ICONST_4);
			case 5: return new InsnNode(Opcodes.ICONST_5);
			default:
				if (value >= -128 && value <= 127) return new IntInsnNode(Opcodes.BIPUSH, value);
				if (value >= -32768 && value <= 32767) return new IntInsnNode(Opcodes.SIPUSH, value);
				return new LdcInsnNode(value);
		}
	}

	private static final String PATCH_MARKER_ANNOTATION_DESC =
			"Lnet/fabricmc/loader/impl/transformer/PaperModSignatureCompat$AlreadyPatched;";

	private static boolean hasPatchMarker(MethodNode method, String markerId) {
		if (method.invisibleAnnotations == null) return false;
		for (AnnotationNode a : method.invisibleAnnotations) {
			if (!PATCH_MARKER_ANNOTATION_DESC.equals(a.desc) || a.values == null) continue;
			for (int i = 0; i < a.values.size(); i += 2) {
				if ("value".equals(a.values.get(i)) && markerId.equals(a.values.get(i + 1))) {
					return true;
				}
			}
		}
		return false;
	}

	private static void addPatchMarker(MethodNode method, String markerId) {
		AnnotationNode marker = new AnnotationNode(PATCH_MARKER_ANNOTATION_DESC);
		marker.visit("value", markerId);
		marker.visitEnd();
		if (method.invisibleAnnotations == null) method.invisibleAnnotations = new ArrayList<>();
		method.invisibleAnnotations.add(marker);
	}

	private static InsnList pushArgSpecInsnList(String spec) {
		InsnList il = new InsnList();
		if (spec.equals("true")) {
			il.add(new InsnNode(Opcodes.ICONST_1));
			il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
			return il;
		}
		if (spec.equals("false")) {
			il.add(new InsnNode(Opcodes.ICONST_0));
			il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
			return il;
		}
		if (spec.startsWith("null:")) {
			il.add(new InsnNode(Opcodes.ACONST_NULL));
			return il;
		}
		if (spec.startsWith("static:")) {
			String[] p = spec.split(":");
			String owner = p[1];
			String fname = p[2];
			String fdesc = p[3];
			il.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, fname, fdesc));
			return il;
		}
		if (spec.startsWith("param:")) {
			String[] p = spec.split(":");
			int slot = Integer.parseInt(p[1]);
			Type t = Type.getType(p[2]);
			switch (t.getSort()) {
				case Type.BOOLEAN:
					il.add(new VarInsnNode(Opcodes.ILOAD, slot));
					il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
					break;
				case Type.INT:
					il.add(new VarInsnNode(Opcodes.ILOAD, slot));
					il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
					break;
				case Type.LONG:
					il.add(new VarInsnNode(Opcodes.LLOAD, slot));
					il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
					break;
				case Type.DOUBLE:
					il.add(new VarInsnNode(Opcodes.DLOAD, slot));
					il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
					break;
				case Type.FLOAT:
					il.add(new VarInsnNode(Opcodes.FLOAD, slot));
					il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
					break;
				default:
					il.add(new VarInsnNode(Opcodes.ALOAD, slot));
					break;
			}
			return il;
		}
		return null;
	}

	private static boolean hasAnnotationDescContaining(MethodNode method, String substring) {
		if (substring == null) return true;
		List<AnnotationNode> all = new ArrayList<>();
		if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
		for (AnnotationNode a : all) {
			if (a.desc != null && a.desc.contains(substring)) return true;
		}
		return false;
	}

	private static boolean hasWrapOperationAnnotation(MethodNode method) {
		List<AnnotationNode> all = new ArrayList<>();
		if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
		for (AnnotationNode a : all) {
			if (a.desc != null && a.desc.contains("WrapOperation")) return true;
		}
		return false;
	}

	private static boolean hasAtTargetContaining(MethodNode method, String substring) {
		if (substring == null) return true;
		List<AnnotationNode> all = new ArrayList<>();
		if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
		for (AnnotationNode a : all) {
			if (a.values == null) continue;
			for (int i = 0; i < a.values.size(); i += 2) {
				if (!"at".equals(a.values.get(i))) continue;
				Object raw = a.values.get(i + 1);
				List<?> atNodes = raw instanceof List ? (List<?>) raw : java.util.Collections.singletonList(raw);
				for (Object atObj : atNodes) {
					if (!(atObj instanceof AnnotationNode)) continue;
					AnnotationNode atNode = (AnnotationNode) atObj;
					if (atNode.values == null) continue;
					for (int j = 0; j < atNode.values.size(); j += 2) {
						if (!"target".equals(atNode.values.get(j))) continue;
						Object t = atNode.values.get(j + 1);
						if (t instanceof String && ((String) t).contains(substring)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static void retargetLeadingArgs(MethodNode method, LeadingArgsPatch patch) {
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;

		Type[] insertedArgs = Type.getArgumentTypes("(" + patch.insertedArgsDesc + ")V");
		int shiftStart = isStatic ? 0 : 1;
		int addedSlots = slotSize(insertedArgs);

		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof VarInsnNode) {
				VarInsnNode varInsn = (VarInsnNode) insn;
				if (varInsn.var >= shiftStart) {
					varInsn.var += addedSlots;
				}
			} else if (insn instanceof IincInsnNode) {
				IincInsnNode iinc = (IincInsnNode) insn;
				if (iinc.var >= shiftStart) {
					iinc.var += addedSlots;
				}
			}
		}

		if (method.localVariables != null) {
			method.localVariables.clear();
		}

		Type[] oldFullArgs = Type.getArgumentTypes(method.desc);
		int insertCount = insertedArgs.length;
		method.visibleParameterAnnotations = insertNullSlotsInParamAnnotations(
				method.visibleParameterAnnotations, oldFullArgs.length, 0, insertCount);
		method.invisibleParameterAnnotations = insertNullSlotsInParamAnnotations(
				method.invisibleParameterAnnotations, oldFullArgs.length, 0, insertCount);

		method.desc = "(" + patch.insertedArgsDesc + method.desc.substring(1);
	}

	private static void retargetTrailingArgs(MethodNode method, TrailingArgsPatch patch, String suffix) {
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;

		Type[] oldRealArgs = Type.getArgumentTypes("(" + patch.oldRealArgsDesc + ")V");
		Type[] insertedArgs = Type.getArgumentTypes("(" + patch.insertedArgsDesc + ")V");

		int oldSlotsBeforeCallback = (isStatic ? 0 : 1) + slotSize(oldRealArgs);
		int addedSlots = slotSize(insertedArgs);

		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof VarInsnNode) {
				VarInsnNode varInsn = (VarInsnNode) insn;
				if (varInsn.var >= oldSlotsBeforeCallback) {
					varInsn.var += addedSlots;
				}
			} else if (insn instanceof IincInsnNode) {
				IincInsnNode iinc = (IincInsnNode) insn;
				if (iinc.var >= oldSlotsBeforeCallback) {
					iinc.var += addedSlots;
				}
			}
		}

		if (method.localVariables != null) {
			method.localVariables.clear();
		}

		Type[] oldFullArgs = Type.getArgumentTypes(method.desc);
		int paramIndex = oldRealArgs.length;
		int insertCount = insertedArgs.length;
		method.visibleParameterAnnotations = insertNullSlotsInParamAnnotations(
				method.visibleParameterAnnotations, oldFullArgs.length, paramIndex, insertCount);
		method.invisibleParameterAnnotations = insertNullSlotsInParamAnnotations(
				method.invisibleParameterAnnotations, oldFullArgs.length, paramIndex, insertCount);

		method.desc = "(" + patch.oldRealArgsDesc + patch.insertedArgsDesc + suffix;
		method.signature = null;
	}

	private static int slotSize(Type[] types) {
		int size = 0;
		for (Type t : types) {
			size += t.getSize();
		}
		return size;
	}

	private static boolean mightReferenceClass(byte[] bytes, String targetClassInternalName) {
		byte[] needle = targetClassInternalName.getBytes(StandardCharsets.UTF_8);

		outer:
		for (int i = 0; i <= bytes.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (bytes[i + j] != needle[j]) continue outer;
			}
			return true;
		}

		return false;
	}

	private static boolean targetsClass(ClassNode node, String targetClassInternalName) {
		boolean found = targetsClass(node.visibleAnnotations, targetClassInternalName)
				|| targetsClass(node.invisibleAnnotations, targetClassInternalName);
		return found;
	}

	private static boolean targetsClass(List<AnnotationNode> annotations, String targetClassInternalName) {
		if (annotations == null) return false;
		String targetClassDotName = targetClassInternalName.replace('/', '.');

		for (AnnotationNode annotation : annotations) {
			if (!MIXIN_ANNOTATION_DESC.equals(annotation.desc) || annotation.values == null) continue;

			for (int i = 0; i < annotation.values.size(); i += 2) {
				Object key = annotation.values.get(i);
				Object rawTargets = annotation.values.get(i + 1);
				if (!(rawTargets instanceof List)) continue;

				if ("value".equals(key)) {
					for (Object rawTarget : (List<?>) rawTargets) {
						if (rawTarget instanceof Type && targetClassInternalName.equals(((Type) rawTarget).getInternalName())) {
							return true;
						}
					}
				} else if ("targets".equals(key)) {
					for (Object rawTarget : (List<?>) rawTargets) {
						if (rawTarget instanceof String) {
							String targetStr = ((String) rawTarget).replace('/', '.');
							if (targetClassDotName.equals(targetStr)) {
								return true;
							}
						}
					}
				}
			}
		}

		return false;
	}

	private static void retarget(MethodNode method, SignaturePatch patch) {
		method.desc = patch.newArgPrefix + method.desc.substring(patch.oldArgPrefix.length());

		InsnList prefix = new InsnList();
		prefix.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				patch.reconstructorOwner, patch.reconstructorName, patch.reconstructorDesc, false));
		prefix.add(new VarInsnNode(Opcodes.ASTORE, 0));
		method.instructions.insert(prefix);
	}

	private static byte[] applyMethodBodyPatch(String name, byte[] bytes, MethodBodyPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		MethodNode oldMethod = null;
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) && !"<clinit>".equals(m.name)
					&& m.name.equals(patch.oldMethodName)) {
				oldMethod = m;
				break;
			}
			if (injectorTargetsMethodName(m, patch.oldMethodName) && !isDefaultTrue(m)
					&& hasAnnotationDescContaining(m, patch.requiredOldAnnotationDesc)) {
				oldMethod = m;
				break;
			}
		}
		
		if (oldMethod == null) {
			return bytes;
		}

		LABELS.clear();

		InsnList newCode = new InsnList();
		for (String line : patch.instructions) {
			if (line.startsWith("LOAD_LOCAL:")) {
				String slotStr = line.substring("LOAD_LOCAL:".length()).trim();
				int colonIdx = slotStr.indexOf(':');
				if (colonIdx >= 0) slotStr = slotStr.substring(0, colonIdx);
				try {
					int slot = Integer.parseInt(slotStr);
					newCode.add(new VarInsnNode(Opcodes.ALOAD, slot));
					continue;
				} catch (NumberFormatException e) {
					Log.warn(LogCategory.GAME_PATCH, "methodBodyPatch: invalid LOAD_LOCAL slot "
							+ "\"%s\" for %s — aborting", line, name);
					return bytes;
				}
			}

			AbstractInsnNode insn = parseInstruction(line);
			if (insn == null) {
				Log.warn(LogCategory.GAME_PATCH, "methodBodyPatch: could not parse instruction "
						+ "\"%s\" for %s — aborting this patch, leaving method untouched", line, name);
				return bytes;
			}
			newCode.add(insn);
		}

		int access = oldMethod.access;
		String originalJavaName = oldMethod.name;
		node.methods.remove(oldMethod);

		MethodNode newMethod = new MethodNode(access, originalJavaName, patch.newDesc, null, null);
		newMethod.instructions = newCode;

		String methodValue = patch.annotationMethodDesc != null
				? patch.annotationMethodDesc
				: patch.newMethodName;
		AnnotationNode newAnnotation = new AnnotationNode(patch.newAnnotationDesc);
		newAnnotation.visit("method", methodValue);
		if (patch.newAtValue != null) {
			AnnotationVisitor atVisitor = newAnnotation.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
			atVisitor.visit("value", patch.newAtValue);
			atVisitor.visitEnd();
		}
		if (patch.newDesc != null && patch.newDesc.contains("CallbackInfoReturnable")) {
			newAnnotation.visit("cancellable", true);
		}
		newAnnotation.visitEnd();
		newMethod.visibleAnnotations = new ArrayList<>();
		newMethod.visibleAnnotations.add(newAnnotation);

		node.methods.add(newMethod);

		Log.info(LogCategory.GAME_PATCH, "Replaced %s's own %s(...) entirely with a hand-specified "
				+ "%d-instruction body targeting %s's %s(...)", name, originalJavaName,
				patch.instructions.size(), patch.targetClassInternalName.replace('/', '.'), methodValue);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyWrapMethodPatch(String name, byte[] bytes, WrapMethodPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		MethodNode oldMethod = null;
		for (MethodNode m : node.methods) {
			if (injectorTargetsMethodName(m, patch.oldMethodName) && !isDefaultTrue(m)) {
				oldMethod = m;
				break;
			}
		}
		if (oldMethod == null) {
			return bytes;
		}

		InsnList newCode = new InsnList();
		for (String line : patch.instructions) {
			AbstractInsnNode insn = parseInstruction(line);
			if (insn == null) {
				Log.warn(LogCategory.GAME_PATCH, "wrapMethodPatch: could not parse instruction "
						+ "\"%s\" for %s — aborting this patch", line, name);
				return bytes;
			}
			newCode.add(insn);
		}

		int access = oldMethod.access;
		node.methods.remove(oldMethod);

		MethodNode newMethod = new MethodNode(access, patch.newMethodName, patch.newMethodDesc, null, null);
		newMethod.instructions = newCode;

		AnnotationNode newAnnotation = new AnnotationNode("Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
		newAnnotation.visit("method", patch.newMethodName + patch.annotationMethodDesc);
		newAnnotation.visitEnd();
		newMethod.visibleAnnotations = new ArrayList<>();
		newMethod.visibleAnnotations.add(newAnnotation);

		node.methods.add(newMethod);

		Log.info(LogCategory.GAME_PATCH, "Replaced %s's own %s(...) entirely with @WrapMethod + "
				+ "%d-instruction body", name, patch.oldMethodName, patch.instructions.size());

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyRedirectTrailingArgsPatch(String name, byte[] bytes, RedirectTrailingArgsPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		String oldDesc = "(" + patch.oldRealArgsDesc + ")" + patch.oldReturnDesc;
		boolean changed = false;

		for (MethodNode method : node.methods) {
			if (!method.desc.equals(oldDesc)) continue;
			if (isDefaultTrue(method)) continue;
			if (patch.targetMethodName != null && !injectorTargetsMethodName(method, patch.targetMethodName)) continue;

			boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
			Type[] oldRealArgs = Type.getArgumentTypes("(" + patch.oldRealArgsDesc + ")V");
			int newParamSlot = (isStatic ? 0 : 1) + slotSize(oldRealArgs);

			InsnList prefix = new InsnList();
			prefix.add(new VarInsnNode(Opcodes.ALOAD, newParamSlot));
			prefix.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					patch.invokeCallbackOwner, patch.invokeCallbackName, patch.invokeCallbackDesc, true));
			method.instructions.insert(prefix);

			method.desc = "(" + patch.oldRealArgsDesc + patch.insertedArgsDesc + ")" + patch.oldReturnDesc;
			changed = true;
		}

		if (!changed) {
			return bytes;
		}

		Log.info(LogCategory.GAME_PATCH, "Grew %s's own @Redirect handler for %s, appending Paper's "
				+ "new trailing parameter (%s) and invoking it to preserve vanilla behavior",
				name, patch.targetClassInternalName.replace('/', '.'), patch.insertedArgsDesc);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applyInjectorRewrite(String name, byte[] bytes, InjectorRewritePatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		MethodNode oldMethod = null;
		for (MethodNode m : node.methods) {
			if (injectorTargetsMethodName(m, patch.oldSelector) && !isDefaultTrue(m)
					&& hasAnnotationDescContaining(m, patch.requiredOldAnnotationDesc)
					&& hasAtTargetContaining(m, patch.requiredOldAtTargetContains)) {
				oldMethod = m;
				break;
			}
		}
		if (oldMethod == null) {
			return bytes;
		}

		int access = oldMethod.access;
		String originalJavaName = oldMethod.name;
		node.methods.remove(oldMethod);

		MethodNode newMethod = new MethodNode(access, originalJavaName, patch.newDesc, null, null);
		boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
		Type[] argTypes = Type.getArgumentTypes(patch.newDesc);
		Type returnType = Type.getReturnType(patch.newDesc);

		InsnList code = new InsnList();

		code.add(new LdcInsnNode(argTypes.length));
		code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < argTypes.length; i++) {
			Type t = argTypes[i];
			code.add(new InsnNode(Opcodes.DUP));
			code.add(new LdcInsnNode(i));
			code.add(loadAndBox(t, slot));
			code.add(new InsnNode(Opcodes.AASTORE));
			slot += t.getSize();
		}

		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, patch.helperOwner, patch.helperName, patch.helperDesc, false));

		if (returnType.getSort() == Type.VOID) {
			code.add(new InsnNode(Opcodes.POP));
			code.add(new InsnNode(Opcodes.RETURN));
		} else {
			code.add(unboxAndReturn(returnType));
		}

		newMethod.instructions = code;

		AnnotationNode ann = new AnnotationNode(patch.newAnnotationDesc);
		ann.visit("method", patch.newSelector);
		if (patch.newAtValue != null) {
			AnnotationVisitor atVisitor = ann.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
			atVisitor.visit("value", patch.newAtValue);
			if (patch.newAtTarget != null) {
				atVisitor.visit("target", patch.newAtTarget);
			}
			atVisitor.visitEnd();
		}
		ann.visitEnd();
		newMethod.visibleAnnotations = new ArrayList<>();
		newMethod.visibleAnnotations.add(ann);

		node.methods.add(newMethod);

		Log.info(LogCategory.GAME_PATCH, "Rewrote %s's own %s(...) as a %s calling helper %s.%s(...)",
				name, originalJavaName, patch.newAnnotationDesc, patch.helperOwner.replace('/', '.'), patch.helperName);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] applySplitWrapOperationPatch(String name, byte[] bytes, SplitWrapOperationPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		String markerId = "splitWrapOperation:" + patch.targetClassInternalName + "#" + patch.oldMethodName;

		// Đã split ở lần chạy fixIfNeeded trước (pipeline chạy 2 pha trên cùng bytecode) — bỏ qua để
		// tránh split lần 2, vốn sẽ tạo ra 2 method trùng hệt tên+descriptor cho cùng 1 biến thể
		// (MixinApplicatorStandard từ chối merge với lỗi "@Overwrite is required by the parent
		// configuration" — đúng lỗi gặp phải với CustomPayloadStreamCodecMixin).
		for (MethodNode m : node.methods) {
			if (hasPatchMarker(m, markerId)) {
				return bytes;
			}
		}

		MethodNode oldMethod = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(patch.oldMethodName) && !"<init>".equals(m.name) && !"<clinit>".equals(m.name)) {
				oldMethod = m;
				break;
			}
		}
		if (oldMethod == null) {
			return bytes;
		}

		int access = oldMethod.access;
		node.methods.remove(oldMethod);

		for (SplitWrapOperationPatch.Variant variant : patch.variants) {
			MethodNode newMethod = new MethodNode(access, patch.oldMethodName, variant.newDesc, null, null);

			Type[] argTypes = Type.getArgumentTypes(variant.newDesc);
			Type returnType = Type.getReturnType(variant.newDesc);
			boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

			InsnList code = new InsnList();
			code.add(new LdcInsnNode(argTypes.length));
			code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));

			int slot = isStatic ? 0 : 1;
			for (int i = 0; i < argTypes.length; i++) {
				Type t = argTypes[i];
				code.add(new InsnNode(Opcodes.DUP));
				code.add(new LdcInsnNode(i));
				code.add(loadAndBox(t, slot));
				code.add(new InsnNode(Opcodes.AASTORE));
				slot += t.getSize();
			}

			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, variant.helperOwner, variant.helperName, variant.helperDesc, false));

			if (returnType.getSort() == Type.VOID) {
				code.add(new InsnNode(Opcodes.POP));
				code.add(new InsnNode(Opcodes.RETURN));
			} else {
				code.add(unboxAndReturn(returnType));
			}

			newMethod.instructions = code;

			// @WrapOperation luôn coi tham số đầu tiên là "instance" (this) của method thật đang bị wrap.
			// Method gốc khai instance dưới dạng kiểu interface StreamCodec kèm @Coerce — báo cho Mixin
			// chấp nhận giá trị thật là CustomPacketPayload$1 (lớp ẩn danh implement StreamCodec) thay vì
			// đòi đúng tên class CustomPacketPayload$1 làm kiểu tham số. Vì method mới ở đây được dựng lại
			// HOÀN TOÀN TỪ ĐẦU (không kế thừa annotation cũ như insertParamPatch), @Coerce bị mất nếu không
			// thêm lại thủ công — thiếu nó Mixin validate cứng và báo "expected CustomPacketPayload$1,
			// found StreamCodec" tại index 0.
			@SuppressWarnings("unchecked")
			List<AnnotationNode>[] invisParamAnns = new List[argTypes.length];
			AnnotationNode coerce = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Coerce;");
			coerce.visitEnd();
			invisParamAnns[0] = new ArrayList<>();
			invisParamAnns[0].add(coerce);
			newMethod.invisibleParameterAnnotations = invisParamAnns;

			AnnotationNode ann = new AnnotationNode(patch.newAnnotationDesc);
			ann.visit("method", variant.newSelector);
			if (patch.atValue != null) {
				AnnotationVisitor atVisitor = ann.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
				atVisitor.visit("value", patch.atValue);
				if (patch.atTarget != null) {
					atVisitor.visit("target", patch.atTarget);
				}
				atVisitor.visitEnd();
			}
			ann.visitEnd();
			newMethod.visibleAnnotations = new ArrayList<>();
			newMethod.visibleAnnotations.add(ann);

			addPatchMarker(newMethod, markerId);

			node.methods.add(newMethod);
		}

		Log.info(LogCategory.GAME_PATCH, "Split %s's own shared %s(...) handler into %d separate "
				+ "per-target copies (each calling its own reflective helper), since the targets' "
				+ "local-capture shapes are no longer mutually compatible with one shared handler",
				name, patch.oldMethodName, patch.variants.size());

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Áp dụng {@link AsmMethodPatch} — thay thế TOÀN BỘ 1 method bằng dữ liệu khai HOÀN TOÀN trong
	 * mappings.tiny (annotation + descriptor + thân method + parameter annotations), không cần
	 * thêm bất kỳ hàm Java riêng nào.
	 */
	private static byte[] applyAsmMethodPatch(String name, byte[] bytes, AsmMethodPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		MethodNode oldMethod = null;
		for (MethodNode m : node.methods) {
			if (injectorTargetsMethodName(m, patch.oldSelector) && !isDefaultTrue(m)
					&& hasAnnotationDescContaining(m, patch.requiredOldAnnotationDesc)) {
				oldMethod = m;
				break;
			}
		}
		if (oldMethod == null) {
			return bytes;
		}

		InsnList newCode = new InsnList();
		for (String line : patch.instructions) {
			AbstractInsnNode insn = parseInstruction(line);
			if (insn == null) {
				Log.warn(LogCategory.GAME_PATCH, "asmMethodPatch: could not parse instruction \"%s\" "
						+ "for %s — aborting this patch, leaving method untouched", line, name);
				return bytes;
			}
			newCode.add(insn);
		}

		int access = oldMethod.access;
		String originalJavaName = oldMethod.name;
		String javaName = patch.newJavaName != null ? patch.newJavaName : originalJavaName;
		node.methods.remove(oldMethod);

		MethodNode newMethod = new MethodNode(access, javaName, patch.newDesc, null, null);
		newMethod.instructions = newCode;

		AnnotationNode ann = new AnnotationNode(patch.newAnnotationDesc);
		applyElementsToAnnotation(ann, patch.elements);
		if (!patch.atElements.isEmpty()) {
			AnnotationVisitor atVisitor = ann.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
			applyElementsToAnnotation(atVisitor, patch.atElements);
			atVisitor.visitEnd();
		}
		ann.visitEnd();
		newMethod.visibleAnnotations = new ArrayList<>();
		newMethod.visibleAnnotations.add(ann);

		// Mỗi dòng PARAM_ELEM: <paramIndex> <annotationDesc> <key> <value>
		// Nhóm theo paramIndex, tạo AnnotationNode cho mỗi annotation, gom vào mảng theo index.
		Type[] newArgTypes = Type.getArgumentTypes(patch.newDesc);
		@SuppressWarnings("unchecked")
		List<AnnotationNode>[] invisParamAnns = new List[newArgTypes.length];

		for (String[] pa : patch.paramAnnotations) {
			if (pa.length != 4) continue;
			int idx;
			try {
				idx = Integer.parseInt(pa[0]);
			} catch (NumberFormatException e) {
				Log.warn(LogCategory.GAME_PATCH, "asmMethodPatch: PARAM_ELEM invalid index \"%s\" "
						+ "for %s — skipping this PARAM_ELEM", pa[0], name);
				continue;
			}
			if (idx < 0 || idx >= newArgTypes.length) {
				Log.warn(LogCategory.GAME_PATCH, "asmMethodPatch: PARAM_ELEM index %d out of range "
						+ "(method has %d param(s)) for %s — skipping this PARAM_ELEM",
						idx, newArgTypes.length, name);
				continue;
			}

			String annDesc = pa[1];
			String key = pa[2];
			String val = pa[3];

			AnnotationNode pann = new AnnotationNode(annDesc);
			pann.visit(key, decodeAnnotationElementValue(val));
			pann.visitEnd();

			if (invisParamAnns[idx] == null) invisParamAnns[idx] = new ArrayList<>();
			invisParamAnns[idx].add(pann);
		}
		newMethod.invisibleParameterAnnotations = invisParamAnns;

		node.methods.add(newMethod);

		Log.info(LogCategory.GAME_PATCH, "Rewrote %s's own %s(...) entirely via asmMethodPatch — new "
				+ "%s annotation on %s(...) [%s] with %d PARAM_ELEM(s)",
				name, originalJavaName, patch.newAnnotationDesc,
				javaName, patch.newDesc, patch.paramAnnotations.size());

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static void applyElementsToAnnotation(AnnotationVisitor visitor, List<String[]> kvPairs) {
		LinkedHashMap<String, List<String>> grouped = new LinkedHashMap<>();
		for (String[] kv : kvPairs) {
			grouped.computeIfAbsent(kv[0], k -> new ArrayList<>()).add(kv[1]);
		}
		for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
			List<String> values = e.getValue();
			if (values.size() == 1) {
				visitor.visit(e.getKey(), decodeAnnotationElementValue(values.get(0)));
			} else {
				List<Object> list = new ArrayList<>();
				for (String v : values) {
					list.add(decodeAnnotationElementValue(v));
				}
				visitor.visit(e.getKey(), list);
			}
		}
	}

	private static byte[] applyBridgeFieldPatch(String name, byte[] bytes, BridgeFieldPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		if ((node.access & Opcodes.ACC_INTERFACE) != 0) {
			return bytes;
		}

		for (FieldNode f : node.fields) {
			if (f.name.equals(patch.fieldName) && f.desc.equals(patch.vanillaTypeDesc)) {
				return bytes;
			}
		}

		FieldNode newField = new FieldNode(
				Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				patch.fieldName,
				patch.vanillaTypeDesc,
				null,
				null);
		node.fields.add(newField);

		Log.info(LogCategory.GAME_PATCH, "Added bridge field %s#%s %s -> %s",
				name, patch.fieldName, patch.vanillaTypeDesc, patch.actualTypeDesc);

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static Object decodeAnnotationElementValue(String raw) {
		if ("true".equals(raw)) return Boolean.TRUE;
		if ("false".equals(raw)) return Boolean.FALSE;
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException ignored) {
			return raw;
		}
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] insertNullSlotsInParamAnnotations(
			List<AnnotationNode>[] paramAnnotations, int oldArgCount, int paramIndex, int count) {
		if (paramAnnotations == null || count <= 0) {
			return paramAnnotations;
		}
		if (paramAnnotations.length != oldArgCount) {
			Log.warn(LogCategory.GAME_PATCH, "parameter annotations array length (%d) doesn't match "
					+ "old arg count (%d) — leaving parameter annotations untouched, sugar annotations "
					+ "(@Local/@Share) may end up misaligned", paramAnnotations.length, oldArgCount);
			return paramAnnotations;
		}
		List<AnnotationNode>[] result = new List[oldArgCount + count];
		System.arraycopy(paramAnnotations, 0, result, 0, paramIndex);
		for (int i = 0; i < count; i++) {
			result[paramIndex + i] = null;
		}
		System.arraycopy(paramAnnotations, paramIndex, result, paramIndex + count, oldArgCount - paramIndex);
		return result;
	}

	private static List<AnnotationNode>[] insertNullSlotInParamAnnotations(
			List<AnnotationNode>[] paramAnnotations, int oldArgCount, int paramIndex) {
		return insertNullSlotsInParamAnnotations(paramAnnotations, oldArgCount, paramIndex, 1);
	}

	private static byte[] applyInsertParamPatch(String name, byte[] bytes, InsertParamPatch patch) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		if (!targetsClass(node, patch.targetClassInternalName)) {
			return bytes;
		}

		MethodNode target = null;
		for (MethodNode m : node.methods) {
			if (injectorTargetsMethodName(m, patch.oldSelector) && !isDefaultTrue(m)
					&& hasAnnotationDescContaining(m, patch.requiredOldAnnotationDesc)) {
				target = m;
				break;
			}
		}
		if (target == null) {
			return bytes;
		}

		String markerId = "insertParam:" + patch.targetClassInternalName + "#" + patch.oldSelector
				+ "#" + patch.paramIndex + "#" + patch.insertedTypeDesc;
		if (hasPatchMarker(target, markerId)) {
			return bytes;
		}

		boolean isStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		Type[] oldArgs = Type.getArgumentTypes(target.desc);
		if (patch.paramIndex < 0 || patch.paramIndex > oldArgs.length) {
			Log.warn(LogCategory.GAME_PATCH, "insertParamPatch: paramIndex %d out of range for %s "
					+ "(has %d param(s)) — leaving untouched", patch.paramIndex, target.name, oldArgs.length);
			return bytes;
		}

		Type insertedType = Type.getType(patch.insertedTypeDesc);
		int insertSlot = isStatic ? 0 : 1;
		for (int i = 0; i < patch.paramIndex; i++) {
			insertSlot += oldArgs[i].getSize();
		}
		int addedSize = insertedType.getSize();

		for (AbstractInsnNode insn : target.instructions.toArray()) {
			if (insn instanceof VarInsnNode) {
				VarInsnNode v = (VarInsnNode) insn;
				if (v.var >= insertSlot) v.var += addedSize;
			} else if (insn instanceof IincInsnNode) {
				IincInsnNode ii = (IincInsnNode) insn;
				if (ii.var >= insertSlot) ii.var += addedSize;
			}
		}
		if (target.localVariables != null) {
			target.localVariables.clear();
		}

		target.visibleParameterAnnotations = insertNullSlotInParamAnnotations(
				target.visibleParameterAnnotations, oldArgs.length, patch.paramIndex);
		target.invisibleParameterAnnotations = insertNullSlotInParamAnnotations(
				target.invisibleParameterAnnotations, oldArgs.length, patch.paramIndex);

		Type[] newArgs = new Type[oldArgs.length + 1];
		System.arraycopy(oldArgs, 0, newArgs, 0, patch.paramIndex);
		newArgs[patch.paramIndex] = insertedType;
		System.arraycopy(oldArgs, patch.paramIndex, newArgs, patch.paramIndex + 1,
				oldArgs.length - patch.paramIndex);

		StringBuilder sb = new StringBuilder("(");
		for (Type t : newArgs) sb.append(t.getDescriptor());
		sb.append(")").append(Type.getReturnType(target.desc).getDescriptor());
		target.desc = sb.toString();
		addPatchMarker(target, markerId);

		if (patch.newSelector != null) {
			List<AnnotationNode> allAnns = new ArrayList<>();
			if (target.visibleAnnotations != null) allAnns.addAll(target.visibleAnnotations);
			if (target.invisibleAnnotations != null) allAnns.addAll(target.invisibleAnnotations);
			for (AnnotationNode a : allAnns) {
				if (a.values == null) continue;
				for (int i = 0; i < a.values.size(); i += 2) {
					if (!"method".equals(a.values.get(i))) continue;
					a.values.set(i + 1, patch.newSelector);
				}
			}
		}

		Log.info(LogCategory.GAME_PATCH, "Inserted a new %s parameter at index %d into %s's own %s(...) "
				+ "descriptor (shifting subsequent local variable slots by %d and re-aligning parameter "
				+ "annotations)%s, to satisfy injector arity validation against Paper's grown real target method",
				patch.insertedTypeDesc, patch.paramIndex, name, target.name, addedSize,
				patch.newSelector != null ? (" and retargeting method= to \"" + patch.newSelector + "\"") : "");

		ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static InsnList loadAndBox(Type t, int slot) {
		InsnList il = new InsnList();
		switch (t.getSort()) {
			case Type.BOOLEAN: il.add(new VarInsnNode(Opcodes.ILOAD, slot));
				il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)); break;
			case Type.INT:     il.add(new VarInsnNode(Opcodes.ILOAD, slot));
				il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)); break;
			case Type.LONG:    il.add(new VarInsnNode(Opcodes.LLOAD, slot));
				il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)); break;
			case Type.DOUBLE:  il.add(new VarInsnNode(Opcodes.DLOAD, slot));
				il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)); break;
			case Type.FLOAT:   il.add(new VarInsnNode(Opcodes.FLOAD, slot));
				il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)); break;
			default:           il.add(new VarInsnNode(Opcodes.ALOAD, slot)); break;
		}
		return il;
	}

	private static InsnList unboxAndReturn(Type returnType) {
		InsnList il = new InsnList();
		switch (returnType.getSort()) {
			case Type.BOOLEAN: il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
				il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
				il.add(new InsnNode(Opcodes.IRETURN)); break;
			case Type.INT:      il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
				il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
				il.add(new InsnNode(Opcodes.IRETURN)); break;
			default:            il.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
				il.add(new InsnNode(Opcodes.ARETURN)); break;
		}
		return il;
	}

	private static boolean isDefaultTrue(MethodNode method) {
		List<AnnotationNode> all = new ArrayList<>();
		if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
		if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
		for (AnnotationNode a : all) {
			if (a.values == null) continue;
			for (int i = 0; i < a.values.size(); i += 2) {
				if ("method".equals(a.values.get(i))) return false;
			}
		}
		return true;
	}

	private static AbstractInsnNode parseInstruction(String line) {
		String[] parts = line.split(" ", 2);
		String op = parts[0].trim();
		String operand = parts.length > 1 ? parts[1].trim() : null;

		switch (op) {
			case "GETSTATIC": case "PUTSTATIC": case "GETFIELD": case "PUTFIELD": {
				if (operand == null) return null;
				int dot = operand.lastIndexOf('.');
				int colon = operand.lastIndexOf(':');
				if (dot < 0 || colon < 0 || colon < dot) {
					Log.warn(LogCategory.GAME_PATCH, "parseInstruction: malformed field instruction \"%s\"", line);
					return null;
				}
				String owner = operand.substring(0, dot);
				String fname = operand.substring(dot + 1, colon);
				String fdesc = operand.substring(colon + 1);
				int opcode = op.equals("GETSTATIC") ? Opcodes.GETSTATIC : op.equals("PUTSTATIC") ? Opcodes.PUTSTATIC
						: op.equals("GETFIELD") ? Opcodes.GETFIELD : Opcodes.PUTFIELD;
				return new FieldInsnNode(opcode, owner, fname, fdesc);
			}

			case "INVOKESTATIC": case "INVOKEVIRTUAL": case "INVOKESPECIAL": case "INVOKEINTERFACE": {
				if (operand == null) return null;
				int dot = operand.lastIndexOf('.');
				int colon = operand.indexOf(':', dot);
				if (dot < 0 || colon < 0) {
					Log.warn(LogCategory.GAME_PATCH, "parseInstruction: malformed method instruction \"%s\"", line);
					return null;
				}
				String owner = operand.substring(0, dot);
				String mname = operand.substring(dot + 1, colon);
				String mdesc = operand.substring(colon + 1);
				int opcode = op.equals("INVOKESTATIC") ? Opcodes.INVOKESTATIC
						: op.equals("INVOKEVIRTUAL") ? Opcodes.INVOKEVIRTUAL
						: op.equals("INVOKESPECIAL") ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEINTERFACE;
				return new MethodInsnNode(opcode, owner, mname, mdesc, op.equals("INVOKEINTERFACE"));
			}

			case "CHECKCAST": case "NEW": case "INSTANCEOF": case "ANEWARRAY":
				return new TypeInsnNode(
						op.equals("CHECKCAST") ? Opcodes.CHECKCAST
						: op.equals("NEW") ? Opcodes.NEW
						: op.equals("INSTANCEOF") ? Opcodes.INSTANCEOF : Opcodes.ANEWARRAY,
						operand);

			case "ALOAD": case "ILOAD": case "LLOAD": case "FLOAD": case "DLOAD":
			case "ASTORE": case "ISTORE": case "LSTORE": case "FSTORE": case "DSTORE":
			case "RET": {
				if (op.equals("RET")) return new VarInsnNode(Opcodes.RET, Integer.parseInt(operand));
				int opcode;
				switch (op) {
					case "ALOAD": opcode = Opcodes.ALOAD; break;
					case "ILOAD": opcode = Opcodes.ILOAD; break;
					case "LLOAD": opcode = Opcodes.LLOAD; break;
					case "FLOAD": opcode = Opcodes.FLOAD; break;
					case "DLOAD": opcode = Opcodes.DLOAD; break;
					case "ASTORE": opcode = Opcodes.ASTORE; break;
					case "ISTORE": opcode = Opcodes.ISTORE; break;
					case "LSTORE": opcode = Opcodes.LSTORE; break;
					case "FSTORE": opcode = Opcodes.FSTORE; break;
					default: opcode = Opcodes.DSTORE;
				}
				return new VarInsnNode(opcode, Integer.parseInt(operand));
			}

			case "IINC": {
				String[] iincParts = operand.split("\\s+");
				return new IincInsnNode(Integer.parseInt(iincParts[0]), Integer.parseInt(iincParts[1]));
			}

			case "NOP": return new InsnNode(Opcodes.NOP);
			case "ACONST_NULL": return new InsnNode(Opcodes.ACONST_NULL);
			case "ICONST_M1": return new InsnNode(Opcodes.ICONST_M1);
			case "ICONST_0": return new InsnNode(Opcodes.ICONST_0);
			case "ICONST_1": return new InsnNode(Opcodes.ICONST_1);
			case "ICONST_2": return new InsnNode(Opcodes.ICONST_2);
			case "ICONST_3": return new InsnNode(Opcodes.ICONST_3);
			case "ICONST_4": return new InsnNode(Opcodes.ICONST_4);
			case "ICONST_5": return new InsnNode(Opcodes.ICONST_5);
			case "LCONST_0": return new InsnNode(Opcodes.LCONST_0);
			case "LCONST_1": return new InsnNode(Opcodes.LCONST_1);
			case "FCONST_0": return new InsnNode(Opcodes.FCONST_0);
			case "FCONST_1": return new InsnNode(Opcodes.FCONST_1);
			case "FCONST_2": return new InsnNode(Opcodes.FCONST_2);
			case "DCONST_0": return new InsnNode(Opcodes.DCONST_0);
			case "DCONST_1": return new InsnNode(Opcodes.DCONST_1);

			case "POP": return new InsnNode(Opcodes.POP);
			case "POP2": return new InsnNode(Opcodes.POP2);
			case "DUP": return new InsnNode(Opcodes.DUP);
			case "DUP_X1": return new InsnNode(Opcodes.DUP_X1);
			case "DUP_X2": return new InsnNode(Opcodes.DUP_X2);
			case "DUP2": return new InsnNode(Opcodes.DUP2);
			case "DUP2_X1": return new InsnNode(Opcodes.DUP2_X1);
			case "DUP2_X2": return new InsnNode(Opcodes.DUP2_X2);
			case "SWAP": return new InsnNode(Opcodes.SWAP);

			case "IADD": return new InsnNode(Opcodes.IADD);
			case "ISUB": return new InsnNode(Opcodes.ISUB);
			case "IMUL": return new InsnNode(Opcodes.IMUL);
			case "IDIV": return new InsnNode(Opcodes.IDIV);
			case "IREM": return new InsnNode(Opcodes.IREM);
			case "INEG": return new InsnNode(Opcodes.INEG);
			case "ISHL": return new InsnNode(Opcodes.ISHL);
			case "ISHR": return new InsnNode(Opcodes.ISHR);
			case "IUSHR": return new InsnNode(Opcodes.IUSHR);
			case "IAND": return new InsnNode(Opcodes.IAND);
			case "IOR": return new InsnNode(Opcodes.IOR);
			case "IXOR": return new InsnNode(Opcodes.IXOR);

			case "LADD": return new InsnNode(Opcodes.LADD);
			case "LSUB": return new InsnNode(Opcodes.LSUB);
			case "LMUL": return new InsnNode(Opcodes.LMUL);
			case "LDIV": return new InsnNode(Opcodes.LDIV);
			case "LREM": return new InsnNode(Opcodes.LREM);
			case "LNEG": return new InsnNode(Opcodes.LNEG);

			case "FADD": return new InsnNode(Opcodes.FADD);
			case "FSUB": return new InsnNode(Opcodes.FSUB);
			case "FMUL": return new InsnNode(Opcodes.FMUL);
			case "FDIV": return new InsnNode(Opcodes.FDIV);
			case "FREM": return new InsnNode(Opcodes.FREM);
			case "FNEG": return new InsnNode(Opcodes.FNEG);

			case "DADD": return new InsnNode(Opcodes.DADD);
			case "DSUB": return new InsnNode(Opcodes.DSUB);
			case "DMUL": return new InsnNode(Opcodes.DMUL);
			case "DDIV": return new InsnNode(Opcodes.DDIV);
			case "DREM": return new InsnNode(Opcodes.DREM);
			case "DNEG": return new InsnNode(Opcodes.DNEG);

			case "I2L": return new InsnNode(Opcodes.I2L);
			case "I2F": return new InsnNode(Opcodes.I2F);
			case "I2D": return new InsnNode(Opcodes.I2D);
			case "L2I": return new InsnNode(Opcodes.L2I);
			case "L2F": return new InsnNode(Opcodes.L2F);
			case "L2D": return new InsnNode(Opcodes.L2D);
			case "F2I": return new InsnNode(Opcodes.F2I);
			case "F2L": return new InsnNode(Opcodes.F2L);
			case "F2D": return new InsnNode(Opcodes.F2D);
			case "D2I": return new InsnNode(Opcodes.D2I);
			case "D2L": return new InsnNode(Opcodes.D2L);
			case "D2F": return new InsnNode(Opcodes.D2F);
			case "I2B": return new InsnNode(Opcodes.I2B);
			case "I2C": return new InsnNode(Opcodes.I2C);
			case "I2S": return new InsnNode(Opcodes.I2S);

			case "LCMP": return new InsnNode(Opcodes.LCMP);
			case "FCMPL": return new InsnNode(Opcodes.FCMPL);
			case "FCMPG": return new InsnNode(Opcodes.FCMPG);
			case "DCMPL": return new InsnNode(Opcodes.DCMPL);
			case "DCMPG": return new InsnNode(Opcodes.DCMPG);

			case "IFEQ": case "IFNE": case "IFLT": case "IFGE": case "IFGT": case "IFLE":
			case "IF_ICMPEQ": case "IF_ICMPNE": case "IF_ICMPLT": case "IF_ICMPGE":
			case "IF_ICMPGT": case "IF_ICMPLE": case "IF_ACMPEQ": case "IF_ACMPNE":
			case "GOTO": case "JSR": case "IFNULL": case "IFNONNULL": {
				LabelNode label = labelFor(operand);
				int opcode;
				switch (op) {
					case "IFEQ": opcode = Opcodes.IFEQ; break;
					case "IFNE": opcode = Opcodes.IFNE; break;
					case "IFLT": opcode = Opcodes.IFLT; break;
					case "IFGE": opcode = Opcodes.IFGE; break;
					case "IFGT": opcode = Opcodes.IFGT; break;
					case "IFLE": opcode = Opcodes.IFLE; break;
					case "IF_ICMPEQ": opcode = Opcodes.IF_ICMPEQ; break;
					case "IF_ICMPNE": opcode = Opcodes.IF_ICMPNE; break;
					case "IF_ICMPLT": opcode = Opcodes.IF_ICMPLT; break;
					case "IF_ICMPGE": opcode = Opcodes.IF_ICMPGE; break;
					case "IF_ICMPGT": opcode = Opcodes.IF_ICMPGT; break;
					case "IF_ICMPLE": opcode = Opcodes.IF_ICMPLE; break;
					case "IF_ACMPEQ": opcode = Opcodes.IF_ACMPEQ; break;
					case "IF_ACMPNE": opcode = Opcodes.IF_ACMPNE; break;
					case "GOTO": opcode = Opcodes.GOTO; break;
					case "JSR": opcode = Opcodes.JSR; break;
					case "IFNULL": opcode = Opcodes.IFNULL; break;
					default: opcode = Opcodes.IFNONNULL;
				}
				return new JumpInsnNode(opcode, label);
			}

			case "RETURN": return new InsnNode(Opcodes.RETURN);
			case "IRETURN": return new InsnNode(Opcodes.IRETURN);
			case "LRETURN": return new InsnNode(Opcodes.LRETURN);
			case "FRETURN": return new InsnNode(Opcodes.FRETURN);
			case "DRETURN": return new InsnNode(Opcodes.DRETURN);
			case "ARETURN": return new InsnNode(Opcodes.ARETURN);

			case "TABLESWITCH": case "LOOKUPSWITCH":
				return null;

			case "ATHROW": return new InsnNode(Opcodes.ATHROW);

			case "MONITORENTER": return new InsnNode(Opcodes.MONITORENTER);
			case "MONITOREXIT": return new InsnNode(Opcodes.MONITOREXIT);

			case "NEWARRAY": {
				int type;
				switch (operand) {
					case "BOOLEAN": type = Opcodes.T_BOOLEAN; break;
					case "CHAR": type = Opcodes.T_CHAR; break;
					case "FLOAT": type = Opcodes.T_FLOAT; break;
					case "DOUBLE": type = Opcodes.T_DOUBLE; break;
					case "BYTE": type = Opcodes.T_BYTE; break;
					case "SHORT": type = Opcodes.T_SHORT; break;
					case "INT": type = Opcodes.T_INT; break;
					default: type = Opcodes.T_LONG;
				}
				return new IntInsnNode(Opcodes.NEWARRAY, type);
			}
			case "BIPUSH": case "SIPUSH":
				return new IntInsnNode(op.equals("BIPUSH") ? Opcodes.BIPUSH : Opcodes.SIPUSH, Integer.parseInt(operand));

			case "IALOAD": return new InsnNode(Opcodes.IALOAD);
			case "LALOAD": return new InsnNode(Opcodes.LALOAD);
			case "FALOAD": return new InsnNode(Opcodes.FALOAD);
			case "DALOAD": return new InsnNode(Opcodes.DALOAD);
			case "AALOAD": return new InsnNode(Opcodes.AALOAD);
			case "BALOAD": return new InsnNode(Opcodes.BALOAD);
			case "CALOAD": return new InsnNode(Opcodes.CALOAD);
			case "SALOAD": return new InsnNode(Opcodes.SALOAD);
			case "IASTORE": return new InsnNode(Opcodes.IASTORE);
			case "LASTORE": return new InsnNode(Opcodes.LASTORE);
			case "FASTORE": return new InsnNode(Opcodes.FASTORE);
			case "DASTORE": return new InsnNode(Opcodes.DASTORE);
			case "AASTORE": return new InsnNode(Opcodes.AASTORE);
			case "BASTORE": return new InsnNode(Opcodes.BASTORE);
			case "CASTORE": return new InsnNode(Opcodes.CASTORE);
			case "SASTORE": return new InsnNode(Opcodes.SASTORE);
			case "ARRAYLENGTH": return new InsnNode(Opcodes.ARRAYLENGTH);

			case "LDC": {
				if (operand == null) return null;
				Object value;
				if (operand.startsWith("\"") && operand.endsWith("\"")) {
					value = operand.substring(1, operand.length() - 1);
				} else if (operand.equals("null")) {
					value = null;
				} else if (operand.endsWith("L")) {
					value = Long.parseLong(operand.substring(0, operand.length() - 1));
				} else if (operand.endsWith("F")) {
					value = Float.parseFloat(operand.substring(0, operand.length() - 1));
				} else if (operand.endsWith("D")) {
					value = Double.parseDouble(operand.substring(0, operand.length() - 1));
				} else if (operand.contains(".")) {
					value = Double.parseDouble(operand);
				} else {
					value = Integer.parseInt(operand);
				}
				return new LdcInsnNode(value);
			}

			case "LABEL": {
				return labelFor(operand);
			}

			default:
				return null;
		}
	}

	private static boolean renameLocalInAnnotations(MethodNode method, String oldName, String newName) {
		boolean changed = false;
		List<List<AnnotationNode>> allParamAnns = new ArrayList<>();
		if (method.visibleParameterAnnotations != null) {
			for (List<AnnotationNode> list : method.visibleParameterAnnotations) {
				if (list != null) allParamAnns.add(list);
			}
		}
		if (method.invisibleParameterAnnotations != null) {
			for (List<AnnotationNode> list : method.invisibleParameterAnnotations) {
				if (list != null) allParamAnns.add(list);
			}
		}
		for (List<AnnotationNode> list : allParamAnns) {
			for (AnnotationNode ann : list) {
				if (ann.desc == null || !ann.desc.contains("Local")) continue;
				if (ann.values == null) continue;
				for (int i = 0; i < ann.values.size(); i += 2) {
					if (!"name".equals(ann.values.get(i))) continue;
					Object val = ann.values.get(i + 1);
					
					// XỬ LÝ CẢ String VÀ List (String[])
					if (val instanceof String) {
						if (oldName.equals(val)) {
							ann.values.set(i + 1, newName);
							changed = true;
						}
					} else if (val instanceof List) {
						@SuppressWarnings("unchecked")
						List<Object> nameList = (List<Object>) val;
						for (int j = 0; j < nameList.size(); j++) {
							if (oldName.equals(nameList.get(j))) {
								nameList.set(j, newName);
								changed = true;
							}
						}
					}
				}
			}
		}
		return changed;
	}

	private static final java.util.Map<String, org.objectweb.asm.tree.LabelNode> LABELS = new java.util.HashMap<>();

	private static org.objectweb.asm.tree.LabelNode labelFor(String name) {
		return LABELS.computeIfAbsent(name, k -> new org.objectweb.asm.tree.LabelNode());
	}

	private static volatile java.lang.reflect.Field MOONRISE_OLD_TICKET_LEVEL_FIELD;
	private static volatile boolean moonriseFieldLookupFailed = false;

	private static Map<String, String> parseReflectiveConfig(String config) {
		Map<String, String> ops = new HashMap<>();
		for (String op : config.split(";")) {
			op = op.trim();
			if (op.isEmpty()) continue;
			int eq = op.indexOf('=');
			if (eq < 0) {
				ops.put(op, "");
			} else {
				ops.put(op.substring(0, eq), op.substring(eq + 1));
			}
		}
		return ops;
	}

	private static Class<?> resolveReflectiveClass(String name) {
		if (name == null) return null;
		if (name.contains(".")) {
			try {
				return FabricLauncherBase.getClass(name);
			} catch (ClassNotFoundException e) {
				return null;
			}
		}
		String[] commonPackages = {
			"net.minecraft.world.level.block.entity.",
			"net.minecraft.server.level.",
			"net.minecraft.world.entity.",
			"net.minecraft.world.level.",
			"net.minecraft.server.",
			"net.minecraft.core.",
			"net.minecraft.world.effect.",
		};
		for (String pkg : commonPackages) {
			try {
				return FabricLauncherBase.getClass(pkg + name);
			} catch (ClassNotFoundException ignored) {}
		}
		return null;
	}

	private static Object invokeReflectiveMethod(Class<?> owner, Object[] args, String methodName) {
		try {
			Object instance = args[0];
			Object[][] callArgsCandidates = {
				new Object[]{},
				new Object[]{args[1]},
				new Object[]{args[1], args[2]},
				new Object[]{args[1], args[2], args[3]},
				new Object[]{instance},
				new Object[]{instance, args[1]},
			};

			for (Object[] callArgs : callArgsCandidates) {
				for (java.lang.reflect.Method m : owner.getDeclaredMethods()) {
					if (!m.getName().equals(methodName)) continue;
					if (m.getParameterCount() != callArgs.length) continue;
					boolean compatible = true;
					Class<?>[] paramTypes = m.getParameterTypes();
					for (int i = 0; i < callArgs.length; i++) {
						if (callArgs[i] == null) continue;
						if (!paramTypes[i].isInstance(callArgs[i])) {
							compatible = false;
							break;
						}
					}
					if (!compatible) continue;

					m.setAccessible(true);
					Object target = java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : instance;
					try {
						return m.invoke(target, callArgs);
					} catch (Throwable ignored) {}
				}
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "invokeReflectiveMethod %s failed", methodName, t);
		}
		return null;
	}

	private static long executeFabricTransfer(String spec, Object[] args, Map<String, Object> vars) {
		try {
			Map<String, String> tOps = parseReflectiveConfig(spec.replace(",", ";"));

			Object instance = args[0];
			Object level = args[1];
			Object pos = args[2];

			Object direction = null;
			String dirSpec = tOps.get("direction");
			if (dirSpec != null && dirSpec.startsWith("getField:")) {
				String fieldName = dirSpec.substring("getField:".length());
				java.lang.reflect.Field f = instance.getClass().getDeclaredField(fieldName);
				f.setAccessible(true);
				direction = f.get(instance);
			} else if ("DOWN".equals(dirSpec)) {
				Class<?> dirClass = FabricLauncherBase.getClass("net.minecraft.core.Direction");
				direction = dirClass.getField("DOWN").get(null);
			} else if ("UP".equals(dirSpec)) {
				Class<?> dirClass = FabricLauncherBase.getClass("net.minecraft.core.Direction");
				direction = dirClass.getField("UP").get(null);
			}

			Class<?> itemStorageClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.item.ItemStorage");
			Object sided = itemStorageClass.getField("SIDED").get(null);

			Object target = null;
			for (java.lang.reflect.Method m : sided.getClass().getMethods()) {
				if (!m.getName().equals("find")) continue;
				if (m.getParameterCount() != 3) continue;
				try {
					target = m.invoke(sided, level, pos, direction);
					break;
				} catch (Throwable ignored) {}
			}
			if (target == null) return 0;

			Class<?> containerStorageClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage");
			Object source = null;
			for (java.lang.reflect.Method m : containerStorageClass.getMethods()) {
				if (!m.getName().equals("of")) continue;
				if (m.getParameterCount() != 2) continue;
				try {
					source = m.invoke(null, instance, direction);
					break;
				} catch (Throwable ignored) {}
			}
			if (source == null) return 0;

			Class<?> storageUtilClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil");
			Object filter = java.lang.reflect.Proxy.newProxyInstance(
					PaperModSignatureCompat.class.getClassLoader(),
					new Class<?>[] { java.util.function.Predicate.class },
					(proxy, method, a) -> Boolean.TRUE);

			for (java.lang.reflect.Method m : storageUtilClass.getMethods()) {
				if (!m.getName().equals("move")) continue;
				if (m.getParameterCount() < 4) continue;
				try {
					Object[] moveArgs = new Object[m.getParameterCount()];
					moveArgs[0] = source;
					moveArgs[1] = target;
					moveArgs[2] = filter;
					moveArgs[3] = 1L;
					for (int i = 4; i < moveArgs.length; i++) moveArgs[i] = null;
					Object moved = m.invoke(null, moveArgs);
					return ((Number) moved).longValue();
				} catch (Throwable ignored) {}
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "executeFabricTransfer failed: spec=%s", spec, t);
		}
		return 0;
	}

	@SuppressWarnings("unused")
	public static int getMoonriseOldTicketLevel(Object chunkHolder) {
		try {
			java.lang.reflect.Field f = MOONRISE_OLD_TICKET_LEVEL_FIELD;
			if (f == null && !moonriseFieldLookupFailed) {
				synchronized (PaperModSignatureCompat.class) {
					f = MOONRISE_OLD_TICKET_LEVEL_FIELD;
					if (f == null && !moonriseFieldLookupFailed) {
						Object newChunkHolder = chunkHolder.getClass()
								.getMethod("moonrise$getRealChunkHolder")
								.invoke(chunkHolder);
						f = newChunkHolder.getClass().getDeclaredField("oldTicketLevel");
						f.setAccessible(true);
						MOONRISE_OLD_TICKET_LEVEL_FIELD = f;
					}
				}
			}
			if (f == null) return 0;

			Object newChunkHolder = chunkHolder.getClass()
					.getMethod("moonrise$getRealChunkHolder")
					.invoke(chunkHolder);
			return (int) f.get(newChunkHolder);
		} catch (Throwable t) {
			if (!moonriseFieldLookupFailed) {
				moonriseFieldLookupFailed = true;
				Log.warn(LogCategory.GAME_PATCH, "Could not locate Moonrise's NewChunkHolder."
						+ "oldTicketLevel via reflection at runtime — mods relying on "
						+ "ChunkHolder.oldTicketLevel will read 0 instead of the real value", t);
			}
			return 0;
		}
	}

	@SuppressWarnings("unused")
	public static String[] getRealServerLaunchArgs() {
		try {
			return FabricLoaderImpl.INSTANCE.getGameProvider().getLaunchArguments(false);
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "Could not reconstruct the real server launch "
					+ "arguments for a retargeted mod Mixin callback; passing an empty array "
					+ "instead", t);
			return new String[0];
		}
	}

	@SuppressWarnings("unused")
	public static boolean wrapAllowRemoveAllEffects(Object self, Object operation) {
		try {
			java.lang.reflect.Field f = null;
			Class<?> c = self.getClass();
			while (c != null && f == null) {
				try {
					f = c.getDeclaredField("activeEffects");
				} catch (NoSuchFieldException e) {
					c = c.getSuperclass();
				}
			}
			if (f == null) {
				Log.warn(LogCategory.GAME_PATCH, "wrapAllowRemoveAllEffects: could not find activeEffects field");
				return callOriginalBoolean(operation);
			}
			f.setAccessible(true);

			@SuppressWarnings("unchecked")
			java.util.Map<Object, Object> activeEffects =
					(java.util.Map<Object, Object>) f.get(self);

			if (activeEffects == null || activeEffects.isEmpty()) {
				return callOriginalBoolean(operation);
			}

			java.util.Map<Object, Object> snapshot = new java.util.HashMap<>(activeEffects);

			boolean result = callOriginalBoolean(operation);

			Class<?> eventsClass = FabricLauncherBase.getClass(
					"net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents");
			Object event = eventsClass.getField("ALLOW_EARLY_REMOVE").get(null);
			Object invoker = event.getClass().getMethod("invoker").invoke(event);

			Class<?> mobEffectUtilClass = FabricLauncherBase.getClass(
					"net.fabricmc.fabric.impl.entity.event.effect.MobEffectUtil");
			Object ctx = mobEffectUtilClass.getMethod("getCommandContext").invoke(null);

			java.lang.reflect.Method allowEarlyRemove = invoker.getClass().getMethod(
					"allowEarlyRemove",
					FabricLauncherBase.getClass("net.minecraft.world.effect.MobEffectInstance"),
					FabricLauncherBase.getClass("net.minecraft.world.entity.LivingEntity"),
					ctx.getClass());

			for (java.util.Map.Entry<Object, Object> entry : snapshot.entrySet()) {
				boolean cannotRemove = !((Boolean) allowEarlyRemove.invoke(
						invoker, entry.getValue(), self, ctx));
				if (cannotRemove) {
					activeEffects.put(entry.getKey(), entry.getValue());
				}
			}
			return result;
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "wrapAllowRemoveAllEffects failed", t);
			return false;
		}
	}

	private static boolean callOriginalBoolean(Object operation) throws Exception {
		Class<?> opClass = FabricLauncherBase.getClass(
				"com.llamalad7.mixinextras.injector.wrapoperation.Operation");
		java.lang.reflect.Method callMethod = opClass.getMethod("call", Object[].class);
		Object result = callMethod.invoke(operation, (Object) new Object[0]);
		return (Boolean) result;
	}

	private static final ThreadLocal<Boolean> IN_REMOVE_ALL_EFFECTS = ThreadLocal.withInitial(() -> false);

	@SuppressWarnings("unused")
	public static void handleRemoveAllEffects(Object self, Object cir) {
		if (IN_REMOVE_ALL_EFFECTS.get()) {
			return;
		}
		IN_REMOVE_ALL_EFFECTS.set(true);
		try {
			java.lang.reflect.Field activeEffectsField = null;
			Class<?> c = self.getClass();
			while (c != null && activeEffectsField == null) {
				try {
					activeEffectsField = c.getDeclaredField("activeEffects");
				} catch (NoSuchFieldException e) {
					c = c.getSuperclass();
				}
			}
			if (activeEffectsField == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleRemoveAllEffects: could not find activeEffects field");
				return;
			}
			activeEffectsField.setAccessible(true);

			@SuppressWarnings("unchecked")
			java.util.Map<Object, Object> activeEffects =
					(java.util.Map<Object, Object>) activeEffectsField.get(self);

			if (activeEffects == null || activeEffects.isEmpty()) {
				return;
			}

			java.util.Map<Object, Object> snapshot = new java.util.HashMap<>(activeEffects);

			java.lang.reflect.Method removeAllEffects = self.getClass().getMethod("removeAllEffects");
			removeAllEffects.setAccessible(true);
			boolean originalResult = (Boolean) removeAllEffects.invoke(self);

			Class<?> eventsClass = FabricLauncherBase.getClass(
					"net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents");
			Object event = eventsClass.getField("ALLOW_EARLY_REMOVE").get(null);
			Object invoker = event.getClass().getMethod("invoker").invoke(event);

			Class<?> mobEffectUtilClass = FabricLauncherBase.getClass(
					"net.fabricmc.fabric.impl.entity.event.effect.MobEffectUtil");
			Object ctx = mobEffectUtilClass.getMethod("getCommandContext").invoke(null);

			java.lang.reflect.Method allowEarlyRemove = invoker.getClass().getMethod(
					"allowEarlyRemove",
					FabricLauncherBase.getClass("net.minecraft.world.effect.MobEffectInstance"),
					FabricLauncherBase.getClass("net.minecraft.world.entity.LivingEntity"),
					ctx.getClass());

			for (java.util.Map.Entry<Object, Object> entry : snapshot.entrySet()) {
				boolean cannotRemove = !((Boolean) allowEarlyRemove.invoke(
						invoker, entry.getValue(), self, ctx));
				if (cannotRemove) {
					activeEffects.put(entry.getKey(), entry.getValue());
				}
			}

			try {
				java.lang.reflect.Method setReturnValue = cir.getClass().getMethod("setReturnValue", Object.class);
				setReturnValue.invoke(cir, originalResult);
			} catch (NoSuchMethodException ignored) {
			}
				java.lang.reflect.Method cancel = cir.getClass().getMethod("cancel");
				cancel.invoke(cir);
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "handleRemoveAllEffects failed", t);
		} finally {
			IN_REMOVE_ALL_EFFECTS.set(false);
		}
	}

	@SuppressWarnings("unused")
	public static void handleHookInsert(Object level, Object pos, Object hopper, Object cir) {
		try {
			Class<?> levelClass = FabricLauncherBase.getClass("net.minecraft.world.level.Level");
			Class<?> blockPosClass = FabricLauncherBase.getClass("net.minecraft.core.BlockPos");
			Class<?> hopperClass = FabricLauncherBase.getClass("net.minecraft.world.level.block.entity.HopperBlockEntity");
			Class<?> containerClass = FabricLauncherBase.getClass("net.minecraft.world.Container");

			java.lang.reflect.Field facingField = hopperClass.getDeclaredField("facing");
			facingField.setAccessible(true);
			Object direction = facingField.get(hopper);

			Object container = null;
			try {
				java.lang.reflect.Method getInsertInventory = hopperClass.getMethod("getInsertInventory", levelClass);
				getInsertInventory.setAccessible(true);
				container = getInsertInventory.invoke(hopper, level);
			} catch (NoSuchMethodException e) {
				java.lang.reflect.Method getAttachedContainer = hopperClass.getDeclaredMethod(
						"getAttachedContainer", levelClass, blockPosClass, hopperClass);
				getAttachedContainer.setAccessible(true);
				container = getAttachedContainer.invoke(null, level, pos, hopper);
			}

			if (container != null) {
				return;
			}

			java.lang.reflect.Method getOpposite = direction.getClass().getMethod("getOpposite");
			Object oppositeDirection = getOpposite.invoke(direction);

			java.lang.reflect.Method relative = blockPosClass.getMethod("relative", direction.getClass());
			Object targetPos = relative.invoke(pos, direction);

			Class<?> itemStorageClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.item.ItemStorage");
			Object sided = itemStorageClass.getField("SIDED").get(null);
			Class<?> storageClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.storage.Storage");

			java.lang.reflect.Method findMethod = null;
			for (java.lang.reflect.Method m : sided.getClass().getMethods()) {
				if (!m.getName().equals("find")) continue;
				Class<?>[] params = m.getParameterTypes();
				if (params.length >= 3) {
					findMethod = m;
					break;
				}
			}
			if (findMethod == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHookInsert: could not find ItemStorage.SIDED.find(...)");
				return;
			}
			Object[] findArgs = new Object[findMethod.getParameterCount()];
			findArgs[0] = level;
			findArgs[1] = targetPos;
			findArgs[2] = oppositeDirection;
			for (int i = 3; i < findArgs.length; i++) findArgs[i] = null;
			Object target = findMethod.invoke(sided, findArgs);

			if (target == null) {
				return;
			}

			Class<?> containerStorageClass = FabricLauncherBase.getClass(
					"net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage");
			java.lang.reflect.Method ofMethod = null;
			for (java.lang.reflect.Method m : containerStorageClass.getMethods()) {
				if (m.getName().equals("of") && m.getParameterCount() == 2) {
					ofMethod = m;
					break;
				}
			}
			if (ofMethod == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHookInsert: could not find ContainerStorage.of(...)");
				return;
			}
			Object source = ofMethod.invoke(null, hopper, direction);

			Class<?> storageUtilClass = FabricLauncherBase.getClass(
					"net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil");
			java.lang.reflect.Method moveMethod = null;
			for (java.lang.reflect.Method m : storageUtilClass.getMethods()) {
				if (m.getName().equals("move") && m.getParameterCount() >= 4) {
					moveMethod = m;
					break;
				}
			}
			if (moveMethod == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHookInsert: could not find StorageUtil.move(...)");
				return;
			}

			Object filter = java.lang.reflect.Proxy.newProxyInstance(
					PaperModSignatureCompat.class.getClassLoader(),
					new Class<?>[] { FabricLauncherBase.getClass("java.util.function.Predicate") },
					(proxy, method, args) -> Boolean.TRUE);

			Object[] moveArgs = new Object[moveMethod.getParameterCount()];
			moveArgs[0] = source;
			moveArgs[1] = target;
			moveArgs[2] = filter;
			moveArgs[3] = 1L;
			for (int i = 4; i < moveArgs.length; i++) moveArgs[i] = null;
			Object movedObj = moveMethod.invoke(null, moveArgs);
			long moved = ((Number) movedObj).longValue();

			java.lang.reflect.Method setReturnValue = cir.getClass().getMethod("setReturnValue", Object.class);
			setReturnValue.invoke(cir, moved == 1);
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "handleHookInsert failed", t);
		}
	}

	@SuppressWarnings("unused")
	public static void handleHookExtract(Object level, Object hopper, Object cir) {
		try {
			Class<?> levelClass = FabricLauncherBase.getClass("net.minecraft.world.level.Level");
			Class<?> hopperClass = FabricLauncherBase.getClass("net.minecraft.world.level.block.entity.HopperBlockEntity");
			Class<?> hopperIface = FabricLauncherBase.getClass("net.minecraft.world.level.block.entity.Hopper");
			Class<?> blockPosClass = FabricLauncherBase.getClass("net.minecraft.core.BlockPos");
			Class<?> directionClass = FabricLauncherBase.getClass("net.minecraft.core.Direction");
			Class<?> blockStateClass = FabricLauncherBase.getClass("net.minecraft.world.level.block.state.BlockState");

			java.lang.reflect.Method getLevelX = hopperIface.getMethod("getLevelX");
			java.lang.reflect.Method getLevelY = hopperIface.getMethod("getLevelY");
			java.lang.reflect.Method getLevelZ = hopperIface.getMethod("getLevelZ");
			double x = ((Number) getLevelX.invoke(hopper)).doubleValue();
			double y = ((Number) getLevelY.invoke(hopper)).doubleValue();
			double z = ((Number) getLevelZ.invoke(hopper)).doubleValue();

			java.lang.reflect.Method containing = blockPosClass.getMethod("containing", double.class, double.class, double.class);
			Object blockPos = containing.invoke(null, x, y + 1.0, z);

			java.lang.reflect.Method getBlockState = levelClass.getMethod("getBlockState", blockPosClass);
			Object blockState = getBlockState.invoke(level, blockPos);

			Object container = null;
			try {
				java.lang.reflect.Method getExtractInventory = hopperClass.getDeclaredMethod(
						"getExtractInventory", levelClass, hopperIface, blockPosClass, blockStateClass);
				getExtractInventory.setAccessible(true);
				container = getExtractInventory.invoke(null, level, hopper, blockPos, blockState);
			} catch (NoSuchMethodException e) {
				java.lang.reflect.Method getSourceContainer = hopperClass.getDeclaredMethod(
						"getSourceContainer", levelClass, hopperIface, blockPosClass, blockStateClass);
				getSourceContainer.setAccessible(true);
				container = getSourceContainer.invoke(null, level, hopper, blockPos, blockState);
			}

			if (container != null) {
				return;
			}

			Object downDirection = directionClass.getField("DOWN").get(null);

			Class<?> itemStorageClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.item.ItemStorage");
			Object sided = itemStorageClass.getField("SIDED").get(null);

			java.lang.reflect.Method findMethod = null;
			for (java.lang.reflect.Method m : sided.getClass().getMethods()) {
				if (!m.getName().equals("find")) continue;
				if (m.getParameterCount() >= 3) { findMethod = m; break; }
			}
			if (findMethod == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHookExtract: could not find ItemStorage.SIDED.find(...)");
				return;
			}
			Object[] findArgs = new Object[findMethod.getParameterCount()];
			findArgs[0] = level;
			findArgs[1] = blockPos;
			findArgs[2] = downDirection;
			for (int i = 3; i < findArgs.length; i++) findArgs[i] = null;
			Object source = findMethod.invoke(sided, findArgs);

			if (source == null) {
				return;
			}

			Object upDirection = directionClass.getField("UP").get(null);
			Class<?> containerStorageClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage");
			java.lang.reflect.Method ofMethod = null;
			for (java.lang.reflect.Method m : containerStorageClass.getMethods()) {
				if (m.getName().equals("of") && m.getParameterCount() == 2) { ofMethod = m; break; }
			}
			if (ofMethod == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHookExtract: could not find ContainerStorage.of(...)");
				return;
			}
			Object target = ofMethod.invoke(null, hopper, upDirection);

			Class<?> storageUtilClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil");
			java.lang.reflect.Method moveMethod = null;
			for (java.lang.reflect.Method m : storageUtilClass.getMethods()) {
				if (m.getName().equals("move") && m.getParameterCount() >= 4) { moveMethod = m; break; }
			}
			if (moveMethod == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHookExtract: could not find StorageUtil.move(...)");
				return;
			}

			Object filter = java.lang.reflect.Proxy.newProxyInstance(
					PaperModSignatureCompat.class.getClassLoader(),
					new Class<?>[] { FabricLauncherBase.getClass("java.util.function.Predicate") },
					(proxy, method, args) -> Boolean.TRUE);

			Object[] moveArgs = new Object[moveMethod.getParameterCount()];
			moveArgs[0] = source;
			moveArgs[1] = target;
			moveArgs[2] = filter;
			moveArgs[3] = 1L;
			for (int i = 4; i < moveArgs.length; i++) moveArgs[i] = null;
			Object movedObj = moveMethod.invoke(null, moveArgs);
			long moved = ((Number) movedObj).longValue();

			java.lang.reflect.Method setReturnValue = cir.getClass().getMethod("setReturnValue", Object.class);
			setReturnValue.invoke(cir, moved == 1);
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "handleHookExtract failed", t);
		}
	}

	@SuppressWarnings("unused")
	public static void handleReflective(Object instance, Object arg0, Object arg1, Object arg2, String config) {
		Object[] args = { instance, arg0, arg1, arg2 };
		Map<String, String> ops = parseReflectiveConfig(config);
		Map<String, Object> vars = new HashMap<>();

		try {
			String ownerName = ops.get("owner");
			Class<?> ownerClass = resolveReflectiveClass(ownerName);
			if (ownerClass == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleReflective: cannot resolve owner=%s", ownerName);
				return;
			}

			String findSpec = ops.get("find");
			if (findSpec != null) {
				String[] findParts = findSpec.split(":", 2);
				String varName = findParts[0];
				String[] methodNames = findParts[1].split("\\|");

				Object found = null;
				for (String mName : methodNames) {
					found = invokeReflectiveMethod(ownerClass, args, mName.trim());
					if (found != null) break;
				}
				vars.put(varName, found);

				String skipIfNull = ops.get("skipIfNull");
				if (skipIfNull != null && skipIfNull.equals(varName) && found == null) {
					return;
				}

				String returnIfNull = ops.get("returnIfNull");
				if (returnIfNull != null && returnIfNull.equals(varName) && found == null) {
					return;
				}
			}

			String transferSpec = ops.get("fabricTransfer");
			if (transferSpec != null) {
				long moved = executeFabricTransfer(transferSpec, args, vars);
				if (ops.containsKey("setResultFromTransfer")) {
					Object cir = args[3];
					if (cir != null) {
						java.lang.reflect.Method setReturnValue = cir.getClass().getMethod("setReturnValue", Object.class);
						setReturnValue.invoke(cir, moved == 1);
					}
				}
			}

			String setResult = ops.get("setResult");
			if (setResult != null) {
				Object cir = args[3];
				if (cir != null) {
					Object value = "true".equals(setResult) ? Boolean.TRUE
							: "false".equals(setResult) ? Boolean.FALSE : setResult;
					java.lang.reflect.Method setReturnValue = cir.getClass().getMethod("setReturnValue", Object.class);
					setReturnValue.invoke(cir, value);
				}
			}

			if (ops.containsKey("cancel")) {
				Object cir = args[3];
				if (cir != null) {
					java.lang.reflect.Method cancel = cir.getClass().getMethod("cancel");
					cancel.invoke(cir);
				}
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "handleReflective failed: config=%s", config, t);
		}
	}

	@SuppressWarnings("unused")
	public static void handleHasNoMonstersNearby(Object self, Object pos, Object direction, Object cir) {
		try {
			Class<?> serverPlayerClass = FabricLauncherBase.getClass("net.minecraft.server.level.ServerPlayer");
			if (!serverPlayerClass.isInstance(self)) return;

			Class<?> playerClass = FabricLauncherBase.getClass("net.minecraft.world.entity.player.Player");
			java.lang.reflect.Method isCreativeMethod = playerClass.getMethod("isCreative");
			boolean isCreative = (Boolean) isCreativeMethod.invoke(self);
			if (isCreative) return;

			Class<?> entityClass = FabricLauncherBase.getClass("net.minecraft.world.entity.Entity");
			java.lang.reflect.Method levelMethod = entityClass.getMethod("level");
			Object level = levelMethod.invoke(self);

			Class<?> blockPosClass = FabricLauncherBase.getClass("net.minecraft.core.BlockPos");
			Class<?> vec3Class = FabricLauncherBase.getClass("net.minecraft.world.phys.Vec3");
			java.lang.reflect.Method atBottomCenterOf = vec3Class.getMethod("atBottomCenterOf", 
					FabricLauncherBase.getClass("net.minecraft.core.Vec3i"));
			Object bedCenter = atBottomCenterOf.invoke(null, pos);

			java.lang.reflect.Method xMethod = vec3Class.getMethod("x");
			java.lang.reflect.Method yMethod = vec3Class.getMethod("y");
			java.lang.reflect.Method zMethod = vec3Class.getMethod("z");
			double x = ((Number) xMethod.invoke(bedCenter)).doubleValue();
			double y = ((Number) yMethod.invoke(bedCenter)).doubleValue();
			double z = ((Number) zMethod.invoke(bedCenter)).doubleValue();

			Class<?> aabbClass = FabricLauncherBase.getClass("net.minecraft.world.phys.AABB");
			Object aabb = aabbClass.getConstructor(
					double.class, double.class, double.class,
					double.class, double.class, double.class)
					.newInstance(x - 8.0, y - 5.0, z - 8.0, x + 8.0, y + 5.0, z + 8.0);

			Class<?> monsterClass = FabricLauncherBase.getClass("net.minecraft.world.entity.monster.Monster");
			Class<?> levelClass = FabricLauncherBase.getClass("net.minecraft.world.level.Level");

			java.lang.reflect.Method isPreventing = monsterClass.getMethod(
					"isPreventingPlayerRest", levelClass, playerClass);

			final Object fLevel = level;
			final Object fSelf = self;
			Object predicate = java.lang.reflect.Proxy.newProxyInstance(
					PaperModSignatureCompat.class.getClassLoader(),
					new Class<?>[] { java.util.function.Predicate.class },
					(proxy, method, args) -> {
						try {
							return isPreventing.invoke(args[0], fLevel, fSelf);
						} catch (Throwable t) {
							return false;
						}
					});

			java.lang.reflect.Method getEntities = null;
			for (java.lang.reflect.Method m : levelClass.getMethods()) {
				if (m.getName().equals("getEntitiesOfClass") && m.getParameterCount() == 3) {
					getEntities = m;
					break;
				}
			}
			if (getEntities == null) {
				Log.warn(LogCategory.GAME_PATCH, "handleHasNoMonstersNearby: getEntitiesOfClass not found");
				return;
			}
			@SuppressWarnings("unchecked")
			java.util.List<Object> monsters = (java.util.List<Object>) 
					getEntities.invoke(level, monsterClass, aabb, predicate);

			boolean vanillaResult;
			if (monsters == null || monsters.isEmpty()) {
				vanillaResult = true;
			} else {
				boolean allowNearMonsters = false;
				try {
					Class<?> purpurConfigClass = FabricLauncherBase.getClass("org.purpurmc.purpur.PurpurConfig");
					java.lang.reflect.Field field = purpurConfigClass.getField("playerSleepNearMonsters");
					allowNearMonsters = (Boolean) field.get(null);
				} catch (Throwable ignored) {}
				vanillaResult = allowNearMonsters;
			}

			boolean eventResult = vanillaResult;
			try {
				Class<?> eventsClass = FabricLauncherBase.getClass(
						"net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents");
				Object event = eventsClass.getField("ALLOW_NEARBY_MONSTERS").get(null);
				Object invoker = event.getClass().getMethod("invoker").invoke(event);

				Class<?> eventResultClass = FabricLauncherBase.getClass(
						"net.fabricmc.fabric.api.util.EventResult");
				Object[] enumConstants = eventResultClass.getEnumConstants();

				java.lang.reflect.Method allowNearby = null;
				for (java.lang.reflect.Method m : invoker.getClass().getMethods()) {
					if (m.getName().equals("allowNearbyMonsters") && m.getParameterCount() == 3) {
						allowNearby = m;
						break;
					}
				}
				if (allowNearby != null) {
					Object result = allowNearby.invoke(invoker, self, pos, vanillaResult);
					java.lang.reflect.Method allowAction = result.getClass().getMethod("allowAction", boolean.class);
					eventResult = (Boolean) allowAction.invoke(result, vanillaResult);
				}
			} catch (Throwable t) {
				Log.warn(LogCategory.GAME_PATCH, "handleHasNoMonstersNearby: Fabric event call failed", t);
			}

			if (!eventResult) {
				Class<?> bedProblemClass = FabricLauncherBase.getClass(
						"net.minecraft.world.entity.player.Player$BedSleepingProblem");
				Object notSafe = bedProblemClass.getField("NOT_SAFE").get(null);
				Class<?> eitherClass = FabricLauncherBase.getClass("com.mojang.datafixers.util.Either");
				Object leftEither = eitherClass.getMethod("left", Object.class).invoke(null, notSafe);

				java.lang.reflect.Method setReturnValue = cir.getClass().getMethod(
						"setReturnValue", Object.class);
				setReturnValue.invoke(cir, leftEither);
				java.lang.reflect.Method cancel = cir.getClass().getMethod("cancel");
				cancel.invoke(cir);
			}
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "handleHasNoMonstersNearby failed", t);
		}
	}
	
	@SuppressWarnings("unused")
	public static com.mojang.datafixers.util.Pair<Object, Object> 
			handleCloseContainerScreen(Object player, Object menu, Object cancelled) {
		try {
			Object factory = getOpenMenuFactory();
			
			Class<?> craftEventFactory = FabricLauncherBase.getClass("org.bukkit.craftbukkit.event.CraftEventFactory");
			Class<?> serverPlayerClass = FabricLauncherBase.getClass("net.minecraft.server.level.ServerPlayer");
			Class<?> menuClass = FabricLauncherBase.getClass("net.minecraft.world.inventory.AbstractContainerMenu");
			
			java.lang.reflect.Method callMethod = craftEventFactory.getMethod(
					"callInventoryOpenEventWithTitle", serverPlayerClass, menuClass, boolean.class);
			callMethod.setAccessible(true);
			
			Object result = callMethod.invoke(null, player, menu, cancelled);
			
			if (factory != null) {
				Class<?> menuProviderClass = FabricLauncherBase.getClass("net.minecraft.world.MenuProvider");
				java.lang.reflect.Method shouldCloseMethod = menuProviderClass.getMethod("shouldCloseCurrentScreen");
				shouldCloseMethod.setAccessible(true);
				boolean shouldClose = (Boolean) shouldCloseMethod.invoke(factory);
				
				Class<?> playerClass = FabricLauncherBase.getClass("net.minecraft.world.entity.player.Player");
				if (shouldClose) {
					java.lang.reflect.Method closeContainer = playerClass.getMethod("closeContainer");
					closeContainer.setAccessible(true);
					closeContainer.invoke(player);
				} else {
					java.lang.reflect.Method doCloseContainer = playerClass.getMethod("doCloseContainer");
					doCloseContainer.setAccessible(true);
					doCloseContainer.invoke(player);
				}
			}
			
			return (com.mojang.datafixers.util.Pair<Object, Object>) result;
		} catch (Throwable t) {
			Log.warn(LogCategory.GAME_PATCH, "handleCloseContainerScreen failed", t);
			return null;
		}
	}
		
	public static Object handleSetRespawnPosition(Object[] args) throws Throwable {
		Object player = args[0], config = args[1];
		boolean sendMessage = (Boolean) args[2];
		Object cause = args[3], operation = args[4];

		Class<?> playerClass = FabricLauncherBase.getClass("net.minecraft.world.entity.player.Player");
		Class<?> blockPosClass = FabricLauncherBase.getClass("net.minecraft.core.BlockPos");

		Class<?> eventsClass = FabricLauncherBase.getClass("net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents");
		Class<?> allowSettingSpawnIface = FabricLauncherBase.getClass(
				"net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents$AllowSettingSpawn");
		Object event = eventsClass.getField("ALLOW_SETTING_SPAWN").get(null);
		Object invoker = event.getClass().getMethod("invoker").invoke(event);

		Object respawnData = config.getClass().getMethod("respawnData").invoke(config);
		Object pos = respawnData.getClass().getMethod("pos").invoke(respawnData);

		boolean allowed = (boolean) allowSettingSpawnIface
				.getMethod("allowSettingSpawn", playerClass, blockPosClass)
				.invoke(invoker, player, pos);

		if (!allowed) return Boolean.FALSE;

		Class<?> opClass = FabricLauncherBase.getClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation");
		Object result = opClass.getMethod("call", Object[].class)
				.invoke(operation, (Object) new Object[]{player, config, sendMessage, cause});
		return result;
	}

	@SuppressWarnings("unused")
	public static Object wrapGetCodecForWriteCap(Object[] args) throws Throwable {
		// args: [self(coerced StreamCodec), identifier, operation, buf, type(unused), payload(unused)]
		return wrapGetCodecCommon(args[0], args[1], args[2], args[3]);
	}

	@SuppressWarnings("unused")
	public static Object wrapGetCodecForDecodeLambda(Object[] args) throws Throwable {
		// args: [self(coerced StreamCodec), identifier, operation, identifier2(dup, unused), buf]
		return wrapGetCodecCommon(args[0], args[1], args[2], args[4]);
	}

	private static Object wrapGetCodecCommon(
			Object self,
			Object identifier,
			Object operation,
			Object buf
	) throws Throwable {
		/*
		Log.warn(LogCategory.GAME_PATCH,
				"wrapGetCodecCommon: self=" + self.getClass().getName()
						+ " identifier=" + identifier
						+ " operation=" + operation.getClass().getName()
						+ " buf=" + buf.getClass().getName());
		*/
		java.lang.reflect.Field providerField = null;

		Class<?> c = self.getClass();

		while (c != null) {
			try {
				providerField = c.getDeclaredField("customPayloadTypeProvider");
				break;
			} catch (NoSuchFieldException ignored) {
				c = c.getSuperclass();
			}
		}

		if (providerField != null) {
			providerField.setAccessible(true);

			Object provider = providerField.get(self);

			if (provider != null) {

				for (Class<?> pc = provider.getClass();
					 pc != null;
					 pc = pc.getSuperclass()) {

					for (java.lang.reflect.Method m : pc.getDeclaredMethods()) {

						if (!m.getName().equals("get")
								|| m.getParameterCount() != 2) {
							continue;
						}

						Class<?>[] p = m.getParameterTypes();

						if (!p[0].isAssignableFrom(buf.getClass())
								|| !p[1].isAssignableFrom(identifier.getClass())) {
							continue;
						}

						m.setAccessible(true);

						Object typeAndCodec =
								m.invoke(provider, buf, identifier);

						if (typeAndCodec == null) {
							break;
						}

						for (Class<?> tc = typeAndCodec.getClass();
							 tc != null;
							 tc = tc.getSuperclass()) {

							for (java.lang.reflect.Method codecMethod
									: tc.getDeclaredMethods()) {

								if (!codecMethod.getName().equals("codec")
										|| codecMethod.getParameterCount() != 0) {
									continue;
								}

								codecMethod.setAccessible(true);

								Object codec =
										codecMethod.invoke(typeAndCodec);

								if (codec != null) {
									return codec;
								}
							}
						}

						break;
					}
				}
			}
		}

		if (operation != null) {

			for (Class<?> oc = operation.getClass();
				 oc != null;
				 oc = oc.getSuperclass()) {

				for (java.lang.reflect.Method m : oc.getDeclaredMethods()) {

					if (!m.getName().equals("call")
							|| m.getParameterCount() != 1) {
						continue;
					}

					Class<?>[] p = m.getParameterTypes();

					if (!p[0].isArray()) {
						continue;
					}

					m.setAccessible(true);

					Object result =
							m.invoke(
									operation,
									(Object) new Object[]{
											self,
											identifier
									}
							);

					if (result != null) {
						return result;
					}
				}
			}
		}

		throw new IllegalStateException(
				"wrapGetCodecCommon: unable to resolve codec"
						+ "\nself=" + self.getClass().getName()
						+ "\nidentifier=" + identifier
						+ "\noperation=" + operation.getClass().getName()
						+ "\nbuf=" + buf.getClass().getName()
		);
	}

}