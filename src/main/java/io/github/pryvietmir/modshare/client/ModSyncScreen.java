package io.github.pryvietmir.modshare.client;

import com.google.gson.JsonObject;
import io.github.pryvietmir.modshare.Modshare;
import io.github.pryvietmir.modshare.common.ConnectionRequirements;
import io.github.pryvietmir.modshare.common.LocalMod;
import io.github.pryvietmir.modshare.common.Manifest;
import io.github.pryvietmir.modshare.common.ModScanner;
import io.github.pryvietmir.modshare.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Shown instead of ConnectScreen: compares mods with the server and offers to sync them before joining. */
public class ModSyncScreen extends Screen {
    private enum State { CHECKING, CONFIRM, DOWNLOADING, RESTART_REQUIRED, ERROR }

    private static final int WHITE = 0xFFFFFF;
    private static final int GRAY = 0xAAAAAA;
    private static final int RED = 0xFF5555;
    private static final int YELLOW = 0xFFFF55;
    private static final int LINE_HEIGHT = 11;

    private final Screen parent;
    private final ServerAddress address;
    private final ServerData serverData;
    private final boolean quickPlay;

    private State state;
    private ModSyncClient client;
    private boolean started;
    private SyncPlan plan;
    /** Whether the server may change mods without asking; evaluated once when the plan is ready */
    private boolean trusted;
    /** The player's choice for each change of the plan (Manifest.Entry or LocalMod) */
    private final Map<Object, ModChangeList.Choice> choices = new HashMap<>();
    private ModSyncClient.Progress progress;
    private Component error = Component.empty();

    public ModSyncScreen(Screen parent, ServerAddress address, ServerData serverData, boolean quickPlay) {
        super(Component.translatable("modshare.screen.title"));
        this.parent = parent;
        this.address = address;
        this.serverData = serverData;
        this.quickPlay = quickPlay;
        this.state = ModSyncGate.isRestartPending() ? State.RESTART_REQUIRED : State.CHECKING;
    }

