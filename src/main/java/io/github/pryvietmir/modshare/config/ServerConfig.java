package io.github.pryvietmir.modshare.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class ServerConfig {
    public enum ShareMode {
        /** Share only the mods a client needs to join: mods with synced content or required network channels, plus their dependencies */
        AUTO,
        /** Share every jar in the mods folder */
        ALL
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Share the server's mods with connecting ModShare clients.",
                    "Applies to dedicated servers and to singleplayer worlds opened to LAN.")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue HTTP_PORT = BUILDER
            .comment("TCP port the ModShare HTTP server listens on. Use any free port your host gives you.",
                    "Clients learn it automatically from the server list ping, no client setup is needed.")
            .defineInRange("httpPort", 25580, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> PUBLIC_URL = BUILDER
            .comment("Address clients should download from, if it differs from the server's address and 'httpPort'",
                    "(for example when the host maps the port to another external port, or behind a reverse proxy).",
                    "Example: \"http://play.example.com:8123\". Leave empty to use the server's address and 'httpPort'.")
            .define("publicUrl", "");

    public static final ModConfigSpec.EnumValue<ShareMode> SHARE_MODE = BUILDER
            .comment("AUTO - share only the mods clients need to join (mods with blocks, items, entities or required network channels, and their dependencies).",
                    "       Server-only mods (backups, permissions, world generation tweaks, ...) are detected and kept to the server.",
                    "ALL  - share every jar in the mods folder except 'excludedMods'.")
            .defineEnum("shareMode", ShareMode.AUTO);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> FORCE_SHARED_MODS = BUILDER
            .comment("Mods that are always shared, even if AUTO considers them server-only (e.g. recommended client mods of your pack).",
                    "Each entry matches a mod id (e.g. \"jei\") or the beginning of a jar file name, case-insensitive.")
            .defineListAllowEmpty("forceSharedMods", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN_MODS = BUILDER
            .comment("Mods clients install silently, without showing them in the list of changes (e.g. libraries).",
                    "They are always shared. Clients install them silently only from servers they trust:",
                    "the first time, they are mentioned on the confirmation screen and the player has to trust the server.",
                    "Each entry matches a mod id (e.g. \"kotlinforforge\") or the beginning of a jar file name, case-insensitive.")
            .defineListAllowEmpty("hiddenMods", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_MODS = BUILDER
            .comment("Mods that are never shared and do not appear in the mod list clients receive (e.g. server-only mods AUTO misses).",
                    "Takes priority over every other option.",
                    "Each entry matches a mod id (e.g. \"ftbbackups2\") or the beginning of a jar file name (e.g. \"ftb-backups\"), case-insensitive.",
                    "Note: excluding a mod that adds blocks, items or required network channels means clients cannot join without it.")
            .defineListAllowEmpty("excludedMods", List.of(), () -> "", o -> o instanceof String);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {}
}
