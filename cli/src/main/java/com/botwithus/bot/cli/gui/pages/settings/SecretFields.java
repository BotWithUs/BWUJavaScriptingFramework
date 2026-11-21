package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.secrets.CredentialStore;

import imgui.type.ImString;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The secret boxes' side of the Integrations cards: a webhook URL or token is
 * typed, masked, and handed to the launcher when the box is left or Enter is
 * pressed — never per keystroke, and never for an empty box. Render thread only.
 *
 * <p>The launcher keeps the secret and never gives it back, so there is nothing
 * to show: a box that holds nothing shows the launcher's masked hint in place of
 * a saved secret, and the button beside it removes the saved one. The hand-over
 * takes a moment; while it is on its way the box says so, and its outcome is
 * picked up by {@link #poll} on a later frame. A refused secret stays in the box
 * with the reason under it, so it can be fixed rather than retyped.</p>
 */
final class SecretFields {

    private static final int MASK_DOTS = 12;
    /** Stands in for a saved secret when the launcher gave no hint. */
    static final String SAVED_MASK = "•".repeat(MASK_DOTS);
    /** The box's grey text while a secret is on its way to the launcher. */
    static final String SAVING = "Saving…";
    /** Said when the hand-over itself failed rather than being refused. */
    static final String NOT_SAVED = "Could not save it; try again";

    private final Map<AlertService, ImString> buffers = new EnumMap<>(AlertService.class);
    private final Map<AlertService, String> errors = new EnumMap<>(AlertService.class);
    private final Map<AlertService, CompletableFuture<SecretChange>> pending = new EnumMap<>(AlertService.class);

    /** The box's text. */
    ImString buffer(AlertService service) {
        return buffers.computeIfAbsent(service, s -> new ImString(CredentialStore.MAX_SECRET_CHARS));
    }

    /**
     * Hands the box's text to the launcher as the service's secret. Does nothing,
     * and returns empty, for an empty box or while a hand-over is already on its way.
     */
    Optional<CompletableFuture<SecretChange>> commit(AlertService service, SettingsModel model) {
        String text = buffer(service).get();
        if (text.isBlank() || isSaving(service)) {
            return Optional.empty();
        }
        CompletableFuture<SecretChange> change = model.saveSecret(service, text);
        pending.put(service, change);
        return Optional.of(change);
    }

    /** Removes the saved secret, as the button beside a box does. */
    void remove(AlertService service, SettingsModel model) {
        if (!isSaving(service)) {
            pending.put(service, model.saveSecret(service, ""));
        }
    }

    /** Takes in the outcome of a hand-over that has finished. Call once a frame, before drawing the box. */
    void poll(AlertService service) {
        CompletableFuture<SecretChange> change = pending.get(service);
        if (change == null || !change.isDone()) {
            return;
        }
        pending.remove(service);
        switch (outcome(change)) {
            case SecretChange.Saved _ -> {
                errors.remove(service);
                buffer(service).set("");
            }
            case SecretChange.Cleared _ -> errors.remove(service);
            case SecretChange.Refused refused -> errors.put(service, refused.reason());
        }
    }

    /** Whether a hand-over is on its way. */
    boolean isSaving(AlertService service) {
        return pending.containsKey(service);
    }

    /** Why the last secret handed over from the box was refused, if it was. */
    Optional<String> error(AlertService service) {
        return Optional.ofNullable(errors.get(service));
    }

    /** The grey text in the box while it holds nothing: saving, the saved secret's hint, or the example. */
    String hint(CardField.SecretBox box) {
        if (isSaving(box.service())) {
            return SAVING;
        }
        return box.hasSecret() ? box.secretHint().orElse(SAVED_MASK) : box.placeholder();
    }

    private static SecretChange outcome(CompletableFuture<SecretChange> change) {
        try {
            return change.join();
        } catch (CompletionException | CancellationException e) {
            return new SecretChange.Refused(NOT_SAVED);
        }
    }
}
