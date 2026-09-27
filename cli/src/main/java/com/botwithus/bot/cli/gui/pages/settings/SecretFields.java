package com.botwithus.bot.cli.gui.pages.settings;

import com.botwithus.bot.cli.alerts.SecretChange;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.secrets.CredentialStore;

import imgui.type.ImString;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The secret boxes' side of the Integrations cards: a webhook URL or token is
 * typed, masked, and saved to the credential store when the box is left or Enter
 * is pressed — never per keystroke, and never when nothing was changed. A box
 * that is committed blank removes the saved secret. Render thread only.
 *
 * <p>The saved secret is not held here until the user asks to see it: a masked
 * box that has not been touched is empty and shows dots in its place. Show reads
 * it from the store into the box; Hide drops it from the box again, unless the
 * user has typed a new one that is not saved yet. A refused secret stays in the
 * box with the reason under it, so it can be fixed rather than retyped.</p>
 */
final class SecretFields {

    private static final int MASK_DOTS = 12;
    /** Stands in for a saved secret in a masked box that holds nothing. */
    static final String SAVED_MASK = "•".repeat(MASK_DOTS);

    private final Map<AlertService, ImString> buffers = new EnumMap<>(AlertService.class);
    /** What each box held when it last matched the store; a commit of the same text does nothing. */
    private final Map<AlertService, String> baselines = new EnumMap<>(AlertService.class);
    private final Map<AlertService, String> errors = new EnumMap<>(AlertService.class);
    private final Set<AlertService> revealed = EnumSet.noneOf(AlertService.class);

    /** The box's text. */
    ImString buffer(AlertService service) {
        return buffers.computeIfAbsent(service, s -> new ImString(CredentialStore.MAX_SECRET_CHARS));
    }

    /** Whether the box shows its text rather than dots. */
    boolean isRevealed(AlertService service) {
        return revealed.contains(service);
    }

    /** Show or Hide. Show reads the saved secret into an untouched box; Hide drops it again. */
    void toggleReveal(AlertService service, SettingsModel model) {
        ImString buffer = buffer(service);
        if (revealed.remove(service)) {
            if (!isChanged(service)) {
                reset(service);
            }
            return;
        }
        revealed.add(service);
        if (!isChanged(service)) {
            model.readSecret(service).ifPresent(secret -> {
                buffer.set(secret.reveal());
                baselines.put(service, secret.reveal());
            });
        }
    }

    /**
     * Saves the box's text as the service's secret, or removes the saved one when
     * the box is blank. Does nothing, and returns empty, when the text is what the
     * box held when it last matched the store.
     */
    Optional<SecretChange> commit(AlertService service, SettingsModel model) {
        if (!isChanged(service)) {
            return Optional.empty();
        }
        String text = buffer(service).get();
        SecretChange change = model.saveSecret(service, text);
        switch (change) {
            case SecretChange.Saved _ -> saved(service, text);
            case SecretChange.Cleared _ -> {
                errors.remove(service);
                reset(service);
            }
            case SecretChange.Refused refused -> errors.put(service, refused.reason());
        }
        return Optional.of(change);
    }

    /** Why the last secret committed from the box was refused, if it was. */
    Optional<String> error(AlertService service) {
        return Optional.ofNullable(errors.get(service));
    }

    /** The grey text in the box while it holds nothing: dots when a secret is saved, else the example. */
    String hint(CardField.SecretBox box) {
        return box.hasSecret() && !isRevealed(box.service()) ? SAVED_MASK : box.placeholder();
    }

    private void saved(AlertService service, String text) {
        errors.remove(service);
        if (isRevealed(service)) {
            baselines.put(service, text);
        } else {
            reset(service);
        }
    }

    private boolean isChanged(AlertService service) {
        return !buffer(service).get().equals(baselines.getOrDefault(service, ""));
    }

    private void reset(AlertService service) {
        buffer(service).set("");
        baselines.put(service, "");
    }
}
