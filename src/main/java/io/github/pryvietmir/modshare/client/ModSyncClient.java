package io.github.pryvietmir.modshare.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.pryvietmir.modshare.ModShareApplier;
import io.github.pryvietmir.modshare.Modshare;
import io.github.pryvietmir.modshare.common.LocalMod;
import io.github.pryvietmir.modshare.common.Manifest;
import io.github.pryvietmir.modshare.common.ModScanner;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Talks to a server's ModShare HTTP endpoint and prepares the changes to the mods folder. */
public final class ModSyncClient {
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9._+\\-()\\[\\] ]{1,128}");
    private static final int READ_TIMEOUT_MS = 30_000;

    private final HttpClient http;
    private final URI baseUri;
    private final Duration timeout;

    public ModSyncClient(URI baseUri, Duration timeout) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Base URI from the server's {@code "modshare"} ping info: its public URL, or the server's own address with the advertised port. */
    public static URI baseUri(JsonObject info, InetSocketAddress server) {
        if (info.get("url") instanceof JsonPrimitive url && !url.getAsString().isBlank()) {
            String value = url.getAsString().trim();
            URI uri = URI.create(value.endsWith("/") ? value : value + "/");
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("Unsupported ModShare URL " + value);
            }
            return uri.resolve("modshare/");
        }
        return baseUri(server, info.get("port").getAsInt());
    }

    public static URI baseUri(InetSocketAddress server, int port) {
        String host = server.getAddress() != null ? server.getAddress().getHostAddress() : server.getHostString();
        if (host.contains(":")) host = "[" + host + "]"; // IPv6 literal
        return URI.create("http://" + host + ":" + port + "/modshare/");
    }

    public Manifest fetchManifest() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("manifest")).timeout(timeout).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + request.uri());
        return Manifest.parse(response.body());
    }

    /** Progress of {@link #download}, read by the screen from the render thread. */
    public static final class Progress {
        public final AtomicLong bytesDone = new AtomicLong();
        public final AtomicBoolean cancelled = new AtomicBoolean();
        public volatile long bytesTotal;
        public volatile int fileIndex;
        public volatile String currentFile = "";
    }

    /** Downloads every jar of the plan into the staging folder, verifying size and hash. */
    public void download(SyncPlan plan, Path stagingDir, Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(stagingDir);
        progress.bytesTotal = plan.downloadSize();
        List<Manifest.Entry> entries = plan.toDownload();
        for (int i = 0; i < entries.size(); i++) {
            Manifest.Entry entry = entries.get(i);
            progress.fileIndex = i + 1;
            progress.currentFile = entry.file();
            downloadOne(entry, stagingDir.resolve(entry.sha256() + ".jar"), progress);
        }
    }

    private void downloadOne(Manifest.Entry entry, Path target, Progress progress) throws IOException, InterruptedException {
        // HttpURLConnection has a per-read timeout, so a stalled transfer fails instead of freezing the screen forever
        HttpURLConnection connection = (HttpURLConnection) baseUri.resolve("file/" + entry.sha256()).toURL().openConnection();
        connection.setConnectTimeout((int) timeout.toMillis());
        connection.setReadTimeout(READ_TIMEOUT_MS);
        if (connection.getResponseCode() != 200) {
            connection.disconnect();
            throw new IOException("HTTP " + connection.getResponseCode() + " while downloading " + entry.file());
        }

        MessageDigest digest = ModScanner.newDigest();
        long written = 0;
        try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (progress.cancelled.get()) throw new InterruptedException("Download cancelled");
                written += read;
                if (written > entry.size()) throw new IOException(entry.file() + " is larger than announced");
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                progress.bytesDone.addAndGet(read);
            }
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(target);
            throw e;
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (written != entry.size() || !actual.equals(entry.sha256())) {
            Files.deleteIfExists(target);
            throw new IOException(entry.file() + " failed the integrity check");
        }
    }

    /**
     * Writes the operations for {@link ModShareApplier} and starts it in a separate process.
     * It waits for the game to exit, then removes the old jars and moves the downloaded ones into the mods folder.
     *
     * @param restart how to start the game again if {@link GameRestarter#requestRestart()} is called; null to never restart
     */
    public static void scheduleApply(SyncPlan plan, Path stagingDir, boolean backupRemoved, @Nullable GameRestarter.Restart restart) throws IOException {
        Path workDir = workDir();
        Path modsDir = ModScanner.modsDir();
        Path backupDir = workDir.resolve("backup").resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));

        List<String> operations = new ArrayList<>();
        Set<Path> freedPaths = new HashSet<>();
        for (LocalMod mod : plan.toRemove()) {
            freedPaths.add(mod.path());
            operations.add(backupRemoved
                    ? "MOVE\t" + mod.path() + "\t" + backupDir.resolve(mod.fileName())
                    : "DELETE\t" + mod.path());
        }
        Set<String> usedNames = new HashSet<>();
        for (Manifest.Entry entry : plan.toDownload()) {
            String name = safeFileName(entry);
            Path target = modsDir.resolve(name).toAbsolutePath().normalize();
            if ((Files.exists(target) && !freedPaths.contains(target)) || !usedNames.add(name.toLowerCase(Locale.ROOT))) {
                name = entry.sha256().substring(0, 8) + "-" + name;
                usedNames.add(name.toLowerCase(Locale.ROOT));
                target = modsDir.resolve(name).toAbsolutePath().normalize();
            }
            operations.add("MOVE\t" + stagingDir.resolve(entry.sha256() + ".jar").toAbsolutePath() + "\t" + target);
        }

        Path operationsFile = workDir.resolve("pending-operations.txt");
        Files.write(operationsFile, operations, StandardCharsets.UTF_8);
        GameRestarter.clearRestartRequest();
        launchApplier(workDir, operationsFile, restart);
    }

    private static void launchApplier(Path workDir, Path operationsFile, @Nullable GameRestarter.Restart restart) throws IOException {
        Path modJar = ModList.get().getModFileById(Modshare.MODID).getFile().getFilePath();
        Path classpath = modJar;
        if (Files.isRegularFile(modJar)) {
            // Run from a copy: the original jar may itself be replaced by a newer ModShare
            classpath = workDir.resolve("applier.jar");
            Files.copy(modJar, classpath, StandardCopyOption.REPLACE_EXISTING);
        }
        String java = ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());

        Process applier = new ProcessBuilder(java, "-cp", classpath.toString(), ModShareApplier.class.getName(),
                Long.toString(ProcessHandle.current().pid()), operationsFile.toString())
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(workDir.resolve("applier.log").toFile())
                .start();
        // The restart command holds the access token, so it goes through stdin instead of a file or the arguments
        try (OutputStream stdin = applier.getOutputStream()) {
            if (restart != null) {
                stdin.write(ModShareApplier.encodeRestart(restart.workingDir(), restart.command()));
            }
        }
        Modshare.LOGGER.info("ModShare: mod changes will be applied when the game exits ({})", operationsFile);
    }

    public static Path workDir() throws IOException {
        return Files.createDirectories(FMLPaths.GAMEDIR.get().resolve("modshare"));
    }

    /** The server controls the file name, so never let it escape the mods folder. */
    private static String safeFileName(Manifest.Entry entry) {
        String name = entry.file().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (!SAFE_FILE_NAME.matcher(name).matches() || name.startsWith(".") || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return entry.sha256() + ".jar";
        }
        return name;
    }
}
