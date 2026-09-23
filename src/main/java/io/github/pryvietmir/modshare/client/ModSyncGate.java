package io.github.pryvietmir.modshare.client;

import io.github.pryvietmir.modshare.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;

/** Decides whether a connection attempt goes through {@link ModSyncScreen} first. Render thread only. */
public final class ModSyncGate {
    private static boolean bypassNextConnect;
    private static boolean restartPending;
    private static boolean canRestart;
    private static ServerAddress pendingAddress;
    private static ServerData pendingServer;

    private ModSyncGate() {}

    /** Called from ConnectScreenMixin at the start of every connection attempt. */
    public static boolean shouldIntercept(@Nullable TransferState transferState) {
        if (bypassNextConnect) {
            bypassNextConnect = false;
            return false;
        }
        // Server transfers happen mid-session, don't interrupt them
        return transferState == null && ClientConfig.ENABLED.get();
    }

    public static void connectDirectly(Screen parent, Minecraft minecraft, ServerAddress address, ServerData serverData, boolean quickPlay) {
        bypassNextConnect = true;
        ConnectScreen.startConnecting(parent, minecraft, address, serverData, quickPlay, null);
    }

    public static boolean isRestartPending() {
        return restartPending;
    }

    /** Whether ModShareApplier knows how to start the game again. */
    public static boolean canRestart() {
        return canRestart;
    }

    /** The server whose mods are waiting for the restart; null while nothing is pending. */
    public static @Nullable ServerAddress pendingAddress() {
        return pendingAddress;
    }

    public static @Nullable ServerData pendingServer() {
        return pendingServer;
    }

    public static void markRestartPending(boolean canRestartAutomatically, ServerAddress address, ServerData serverData) {
        restartPending = true;
        canRestart = canRestartAutomatically;
        pendingAddress = address;
        pendingServer = serverData;
    }
}
