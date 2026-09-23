package io.github.pryvietmir.modshare.client;

import io.github.pryvietmir.modshare.Modshare;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Works out how to start the game again after ModShareApplier has replaced the mods
 * ({@link RejoinOnStartup} then joins the server again):
 * <ul>
 *     <li>Prism Launcher and its forks (ElyPrism / PineLauncher, MultiMC): ask the launcher itself with
 *     {@code --launch <instance>}, so it handles login, logs and the console as usual;</li>
 *     <li>other launchers: repeat the exact command line the game was started with.
 *     That command contains the player's access token: never log it or write it to disk.</li>
 * </ul>
 */
public final class GameRestarter {
    /** Main classes of Prism-family launchers; they pass the game arguments through stdin */
    private static final Set<String> PRISM_ENTRY_POINTS = Set.of("org.prismlauncher.EntryPoint", "org.multimc.EntryPoint");

    /** How to start the game again: a command run from a working directory. */
    public record Restart(String workingDir, List<String> command) {}

    private GameRestarter() {}

    public static Optional<Restart> restartCommand() {
        try {
            if (isPrismLauncher()) return prismRestart();

            List<String> command = readCommandLine();
            if (command.isEmpty()) return Optional.empty();
            return Optional.of(new Restart(System.getProperty("user.dir"), command));
        } catch (Exception e) {
            Modshare.LOGGER.warn("ModShare: could not work out how to restart the game, automatic restart is unavailable ({})", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static boolean isPrismLauncher() {
        String javaCommand = System.getProperty("sun.java.command", "");
        return PRISM_ENTRY_POINTS.stream().anyMatch(javaCommand::startsWith) || System.getenv("INST_ID") != null;
    }

    private static Optional<Restart> prismRestart() {
        // Prism starts the game directly, so the game's parent process is the launcher
        Optional<String> launcher = ProcessHandle.current().parent().flatMap(parent -> parent.info().command());
        String instanceId = prismInstanceId();
        if (launcher.isEmpty() || instanceId == null) {
            Modshare.LOGGER.info("ModShare: could not find the launcher or the instance id, automatic restart is unavailable");
            return Optional.empty();
        }
        Path launcherPath = Path.of(launcher.get());
        if (launcherPath.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("java")) {
            Modshare.LOGGER.info("ModShare: the game was started through a wrapper command, automatic restart is unavailable");
            return Optional.empty();
        }
        return Optional.of(new Restart(launcherPath.getParent().toString(),
                List.of(launcherPath.toString(), "--launch", instanceId)));
    }

    /** Prism exports INST_ID to the game; otherwise the instance id is the name of the folder holding instance.cfg. */
    private static @Nullable String prismInstanceId() {
        String fromEnvironment = System.getenv("INST_ID");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment;
        Path instanceDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().getParent();
        if (instanceDir != null && Files.isRegularFile(instanceDir.resolve("instance.cfg"))) return instanceDir.getFileName().toString();
        return null;
    }

    /** Tells ModShareApplier to start the game again once it has applied the changes. */
    public static void requestRestart() throws IOException {
        Files.writeString(restartFlag(), "");
    }

    public static void clearRestartRequest() throws IOException {
        Files.deleteIfExists(restartFlag());
    }

    private static Path restartFlag() throws IOException {
        return ModSyncClient.workDir().resolve("restart.flag");
    }

    private static List<String> readCommandLine() throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) return windowsCommandLine();
        Path procCmdline = Path.of("/proc/self/cmdline");
        if (Files.isReadable(procCmdline)) return splitNul(Files.readAllBytes(procCmdline));
        // Elsewhere (macOS) the exact arguments cannot be recovered; a guessed command could silently fail to start
        return List.of();
    }

    private static List<String> windowsCommandLine() throws IOException, InterruptedException {
        long pid = ProcessHandle.current().pid();
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8; (Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid + "').CommandLine")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        process.getOutputStream().close();
        // Read in the background: a blocking read would ignore the timeout if WMI hangs
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            return List.of();
        }
        String line;
        try {
            line = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
        } catch (ExecutionException | TimeoutException e) {
            return List.of();
        }
        if (line.startsWith("﻿")) line = line.substring(1);
        return splitWindowsCommandLine(line);
    }

    /** Splits a command line the way the Windows C runtime (CommandLineToArgvW) does. */
    static List<String> splitWindowsCommandLine(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean inToken = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                int backslashes = 0;
                while (i < line.length() && line.charAt(i) == '\\') {
                    backslashes++;
                    i++;
                }
                if (i < line.length() && line.charAt(i) == '"') {
                    current.append("\\".repeat(backslashes / 2));
                    if (backslashes % 2 == 1) current.append('"');
                    else i--; // the quote toggles quoting on the next iteration
                } else {
                    current.append("\\".repeat(backslashes));
                    i--;
                }
                inToken = true;
            } else if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                inToken = true;
            } else if ((c == ' ' || c == '\t') && !inQuotes) {
                if (inToken) {
                    args.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (inToken) args.add(current.toString());
        return args;
    }

    private static List<String> splitNul(byte[] bytes) {
        List<String> args = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                args.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start < bytes.length) args.add(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
        return args;
    }
}
