package io.github.pryvietmir.modshare;

import com.mojang.logging.LogUtils;
import io.github.pryvietmir.modshare.config.ClientConfig;
import io.github.pryvietmir.modshare.config.ServerConfig;
import io.github.pryvietmir.modshare.server.ServerHooks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Modshare.MODID)
public class Modshare {
    public static final String MODID = "modshare";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Modshare(IEventBus modEventBus, ModContainer modContainer, Dist dist) {
        // Dedicated servers share their mods over HTTP, and so do singleplayer worlds opened to LAN
        modContainer.registerConfig(ModConfig.Type.COMMON, ServerConfig.SPEC, "modshare-server.toml");
        NeoForge.EVENT_BUS.addListener(ServerHooks::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerHooks::onServerStopping);
        if (dist.isClient()) {
            // The client syncs its mods folder before joining a server (see ConnectScreenMixin)
            modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "modshare-client.toml");
        }
    }
}