    @Override
    protected void init() {
        if (!started && state == State.CHECKING) {
            started = true;
            startCheck();
        }

        int y = height - 30;
        switch (state) {
            case CHECKING -> addButtons(y, Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()));
            case CONFIRM -> {
                int listTop = planHeaderBottom();
                addRenderableWidget(new ModChangeList(minecraft, width, Math.max(LINE_HEIGHT * 3, y - 6 - listTop), listTop, plan, choices));
                addButtons(y,
                        Button.builder(Component.translatable("modshare.button.select_all"), b -> choices.replaceAll((change, choice) -> ModChangeList.Choice.APPLY)),
                        Button.builder(Component.translatable(trusted ? "modshare.button.apply" : "modshare.button.trust_apply"), b -> applySelection()),
                        Button.builder(Component.translatable("modshare.button.join_anyway"), b -> {
                            rememberIgnored();
                            join();
                        }),
                        Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()));
            }
            case DOWNLOADING -> addButtons(y, Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()));
            case RESTART_REQUIRED -> addButtons(y,
                    ModSyncGate.canRestart()
                            ? Button.builder(Component.translatable("modshare.button.restart"), b -> restartNow())
                            : Button.builder(Component.translatable("modshare.button.quit"), b -> minecraft.stop()),
                    Button.builder(CommonComponents.GUI_BACK, b -> onClose()));
            case ERROR -> addButtons(y,
                    Button.builder(Component.translatable("modshare.button.join_anyway"), b -> join()),
                    Button.builder(CommonComponents.GUI_BACK, b -> onClose()));
        }
    }

    private void addButtons(int y, Button.Builder... builders) {
        int gap = 6;
        int width = Math.min(110, (this.width - 20 - (builders.length - 1) * gap) / builders.length);
        int x = (this.width - (builders.length * width + (builders.length - 1) * gap)) / 2;
        for (Button.Builder builder : builders) {
            addRenderableWidget(builder.bounds(x, y, width, 20).build());
            x += width + gap;
        }
    }

    /** Servers are remembered by the address the player connects to, e.g. "play.example.com:25565". */
    private String serverKey() {
        return address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }

    private boolean isTrusted() {
        String key = serverKey();
        return ClientConfig.TRUSTED_SERVERS.get().stream().anyMatch(entry -> entry.trim().equalsIgnoreCase(key));
    }

    private void trust() {
        List<String> trustedServers = new ArrayList<>(ClientConfig.TRUSTED_SERVERS.get());
        trustedServers.add(serverKey());
        ClientConfig.TRUSTED_SERVERS.set(trustedServers);
        ClientConfig.SPEC.save();
        trusted = true;
    }

    /** Applies only the changes left on "Apply", plus removals forced by selected downloads, and remembers "Always ignore". */
    private void applySelection() {
        rememberIgnored();
        List<Manifest.Entry> downloads = plan.toDownload().stream().filter(entry -> choices.get(entry) == ModChangeList.Choice.APPLY).toList();
        List<LocalMod> removals = plan.toRemove().stream()
                .filter(mod -> choices.get(mod) == ModChangeList.Choice.APPLY || SyncPlan.replacedBy(mod, downloads))
                .toList();
        SyncPlan selected = new SyncPlan(downloads, removals);
        if (selected.isEmpty()) {
            join();
            return;
        }
        if (!trusted) trust();
        plan = selected;
        startDownload();
    }

    /** Saves "Always ignore" choices: ignored downloads to ignoredDownloads, ignored removals to keepMods. */
    private void rememberIgnored() {
        List<String> ignoredDownloads = new ArrayList<>(ClientConfig.IGNORED_DOWNLOADS.get());
        List<String> keepMods = new ArrayList<>(ClientConfig.KEEP_MODS.get());
        List<Manifest.Entry> downloads = plan.toDownload().stream().filter(entry -> choices.get(entry) == ModChangeList.Choice.APPLY).toList();
        for (Manifest.Entry entry : plan.toDownload()) {
            if (choices.get(entry) == ModChangeList.Choice.IGNORE) ignoredDownloads.add(ignoreKey(entry.file(), entry.modIds()));
        }
        for (LocalMod mod : plan.toRemove()) {
            if (choices.get(mod) == ModChangeList.Choice.IGNORE && !SyncPlan.replacedBy(mod, downloads)) keepMods.add(ignoreKey(mod.fileName(), mod.modIds()));
        }
        if (ignoredDownloads.size() == ClientConfig.IGNORED_DOWNLOADS.get().size() && keepMods.size() == ClientConfig.KEEP_MODS.get().size()) return;
        ClientConfig.IGNORED_DOWNLOADS.set(ignoredDownloads);
        ClientConfig.KEEP_MODS.set(keepMods);
        ClientConfig.SPEC.save();
    }

    /** A mod is remembered by its mod id, so the choice survives version updates; jars without one by file name. */
    private static String ignoreKey(String fileName, Collection<String> modIds) {
        return modIds.isEmpty() ? fileName : modIds.stream().sorted().findFirst().orElseThrow();
    }

    private void setState(State state) {
        this.state = state;
        rebuildWidgets();
    }

    private record CheckResult(ModSyncClient client, SyncPlan plan) {}

    private void startCheck() {
        runAsync("ModShare check", () -> {
            int timeoutMs = ClientConfig.TIMEOUT_MS.get();
            Optional<InetSocketAddress> resolved = ServerNameResolver.DEFAULT.resolveAddress(address).map(ResolvedServerAddress::asInetSocketAddress);
            if (resolved.isEmpty()) return null; // ConnectScreen reports unknown hosts

            JsonObject info = null;
            try {
                info = StatusPing.queryModShare(address, resolved.get(), timeoutMs);
            } catch (IOException e) {
                Modshare.LOGGER.info("ModShare: ping of {} failed ({})", address.getHost(), e.toString());
            }
            int fallbackPort = ClientConfig.FALLBACK_HTTP_PORT.get();
            if (info == null && fallbackPort == 0) {
                Modshare.LOGGER.info("ModShare: {} does not share its mods (no ModShare on the server, or an older version that cannot share from a LAN world); joining without a mod check",
                        address.getHost() + ":" + address.getPort());
                return null;
            }

            URI baseUri = info != null ? ModSyncClient.baseUri(info, resolved.get()) : ModSyncClient.baseUri(resolved.get(), fallbackPort);
            ModSyncClient syncClient = new ModSyncClient(baseUri, Duration.ofMillis(timeoutMs));
            Manifest manifest;
            try {
                manifest = syncClient.fetchManifest();
            } catch (IOException e) {
                if (info != null) throw new IOException("Could not get the mod list from " + baseUri + ": " + e.getMessage(), e);
                Modshare.LOGGER.info("ModShare: no mod list at {} ({}), joining normally", baseUri, e.toString());
                return null;
            }

            List<LocalMod> local = ModScanner.scanModsDir();
            SyncPlan syncPlan = SyncPlan.compute(manifest, local, ClientConfig.REMOVE_MODE.get(), ClientConfig.KEEP_MODS.get(),
                    ClientConfig.IGNORED_DOWNLOADS.get(), ConnectionRequirements.modsNeededOnBothSides());
            return new CheckResult(syncClient, syncPlan);
        }, result -> {
            if (result == null || result.plan().isEmpty()) {
                join();
                return;
            }
            client = result.client();
            plan = result.plan();
            choices.clear();
            plan.toDownload().forEach(entry -> choices.put(entry, ModChangeList.Choice.APPLY));
            plan.toRemove().forEach(mod -> choices.put(mod, ModChangeList.Choice.APPLY));
            // Installing mods runs the server's code and removing them can wipe the mods folder: both need trust
            trusted = isTrusted();
            if (ClientConfig.CONFIRM_CHANGES.get() || !trusted) {
                setState(State.CONFIRM);
            } else {
                startDownload();
            }
        });
    }

    private void startDownload() {
        progress = new ModSyncClient.Progress();
        ModSyncClient.Progress current = progress;
        ServerAddress syncedAddress = address;
        ServerData syncedServer = serverData;
        setState(State.DOWNLOADING);
        runAsync("ModShare download", () -> {
            // Slow on Windows (PowerShell), so it runs before the download rather than between the cancel check and scheduling
            GameRestarter.Restart restart = GameRestarter.restartCommand().orElse(null);
            Path stagingDir = ModSyncClient.workDir().resolve("staging");
            client.download(plan, stagingDir, current);
            if (current.cancelled.get()) return null;
            ModSyncClient.scheduleApply(plan, stagingDir, ClientConfig.BACKUP_REMOVED_MODS.get(), restart);
            // Marked even if this screen was closed meanwhile: the changes are scheduled either way
            boolean canRestart = restart != null;
            minecraft.execute(() -> ModSyncGate.markRestartPending(canRestart, syncedAddress, syncedServer));
            return null;
        }, ignored -> setState(State.RESTART_REQUIRED));
    }

    private void restartNow() {
        // Rejoin the server the mods were synced with, which is not necessarily the one being joined now
        try {
            RejoinOnStartup.remember(ModSyncGate.pendingAddress(), ModSyncGate.pendingServer());
        } catch (IOException | RuntimeException e) {
            Modshare.LOGGER.warn("ModShare: could not remember the server to join after the restart", e);
        }
        try {
            GameRestarter.requestRestart();
        } catch (IOException e) {
            Modshare.LOGGER.error("ModShare: could not request a restart, the game will just close", e);
        }
        minecraft.stop();
    }

    private void join() {
        ModSyncGate.connectDirectly(parent, minecraft, address, serverData, quickPlay);
    }

    @Override
    public void onClose() {
        if (progress != null) progress.cancelled.set(true);
        minecraft.setScreen(parent);
    }

    /** Runs the task off the render thread; results are delivered only while this screen is still open. */
    private <T> void runAsync(String threadName, Callable<T> task, Consumer<T> onSuccess) {
        Thread thread = new Thread(() -> {
            try {
                T result = task.call();
                minecraft.execute(() -> {
                    if (minecraft.screen == this) onSuccess.accept(result);
                });
            } catch (Exception e) {
                Modshare.LOGGER.error("ModShare: {} failed", threadName, e);
                minecraft.execute(() -> {
                    if (minecraft.screen != this) return;
                    error = Component.literal(e.getMessage() != null ? e.getMessage() : e.toString());
                    setState(State.ERROR);
                });
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centerX = width / 2;
        graphics.drawCenteredString(font, title, centerX, 15, WHITE);

        switch (state) {
            case CHECKING -> graphics.drawCenteredString(font, Component.translatable("modshare.screen.checking"), centerX, height / 2 - 10, GRAY);
            case CONFIRM -> renderPlan(graphics, centerX);
            case DOWNLOADING -> renderProgress(graphics, centerX);
            case RESTART_REQUIRED -> {
                int y = drawWrapped(graphics, Component.translatable("modshare.screen.restart"), centerX, height / 2 - 15, WHITE);
                drawWrapped(graphics, Component.translatable("modshare.screen.restart_hint"), centerX, y + 4, GRAY);
            }
            case ERROR -> {
                graphics.drawCenteredString(font, Component.translatable("modshare.screen.error"), centerX, height / 2 - 25, RED);
                drawWrapped(graphics, error, centerX, height / 2 - 10, WHITE);
            }
        }
    }

    /** Draws centered text wrapped to the screen width; returns the y below the last line. */
    private int drawWrapped(GuiGraphics graphics, Component text, int centerX, int y, int color) {
        for (FormattedCharSequence line : font.split(text, width - 40)) {
            graphics.drawCenteredString(font, line, centerX, y, color);
            y += LINE_HEIGHT;
        }
        return y;
    }

    /** The text above the change list; the list itself is a widget placed below it in init(). */
    private List<Component> planHeader() {
        List<Component> paragraphs = new ArrayList<>();
        paragraphs.add(Component.translatable("modshare.screen.summary",
                plan.toDownload().size(), megabytes(plan.downloadSize()), plan.toRemove().size()).withColor(WHITE));
        if (!trusted) {
            paragraphs.add(Component.translatable("modshare.screen.warning").withColor(YELLOW));
            paragraphs.add(Component.translatable("modshare.screen.trust_hint", serverKey()).withColor(GRAY));
        }
        paragraphs.add(Component.translatable("modshare.screen.choice_hint").withColor(GRAY));
        return paragraphs;
    }

    private int planHeaderBottom() {
        int y = 32;
        for (Component paragraph : planHeader()) y += font.split(paragraph, width - 40).size() * LINE_HEIGHT + 3;
        return y + 2;
    }

    private void renderPlan(GuiGraphics graphics, int centerX) {
        int y = 32;
        for (Component paragraph : planHeader()) y = drawWrapped(graphics, paragraph, centerX, y, WHITE) + 3;
    }

    private void renderProgress(GuiGraphics graphics, int centerX) {
        int total = plan.toDownload().size();
        graphics.drawCenteredString(font, Component.translatable("modshare.screen.downloading",
                progress.fileIndex, total, progress.currentFile), centerX, height / 2 - 25, WHITE);

        long done = progress.bytesDone.get();
        long all = Math.max(1, progress.bytesTotal);
        int barWidth = Math.min(300, width - 40);
        int barX = centerX - barWidth / 2;
        int barY = height / 2 - 8;
        graphics.fill(barX, barY, barX + barWidth, barY + 8, 0xFF404040);
        graphics.fill(barX, barY, barX + (int) (barWidth * Math.min(1.0, (double) done / all)), barY + 8, 0xFF55FF55);
        graphics.drawCenteredString(font, Component.translatable("modshare.screen.progress", megabytes(done), megabytes(progress.bytesTotal)), centerX, barY + 14, GRAY);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }
}
