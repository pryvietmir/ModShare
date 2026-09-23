package io.github.pryvietmir.modshare.server;

import com.google.gson.JsonObject;
import io.github.pryvietmir.modshare.Modshare;
import io.github.pryvietmir.modshare.config.ServerConfig;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.Nullable;

/** Starts and stops the HTTP server together with the dedicated server. */
public final class ServerHooks {
    private static ModShareHttpServer httpServer;
    private static volatile JsonObject advertisement;

    private ServerHooks() {}

    public static void onServerStarted(ServerStartedEvent event) {
        if (!ServerConfig.ENABLED.get()) return;
        int port = ServerConfig.HTTP_PORT.get();
        try {
            httpServer = ModShareHttpServer.start(port, ServerConfig.SHARE_MODE.get(),
                    ServerConfig.FORCE_SHARED_MODS.get(), ServerConfig.HIDDEN_MODS.get());
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

    public static void onServerStopping(ServerStoppingEvent event) {
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
