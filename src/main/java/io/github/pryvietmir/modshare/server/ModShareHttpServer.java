package io.github.pryvietmir.modshare.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.pryvietmir.modshare.Modshare;
import io.github.pryvietmir.modshare.common.ConnectionRequirements;
import io.github.pryvietmir.modshare.common.LocalMod;
import io.github.pryvietmir.modshare.common.Manifest;
import io.github.pryvietmir.modshare.common.ModMatcher;
import io.github.pryvietmir.modshare.common.ModScanner;
import io.github.pryvietmir.modshare.config.ServerConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the shared mods:
 * <ul>
 *     <li>{@code GET /modshare/manifest} - JSON {@link Manifest}</li>
 *     <li>{@code GET /modshare/file/<sha256>} - the jar with that hash</li>
 * </ul>
 * Files are addressed by hash only, so clients can never request anything outside the shared list.
 */
public final class ModShareHttpServer {
    private static final String FILE_PREFIX = "/modshare/file/";

    private final HttpServer server;
    private final ExecutorService executor;
    private final byte[] manifestJson;
    private final Map<String, LocalMod> filesByHash;

    private ModShareHttpServer(HttpServer server, ExecutorService executor, byte[] manifestJson, Map<String, LocalMod> filesByHash) {
        this.server = server;
        this.executor = executor;
        this.manifestJson = manifestJson;
        this.filesByHash = filesByHash;
    }

    public static ModShareHttpServer start(int port, ServerConfig.ShareMode shareMode,
                                           List<? extends String> forceSharedMods, List<? extends String> hiddenMods) throws IOException {
        Set<String> clientMods = ConnectionRequirements.withClientDependencies(ConnectionRequirements.modsNeededOnBothSides());
        Map<String, LocalMod> filesByHash = new HashMap<>();
        List<Manifest.Entry> entries = new ArrayList<>();
        for (LocalMod mod : ModScanner.scanModsDir()) {
            if (ModMatcher.matches(hiddenMods, mod.fileName(), mod.modIds())) {
                if (Collections.disjoint(mod.modIds(), clientMods)) {
                    Modshare.LOGGER.info("ModShare: hiding {} (hiddenMods)", mod.fileName());
                } else {
                    Modshare.LOGGER.warn("ModShare: hiding {} (hiddenMods), but clients need it to join - they will be refused unless they install it themselves", mod.fileName());
                }
                continue;
            }
            boolean shared = shareMode == ServerConfig.ShareMode.ALL
                    || !Collections.disjoint(mod.modIds(), clientMods)
                    || ModMatcher.matches(forceSharedMods, mod.fileName(), mod.modIds());
            if (!shared) {
                Modshare.LOGGER.info("ModShare: not sharing {} (server-only)", mod.fileName());
                continue;
            }
            if (filesByHash.putIfAbsent(mod.sha256(), mod) == null) {
                entries.add(new Manifest.Entry(mod.fileName(), mod.sha256(), mod.size(), List.copyOf(mod.modIds())));
            }
        }
        byte[] manifestJson = Manifest.GSON.toJson(new Manifest(Manifest.FORMAT_VERSION, entries)).getBytes(StandardCharsets.UTF_8);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "ModShare HTTP");
            thread.setDaemon(true);
            return thread;
        });
        ModShareHttpServer instance = new ModShareHttpServer(server, executor, manifestJson, filesByHash);
        server.createContext("/modshare/manifest", instance::handleManifest);
        server.createContext(FILE_PREFIX, instance::handleFile);
        server.setExecutor(executor);
        server.start();

        Modshare.LOGGER.info("ModShare: sharing {} mods on port {}", entries.size(), port);
        return instance;
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handleManifest(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, manifestJson.length);
            exchange.getResponseBody().write(manifestJson);
        }
    }

    private void handleFile(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String hash = exchange.getRequestURI().getPath().substring(FILE_PREFIX.length());
            LocalMod mod = filesByHash.get(hash);
            if (mod == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Path path = mod.path();
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, Files.size(path));
            try (OutputStream body = exchange.getResponseBody()) {
                Files.copy(path, body);
            }
        } catch (IOException e) {
            // Usually the client closed the connection mid-download
            Modshare.LOGGER.debug("ModShare: failed to send file", e);
        }
    }
}
