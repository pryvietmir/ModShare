package io.github.pryvietmir.modshare.common;

import io.github.pryvietmir.modshare.Modshare;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Works out which mods have to be installed on both the client and the server, using the same rules NeoForge uses
 * to accept or refuse a connection: a mod is needed on both sides if it owns entries in a synced registry
 * (blocks, items, entities, ...) or registers a non-optional network payload.
 * <p>
 * Everything else - Distant Horizons, Sodium, JEI on the client, backup or permission mods on the server -
 * does not affect joining and is left alone.
 */
public final class ConnectionRequirements {
    private static final Set<String> BUILT_IN_NAMESPACES = Set.of("minecraft", "neoforge", "c");

    private ConnectionRequirements() {}

    /** Ids of the loaded mods that the other side of a connection must also have. */
    public static Set<String> modsNeededOnBothSides() {
        Set<String> namespaces = new HashSet<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
            if (!registry.doesSync()) continue;
            for (ResourceLocation key : registry.keySet()) namespaces.add(key.getNamespace());
        }
        namespaces.addAll(requiredPayloadNamespaces());
        namespaces.removeAll(BUILT_IN_NAMESPACES);
        return namespaces;
    }

    /** The given mods plus everything they require on the client, transitively. */
    public static Set<String> withClientDependencies(Set<String> modIds) {
        Map<String, IModInfo> modsById = new HashMap<>();
        for (IModInfo mod : ModList.get().getMods()) modsById.put(mod.getModId(), mod);

        Set<String> result = new HashSet<>(modIds);
        Deque<String> queue = new ArrayDeque<>(modIds);
        while (!queue.isEmpty()) {
            IModInfo mod = modsById.get(queue.pop());
            if (mod == null) continue;
            for (IModInfo.ModVersion dependency : mod.getDependencies()) {
                if (dependency.getType() == IModInfo.DependencyType.REQUIRED
                        && dependency.getSide() != IModInfo.DependencySide.SERVER
                        && result.add(dependency.getModId())) {
                    queue.add(dependency.getModId());
                }
            }
        }
        return result;
    }

    /** NeoForge keeps payload registrations private, so they are read reflectively. */
    private static Set<String> requiredPayloadNamespaces() {
        Set<String> namespaces = new HashSet<>();
        try {
            Field field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
            field.setAccessible(true);
            Map<?, ?> byProtocol = (Map<?, ?>) field.get(null);
            for (Object registrations : byProtocol.values()) {
                for (Object value : ((Map<?, ?>) registrations).values()) {
                    PayloadRegistration<?> registration = (PayloadRegistration<?>) value;
                    if (!registration.optional()) namespaces.add(registration.id().getNamespace());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Modshare.LOGGER.warn("ModShare: could not read network payload registrations, only registries will be used to classify mods", e);
        }
        return namespaces;
    }
}
