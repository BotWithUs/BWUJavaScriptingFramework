package com.botwithus.bot.core.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link CredentialStore} contract against the real Windows Credential
 * Manager, through {@link WindowsCredentialStore}.
 *
 * <p>Writes to the current Windows user's credential store, so it is opt-in: run
 * it through the harness task, e.g.
 * {@code ./gradlew :core:harnessTest -PharnessTests=*WindowsCredentialStoreLiveTest*}.
 * Every case writes only under a {@code BotWithUs/integrations/test-<random>}
 * target and deletes it afterwards; {@link #confirmGone} then re-reads each one
 * through a second store instance, so a delete that only looked successful fails
 * the case.</p>
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "botwithus.smoke.live", matches = "true")
class WindowsCredentialStoreLiveTest extends CredentialStoreContract {

    @Override
    CredentialStore createStore() {
        return new WindowsCredentialStore();
    }

    @Override
    void confirmGone(String target) {
        assertEquals(Optional.empty(), new WindowsCredentialStore().read(target),
                "still in Credential Manager: " + target);
    }

    @Test
    void write_isVisibleToASecondInstance() {
        String target = throwawayTarget();
        store().write(target, new Secret("shared"));

        assertEquals(Optional.of(new Secret("shared")), new WindowsCredentialStore().read(target));
    }

    @Test
    void writtenTargets_areAllThrowaway() {
        String target = throwawayTarget();

        assertTrue(target.startsWith(TEST_TARGET_PREFIX), target);
    }
}
