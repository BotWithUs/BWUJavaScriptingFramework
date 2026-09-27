package com.botwithus.bot.cli.watch;

import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.cli.settings.SettingKeys;
import com.botwithus.bot.cli.settings.Subscription;
import com.botwithus.bot.core.runtime.ScriptFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the host's one {@link ScriptWatcher}: starts and stops it, and keeps it
 * following the {@link SettingKeys#AUTO_RELOAD} setting once {@link #bind bound}.
 *
 * <p>The setting is the intent and {@link #isRunning()} is the fact. They agree
 * except when the watch could not be set up, which is logged and printed.</p>
 */
public final class ScriptWatchControl {

    private final Supplier<Path> scriptsDir;
    private final Consumer<Set<ScriptFolder>> onChange;
    private final Consumer<String> out;
    // Both guarded by this.
    private ScriptWatcher watcher;
    private Subscription settingSubscription;

    /**
     * @param scriptsDir resolved at each start, so the watch follows the folder
     *                   the loader reads rather than the one it read at start-up
     * @param onChange   called with the folders a change touched, on the watch thread
     * @param out        where to print that the watch started or stopped
     */
    public ScriptWatchControl(Supplier<Path> scriptsDir, Consumer<Set<ScriptFolder>> onChange,
                              Consumer<String> out) {
        this.scriptsDir = scriptsDir;
        this.onChange = onChange;
        this.out = out;
    }

    /**
     * Starts the watch unless it is running. Creates the scripts folder if it is
     * missing, as the loader would on its first pass. Returns whether the watch
     * is running afterwards.
     */
    public synchronized boolean start() {
        if (watcher != null && watcher.isRunning()) {
            return true;
        }
        Path dir = scriptsDir.get();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            out.accept("Script watcher not started: cannot create " + dir.toAbsolutePath()
                    + " (" + e.getMessage() + ")");
            return false;
        }
        ScriptWatcher fresh = new ScriptWatcher(dir, onChange);
        if (!fresh.start()) {
            out.accept("Script watcher not started: cannot watch " + dir.toAbsolutePath());
            return false;
        }
        watcher = fresh;
        out.accept("Script file watcher started on " + dir.toAbsolutePath() + ".");
        return true;
    }

    /** Stops the watch if it is running. */
    public synchronized void stop() {
        if (watcher == null) {
            return;
        }
        watcher.stop();
        watcher = null;
        out.accept("Script file watcher stopped.");
    }

    public synchronized boolean isRunning() {
        return watcher != null && watcher.isRunning();
    }

    /**
     * Makes the watch follow {@code settings}' {@link SettingKeys#AUTO_RELOAD}:
     * applies its current value now, then starts or stops the watch on every
     * change. Replaces any earlier binding.
     */
    public synchronized void bind(HostSettings settings) {
        if (settingSubscription != null) {
            settingSubscription.close();
        }
        settingSubscription = settings.onChange(SettingKeys.AUTO_RELOAD, this::follow);
        follow(settings.get(SettingKeys.AUTO_RELOAD));
    }

    private void follow(boolean isOn) {
        if (isOn) {
            start();
        } else {
            stop();
        }
    }
}
