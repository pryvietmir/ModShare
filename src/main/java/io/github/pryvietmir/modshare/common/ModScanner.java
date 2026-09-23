package io.github.pryvietmir.modshare.common;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Lists and hashes the jars in the mods folder. */
public final class ModScanner {
    private record CachedHash(long size, long lastModified, String sha256) {}

    private static final Map<Path, CachedHash> HASH_CACHE = new ConcurrentHashMap<>();

    private ModScanner() {}

    public static Path modsDir() {
        return FMLPaths.MODSDIR.get();
    }

    public static List<LocalMod> scanModsDir() throws IOException {
        Map<Path, Set<String>> idsByPath = loadedModIds();
        List<LocalMod> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(modsDir())) {
            for (Path path : files.filter(ModScanner::isJar).sorted().toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                result.add(new LocalMod(normalized, path.getFileName().toString(), sha256(normalized),
                        Files.size(normalized), idsByPath.getOrDefault(normalized, Set.of())));
            }
        }
        return result;
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** Mod ids per jar in the mods folder; jar-in-jar mods count towards the jar that contains them. */
    private static Map<Path, Set<String>> loadedModIds() {
        Map<Path, Set<String>> ids = new HashMap<>();
        for (IModFileInfo info : ModList.get().getModFiles()) {
            IModFile outermost = info.getFile();
            while (outermost.getDiscoveryAttributes().parent() != null) outermost = outermost.getDiscoveryAttributes().parent();
            Path path = outermost.getFilePath();
            if (path.getFileSystem() != FileSystems.getDefault()) continue;
            Set<String> set = ids.computeIfAbsent(path.toAbsolutePath().normalize(), p -> new HashSet<>());
            for (IModInfo mod : info.getMods()) set.add(mod.getModId());
        }
        return ids;
    }

    public static String sha256(Path path) throws IOException {
        long size = Files.size(path);
        long lastModified = Files.getLastModifiedTime(path).toMillis();
        CachedHash cached = HASH_CACHE.get(path);
        if (cached != null && cached.size() == size && cached.lastModified() == lastModified) return cached.sha256();

        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        HASH_CACHE.put(path, new CachedHash(size, lastModified, hash));
        return hash;
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
