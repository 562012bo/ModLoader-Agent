package ankhangbo.fabricloader.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns Paper's {@code DiscardedPayload("minecraft:register" | "minecraft:unregister", data)} into Fabric
 * networking's {@code RegistrationPayload}. Called from the head of
 * {@code ClientboundCustomPayloadPacket}'s constructor (see
 * {@link ankhangbo.fabricloader.asm.ClientboundCustomPayloadFabricRegisterTransformer}).
 *
 * <p>Both sides use the same wire format (channel ids separated by a 0 byte), so the client receives
 * exactly the bytes Paper meant to send - only the Java type changes, which is what Fabric's codec for
 * that channel id insists on. JDK-only signature: this class is in the agent classloader and reaches
 * Minecraft/Fabric classes reflectively through the payload's own (Knot) classloader.
 */
public final class FabricPayloadBridge {
	private static final Logger LOGGER = Logger.getLogger("ankhangbo.fabricloader.compat.FabricPayloadBridge");
	private static final String DISCARDED = "net.minecraft.network.protocol.common.custom.DiscardedPayload";

	private static volatile Handles handles;
	private static volatile boolean unavailable;
	private static volatile boolean logged;

	private FabricPayloadBridge() {
	}

	private static final class Handles {
		final Method discardedId;
		final Method discardedData;
		final Method tryParse;
		final Constructor<?> ctor;
		final Object register;
		final Object unregister;

		Handles(ClassLoader cl, Class<?> discarded) throws ReflectiveOperationException {
			this.discardedId = discarded.getMethod("id");
			this.discardedData = discarded.getMethod("data");
			Class<?> idC = Class.forName("net.minecraft.resources.Identifier", false, cl);
			this.tryParse = idC.getMethod("tryParse", String.class);
			Class<?> rp = Class.forName("net.fabricmc.fabric.impl.networking.RegistrationPayload", true, cl);
			Class<?> typeC = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload$Type", false, cl);
			this.ctor = rp.getConstructor(typeC, List.class);
			this.register = rp.getField("REGISTER").get(null);
			this.unregister = rp.getField("UNREGISTER").get(null);
		}
	}

	/** @return the payload to actually put in the packet (the argument itself unless it needs converting). */
	public static Object convert(Object payload) {
		if (payload == null || unavailable) {
			return payload;
		}
		Class<?> c = payload.getClass();
		if (!DISCARDED.equals(c.getName())) {
			return payload;
		}
		try {
			Handles h = handles;
			if (h == null) {
				try {
					h = new Handles(c.getClassLoader(), c);
				} catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException e) {
					unavailable = true; // no Fabric networking on this server: nothing to convert for
					return payload;
				}
				handles = h;
			}
			String id = String.valueOf(h.discardedId.invoke(payload));
			Object type;
			if (id.equals("minecraft:register")) {
				type = h.register;
			} else if (id.equals("minecraft:unregister")) {
				type = h.unregister;
			} else {
				return payload;
			}
			byte[] data = (byte[]) h.discardedData.invoke(payload);
			List<Object> channels = new ArrayList<>();
			int start = 0;
			for (int i = 0; i <= data.length; i++) {
				if (i == data.length || data[i] == 0) {
					if (i > start) {
						Object ident = h.tryParse.invoke(null, new String(data, start, i - start, StandardCharsets.UTF_8));
						if (ident != null) {
							channels.add(ident);
						}
					}
					start = i + 1;
				}
			}
			if (!logged) {
				logged = true;
				LOGGER.info("Converted Paper's " + id + " payload into Fabric's RegistrationPayload ("
						+ channels.size() + " channel(s)); further conversions are silent");
			}
			return h.ctor.newInstance(type, Collections.unmodifiableList(channels));
		} catch (Throwable t) {
			LOGGER.log(Level.WARNING, "Could not convert a minecraft:register payload; sending it unchanged", t);
			return payload;
		}
	}
}