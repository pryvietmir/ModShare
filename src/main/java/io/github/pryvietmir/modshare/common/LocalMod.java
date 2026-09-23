package io.github.pryvietmir.modshare.common;

import java.nio.file.Path;
import java.util.Set;

/**
 * A jar in the mods folder.
 *
 * @param modIds ids of the mods loaded from this jar; empty if FML did not load it
 */
public record LocalMod(Path path, String fileName, String sha256, long size, Set<String> modIds) {}
