package io.github.pryvietmir.modshare.client;

import io.github.pryvietmir.modshare.Modshare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Joins the server again after an automatic restart. The server is remembered in a file instead of being passed
 * to the launcher ({@code --server}) or the game ({@code --quickPlayMultiplayer}): both parse "host:port" strings
 * and break on IPv6 addresses such as Radmin VPN ones.
 */
@EventBusSubscriber(modid = Modshare.MODID, value = Dist.CLIENT)
public final class RejoinOnStartup {
    /** Ignore a remembered server if the game took longer than this to come back */
    private static final long MAX_AGE_MS = 10 * 60 * 1000;

    private static boolean checked;

    private RejoinOnStartup() {}

    public static void remember(ServerAddress address, ServerData serverData) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("time", Long.toString(System.currentTimeMillis()));
        properties.setProperty("host", address.getHost());
        properties.setProperty("port", Integer.toString(address.getPort()));
        String ip = serverData.ip != null ? serverData.ip : address.getHost() + ":" + address.getPort();
        properties.setProperty("ip", ip);
        properties.setProperty("name", serverData.name != null ? serverData.name : ip);
        try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            properties.store(writer, "ModShare: server to join after the restart");
        }
    }

    private static Path file() throws IOException {
        return ModSyncClient.workDir().resolve("rejoin.properties");
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // Wait until the loading overlay is gone and the main menu is shown
        if (checked || minecraft.getOverlay() != null || !(minecraft.screen instanceof TitleScreen titleScreen)) return;
        checked = true;

        Properties properties = new Properties();
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            Files.delete(file);

            long age = System.currentTimeMillis() - Long.parseLong(properties.getProperty("time"));
            if (age < 0 || age > MAX_AGE_MS) return;

            ServerAddress address = new ServerAddress(properties.getProperty("host"), Integer.parseInt(properties.getProperty("port")));
            String ip = properties.getProperty("ip");
            ServerList serverList = new ServerList(minecraft);
            serverList.load();
            ServerData serverData = serverList.get(ip);
            if (serverData == null) serverData = new ServerData(properties.getProperty("name"), ip, ServerData.Type.OTHER);

            Modshare.LOGGER.info("ModShare: joining {} again after the restart", ip);
            ConnectScreen.startConnecting(titleScreen, minecraft, address, serverData, false, null);
        } catch (IOException | RuntimeException e) {
            Modshare.LOGGER.warn("ModShare: could not join the server again after the restart", e);
        }
    }
}
