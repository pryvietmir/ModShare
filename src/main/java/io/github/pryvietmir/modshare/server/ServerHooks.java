package io.github.pryvietmir.modshare.server;

import com.google.gson.JsonObject;
import io.github.pryvietmir.modshare.Modshare;
import io.github.pryvietmir.modshare.config.ServerConfig;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the HTTP server while mods are being shared: for the whole life of a dedicated server,
 * and for a singleplayer world from the moment it is opened to LAN until it is closed.
 */
public final class ServerHooks {
    /** The server whose mods are shared; guards against a world closing while sharing is still starting */
    private static MinecraftServer activeServer;
    private static ModShareHttpServer httpServer;
    private static volatile JsonObject advertisement;

    private ServerHooks() {}

    public static void onServerStarted(ServerStartedEvent event) {
        // Singleplayer worlds share their mods only once opened to LAN, see onPublishedToLan
        if (event.getServer().isDedicatedServer()) startSharing(event.getServer());
    }

    /** Called by IntegratedServerMixin when a singleplayer world is opened to LAN. */
    public static void onPublishedToLan(MinecraftServer server) {
        synchronized (ServerHooks.class) {
            activeServer = server;
        }
        // Hashing every mod can take a moment: keep it off the render thread
        Thread thread = new Thread(() -> startSharing(server), "ModShare startup");
        thread.setDaemon(true);
        thread.start();
    }

    private static synchronized void startSharing(MinecraftServer server) {
        if (server.isDedicatedServer()) activeServer = server;
        if (activeServer != server || httpServer != null || !ServerConfig.ENABLED.get()) return;

        int port = ServerConfig.HTTP_PORT.get();
        try {
            httpServer = ModShareHttpServer.start(port, ServerConfig.SHARE_MODE.get(),
                    ServerConfig.FORCE_SHARED_MODS.get(), ServerConfig.HIDDEN_MODS.get(), ServerConfig.EXCLUDED_MODS.get());
        } catch (Exception e) {
            Modshare.LOGGER.error("ModShare: failed to start the HTTP server on port {}", port, e);
            return;
        }
        JsonObject info = new JsonObject();
        info.addProperty("port", port);
        String publicUrl = ServerConfig.PUBLIC_URL.get().trim();
        if (!publicUrl.isEmpty()) info.addProperty("url", publicUrl);
        advertisement = info;
    }

    public static synchronized void onServerStopping(ServerStoppingEvent event) {
        activeServer = null;
        advertisement = null;
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    /** The "modshare" object added to the server list ping, or null while nothing is shared. Called from netty threads. */
    public static @Nullable JsonObject advertisement() {
        return advertisement;
    }
}
