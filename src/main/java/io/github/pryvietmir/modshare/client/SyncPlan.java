package io.github.pryvietmir.modshare.client;

import io.github.pryvietmir.modshare.Modshare;
import io.github.pryvietmir.modshare.common.LocalMod;
import io.github.pryvietmir.modshare.common.Manifest;
import io.github.pryvietmir.modshare.common.ModMatcher;
import io.github.pryvietmir.modshare.config.ClientConfig.RemoveMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What has to change in the local mods folder to match the server. */
public record SyncPlan(List<Manifest.Entry> toDownload, List<LocalMod> toRemove) {

    public boolean isEmpty() {
        return toDownload.isEmpty() && toRemove.isEmpty();
    }

    public long downloadSize() {
        return toDownload.stream().mapToLong(Manifest.Entry::size).sum();
    }

    /**
     * Mods are compared by file hash, so renamed jars still count as present.
     * A local jar that the server does not have is removed if:
     * <ul>
     *     <li>the server provides another version of one of its mods (keeping both would crash the game), or</li>
     *     <li>it is neither ModShare itself nor in {@code keepMods}, and {@code removeMode} is ALL,
     *     or AUTO and the jar contains a mod from {@code neededOnBothSides} (it would block joining).</li>
     * </ul>
     */
    public static SyncPlan compute(Manifest manifest, List<LocalMod> local, RemoveMode removeMode,
                                   Collection<? extends String> keepMods, Set<String> neededOnBothSides) {
        Set<String> localHashes = new HashSet<>();
        for (LocalMod mod : local) localHashes.add(mod.sha256());

        Set<String> remoteHashes = new HashSet<>();
        Set<String> remoteModIds = new HashSet<>();
        List<Manifest.Entry> toDownload = new ArrayList<>();
        for (Manifest.Entry entry : manifest.mods()) {
            remoteModIds.addAll(entry.modIds());
            if (remoteHashes.add(entry.sha256()) && !localHashes.contains(entry.sha256())) toDownload.add(entry);
        }

        List<LocalMod> toRemove = new ArrayList<>();
        for (LocalMod mod : local) {
            if (remoteHashes.contains(mod.sha256())) continue;
            boolean replacedByServer = !Collections.disjoint(mod.modIds(), remoteModIds);
            boolean protectedMod = mod.modIds().contains(Modshare.MODID) || ModMatcher.matches(keepMods, mod.fileName(), mod.modIds());
            boolean extra = switch (removeMode) {
                case ALL -> true;
                case AUTO -> !Collections.disjoint(mod.modIds(), neededOnBothSides);
                case NONE -> false;
            };
            if (replacedByServer || (extra && !protectedMod)) toRemove.add(mod);
        }
        return new SyncPlan(List.copyOf(toDownload), List.copyOf(toRemove));
    }
}
