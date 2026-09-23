package io.github.pryvietmir.modshare.common;

import java.util.Collection;
import java.util.Locale;

/** Matches jars against the mod lists from the configs. */
public final class ModMatcher {
    private ModMatcher() {}

    /** True if any pattern equals one of the mod ids or is a prefix of the file name (case-insensitive). */
    public static boolean matches(Collection<? extends String> patterns, String fileName, Collection<String> modIds) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        for (String raw : patterns) {
            String pattern = raw.trim().toLowerCase(Locale.ROOT);
            if (pattern.isEmpty()) continue;
            if (modIds.contains(pattern) || lowerName.startsWith(pattern)) return true;
        }
        return false;
    }
}
