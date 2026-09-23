package io.github.pryvietmir.modshare.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ClientConfig {
    public enum RemoveMode {
        /** Remove only mods that would stop you from joining: mods with synced content or required network channels the server does not have */
        AUTO,
        /** Remove every mod the server does not have, except 'keepMods' */
        ALL,
        /** Never remove mods (a different version of a server mod is still replaced) */
        NONE
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Check the server's mod list before joining a server")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue CONFIRM_CHANGES = BUILDER
            .comment("Always show the list of mods to download and remove and ask before changing anything, even on trusted servers.",
                    "When false, you are asked only the first time a server wants to install mods (see 'trustedServers').")
            .define("confirmChanges", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRUSTED_SERVERS = BUILDER
            .comment("Servers allowed to install and remove mods without asking, as \"host:port\". A server is added when you press \"Trust and apply\".",
                    "Downloaded mods run code on your computer: remove a server from this list if you no longer trust it.")
            .defineListAllowEmpty("trustedServers", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.IntValue TIMEOUT_MS = BUILDER
            .comment("How long to wait for the server before joining normally, in milliseconds")
            .defineInRange("timeoutMs", 3000, 250, 60000);

    public static final ModConfigSpec.IntValue FALLBACK_HTTP_PORT = BUILDER
            .comment("ModShare servers announce their download port in the server list ping. Some proxies (Velocity, BungeeCord) hide it;",
                    "for them, set the server's 'httpPort' here. 0 disables the fallback.")
            .defineInRange("fallbackHttpPort", 0, 0, 65535);

    public static final ModConfigSpec.EnumValue<RemoveMode> REMOVE_MODE = BUILDER
            .comment("AUTO - remove only mods the server does not have that would block joining (they add blocks, items, entities or required network channels).",
                    "       Client-only mods like Distant Horizons, Sodium or JEI are kept automatically.",
                    "ALL  - remove every mod the server does not have, except 'keepMods'.",
                    "NONE - never remove mods.")
            .defineEnum("removeMode", RemoveMode.AUTO);

    public static final ModConfigSpec.BooleanValue BACKUP_REMOVED_MODS = BUILDER
            .comment("Move removed mods to the 'modshare/backup' folder instead of deleting them")
            .define("backupRemovedMods", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> KEEP_MODS = BUILDER
            .comment("Mods that are never removed, in any mode.",
                    "Each entry matches a mod id (e.g. \"jei\") or the beginning of a jar file name (e.g. \"xaeros_minimap\"), case-insensitive.",
                    "A kept mod is still replaced if the server provides a different version of it.")
            .defineListAllowEmpty("keepMods", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
