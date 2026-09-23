package io.github.pryvietmir.modshare;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Standalone program that applies pending mod changes once the game has exited, then starts the game again
 * if the game asked for it (a {@code restart.flag} file next to the operations file).
 * Loaded jars are locked on Windows, so they can only be removed after the game process ends.
 * <p>
 * Must not reference any Minecraft or NeoForge class: it runs in its own JVM with only the ModShare jar on the classpath.
 * <p>
 * Usage: {@code java -cp modshare.jar io.github.pryvietmir.modshare.ModShareApplier <game pid> <operations file>}
 * <p>
 * Operations file, one per line, tab separated: {@code DELETE <path>} or {@code MOVE <from> <to>}.
 */
public final class ModShareApplier {
    private static final int ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 250;
    private static final long RESTART_DELAY_MS = 3000;

    private ModShareApplier() {}

    public static void main(String[] args) throws Exception {
        long gamePid = Long.parseLong(args[0]);
        Path operations = Path.of(args[1]);
        // Optional restart command from the game; stdin is closed right after it is written
        List<String> restart = decodeRestart(System.in.readAllBytes());

        System.out.println("Waiting for the game (pid " + gamePid + ") to exit");
        var game = ProcessHandle.of(gamePid);
        if (game.isPresent()) game.get().onExit().get();

        boolean ok = apply(operations);
        System.out.println(ok ? "All changes applied" : "Some changes failed, see above");

        Path restartFlag = operations.resolveSibling("restart.flag");
        boolean restartRequested = Files.deleteIfExists(restartFlag);
        if (restartRequested && restart.isEmpty()) {
            System.out.println("Restart requested, but the game's command line is unknown");
        } else if (restartRequested && !ok) {
            System.out.println("Not restarting the game because some changes failed");
        } else if (restartRequested) {
            // Give the launcher a moment to notice that the game has exited before asking it to start the instance again
            Thread.sleep(RESTART_DELAY_MS);
            // Never print the command: it may contain the player's access token
            new ProcessBuilder(restart.subList(1, restart.size()))
                    .directory(new File(restart.get(0)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            System.out.println("Game restarted");
        }
        System.exit(ok ? 0 : 1);
    }

    /** Working directory followed by the command, each terminated by a NUL character. */
    public static byte[] encodeRestart(String workingDir, List<String> command) {
        StringBuilder builder = new StringBuilder(workingDir).append('\0');
        for (String arg : command) builder.append(arg).append('\0');
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> decodeRestart(byte[] bytes) {
        if (bytes.length == 0) return List.of();
        // Limit -1 keeps empty arguments; only the empty string after the final terminator is dropped
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\0", -1);
        List<String> result = List.of(parts).subList(0, parts.length - 1);
        return result.size() >= 2 ? result : List.of();
    }

    public static boolean apply(Path operations) throws IOException {
        List<String> lines = Files.readAllLines(operations, StandardCharsets.UTF_8);
        boolean ok = true;
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t");
            ok &= switch (parts[0]) {
                case "DELETE" -> retry(line, () -> Files.deleteIfExists(Path.of(parts[1])));
                case "MOVE" -> retry(line, () -> {
                    Path to = Path.of(parts[2]);
                    Files.createDirectories(to.getParent());
                    Files.move(Path.of(parts[1]), to, StandardCopyOption.REPLACE_EXISTING);
                });
                default -> {
                    System.out.println("Unknown operation: " + line);
                    yield false;
                }
            };
        }
        if (ok) Files.deleteIfExists(operations);
        return ok;
    }

    private interface IoAction {
        void run() throws IOException;
    }

    /** File locks can outlive the game process for a moment, so each operation is retried for a few seconds. */
    private static boolean retry(String description, IoAction action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                System.out.println("OK   " + description);
                return true;
            } catch (IOException e) {
                if (attempt >= ATTEMPTS) {
                    System.out.println("FAIL " + description + ": " + e);
                    return false;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
}
