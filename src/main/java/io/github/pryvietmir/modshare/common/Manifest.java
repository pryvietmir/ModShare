package io.github.pryvietmir.modshare.common;

import com.google.gson.Gson;

import java.util.List;
import java.util.regex.Pattern;

/** The list of mods a server shares, served as JSON at {@code /modshare/manifest}. */
public record Manifest(int formatVersion, List<Entry> mods) {
    public static final int FORMAT_VERSION = 1;
    public static final Gson GSON = new Gson();
    public static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public record Entry(String file, String sha256, long size, List<String> modIds) {
        public boolean isValid() {
            return file != null && sha256 != null && SHA256.matcher(sha256).matches() && size >= 0 && modIds != null;
        }
    }

    public static Manifest parse(String json) {
        Manifest manifest = GSON.fromJson(json, Manifest.class);
        if (manifest == null || manifest.mods() == null) throw new IllegalArgumentException("Empty manifest");
        if (manifest.formatVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported manifest version " + manifest.formatVersion());
        }
        for (Entry entry : manifest.mods()) {
            if (entry == null || !entry.isValid()) throw new IllegalArgumentException("Malformed manifest entry: " + entry);
        }
        return manifest;
    }
}
