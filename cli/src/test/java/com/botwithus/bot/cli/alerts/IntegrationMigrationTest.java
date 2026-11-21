package com.botwithus.bot.cli.alerts;

import com.botwithus.bot.cli.settings.AlertSettingKeys;
import com.botwithus.bot.cli.settings.HostSettings;
import com.botwithus.bot.core.alerts.AlertService;
import com.botwithus.bot.core.alerts.IntegrationChange;
import com.botwithus.bot.core.alerts.IntegrationReply;
import com.botwithus.bot.core.alerts.IntegrationState;
import com.botwithus.bot.core.secrets.CredentialStore;
import com.botwithus.bot.core.secrets.InMemoryCredentialStore;
import com.botwithus.bot.core.secrets.Secret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-time move of the host's old alert set-up to the launcher: it sets each
 * service once, deletes only the host's own entry for that service, leaves the
 * launcher's copy alone when the launcher already has one, and never touches any
 * other credential.
 */
class IntegrationMigrationTest {

    /** Obviously fake values: nothing is ever sent to them. */
    private static final String DISCORD_HOOK = "https://discord.com/api/webhooks/0000/not-a-real-token";
    private static final String SLACK_HOOK = "https://hooks.slack.com/services/T0/B0/not-a-real-token";
    private static final String NTFY_TOKEN = "tk_not_a_real_token";
    /** Entries of other features in the same store; the migration must never touch them. */
    private static final String OTHER_TOKEN = "SomeOtherApp:Token";
    private static final String OTHER_ACCOUNT = "SomeOtherApp:Account:0123456789";
    private static final String OTHER_SECRET = "someone-elses-secret";

    @TempDir
    Path dir;

    private final FakeLauncher launcher = new FakeLauncher(new ManualClock(Instant.parse("2026-11-21T12:00:00Z")));
    private final RecordingStore credentials = new RecordingStore();
    private HostSettings settings;
    private IntegrationMigration migration;

    @BeforeEach
    void open() {
        settings = HostSettings.open(dir);
        migration = new IntegrationMigration(launcher, credentials, settings);
        credentials.seed(OTHER_TOKEN, OTHER_SECRET);
        credentials.seed(OTHER_ACCOUNT, OTHER_SECRET);
    }

    @AfterEach
    void close() {
        settings.close();
    }

    @Test
    void aSecretTheLauncherLacks_isSetOnce_andTheHostsEntryDeleted() {
        credentials.seed(AlertService.DISCORD.credentialTarget(), DISCORD_HOOK);
        credentials.seed(AlertService.SLACK.credentialTarget(), SLACK_HOOK);

        List<IntegrationMigration.Step> first = migration.run();
        List<IntegrationMigration.Step> second = migration.run();

        assertAll(
                () -> assertTrue(first.contains(new IntegrationMigration.Step(AlertService.DISCORD,
                        IntegrationMigration.Outcome.MOVED))),
                () -> assertTrue(first.contains(new IntegrationMigration.Step(AlertService.SLACK,
                        IntegrationMigration.Outcome.MOVED))),
                () -> assertEquals(Optional.of(DISCORD_HOOK), launcher.savedSecret(AlertService.DISCORD)),
                () -> assertEquals(Optional.of(SLACK_HOOK), launcher.savedSecret(AlertService.SLACK)),
                () -> assertEquals(2, launcher.changes().size(), "set once each; the second run has nothing to move"),
                () -> assertTrue(second.stream().allMatch(s -> s.outcome()
                        == IntegrationMigration.Outcome.NOTHING_TO_MOVE)),
                () -> assertEquals(Set.of(AlertService.DISCORD.credentialTarget(),
                        AlertService.SLACK.credentialTarget()), Set.copyOf(credentials.deleted())),
                () -> assertEquals(2, credentials.deleted().size(), "each deleted once"));
    }

    @Test
    void noOtherCredential_isEverRead_writtenOrDeleted() {
        credentials.seed(AlertService.DISCORD.credentialTarget(), DISCORD_HOOK);
        credentials.seed(AlertService.NTFY.credentialTarget(), NTFY_TOKEN);

        migration.run();

        assertAll(
                () -> assertEquals(Optional.of(new Secret(OTHER_SECRET)), credentials.peek(OTHER_TOKEN)),
                () -> assertEquals(Optional.of(new Secret(OTHER_SECRET)), credentials.peek(OTHER_ACCOUNT)),
                () -> assertTrue(credentials.touched().stream()
                        .allMatch(target -> target.startsWith(AlertService.CREDENTIAL_TARGET_PREFIX)),
                        "touched only " + AlertService.CREDENTIAL_TARGET_PREFIX + "*: " + credentials.touched()),
                () -> assertEquals(List.of(), credentials.written(), "the migration never writes a credential"));
    }

    @Test
    void whenTheLauncherAlreadyHasIt_theLauncherWins_andTheHostsCopyGoes() {
        launcher.setup(new IntegrationState(AlertService.DISCORD, true, Optional.of("discord.com/…"), false, "", ""));
        credentials.seed(AlertService.DISCORD.credentialTarget(), DISCORD_HOOK);

        List<IntegrationMigration.Step> steps = migration.run();

        assertAll(
                () -> assertTrue(steps.contains(new IntegrationMigration.Step(AlertService.DISCORD,
                        IntegrationMigration.Outcome.LAUNCHER_HAD_ITS_OWN))),
                () -> assertEquals(List.of(), launcher.changes(), "the launcher's copy is left alone"),
                () -> assertEquals(List.of(AlertService.DISCORD.credentialTarget()), credentials.deleted()));
    }

    @Test
    void aRefusal_keepsTheHostsCopy_forTheNextRun() {
        credentials.seed(AlertService.SLACK.credentialTarget(), SLACK_HOOK);
        launcher.replyToNextChange(new IntegrationReply.Refused(Optional.of("secret"), "not a Slack webhook"));

        List<IntegrationMigration.Step> first = migration.run();
        List<IntegrationMigration.Step> second = migration.run();

        assertAll(
                () -> assertTrue(first.contains(new IntegrationMigration.Step(AlertService.SLACK,
                        IntegrationMigration.Outcome.KEPT_FOR_LATER))),
                () -> assertTrue(second.contains(new IntegrationMigration.Step(AlertService.SLACK,
                        IntegrationMigration.Outcome.MOVED)), "tried again, and moved"),
                () -> assertEquals(List.of(AlertService.SLACK.credentialTarget()), credentials.deleted(),
                        "deleted only once the launcher had it"));
    }

    @Test
    void anOlderLauncher_movesNothing_andDeletesNothing() {
        launcher.predatingAlerts();
        credentials.seed(AlertService.DISCORD.credentialTarget(), DISCORD_HOOK);

        assertAll(() -> assertEquals(List.of(), migration.run()),
                () -> assertEquals(List.of(), credentials.deleted()),
                () -> assertEquals(List.of(), launcher.changes()));
    }

    @Test
    void ntfy_movesItsTokenServerAndTopic_thenStopsReadingTheOldSettings() {
        credentials.seed(AlertService.NTFY.credentialTarget(), NTFY_TOKEN);
        settings.set(AlertSettingKeys.NTFY_SERVER, "https://ntfy.example.org");
        settings.set(AlertSettingKeys.NTFY_TOPIC, "bwu-alerts");

        migration.run();

        IntegrationChange change = launcher.changes().getFirst();
        assertAll(
                () -> assertEquals(Optional.of(NTFY_TOKEN), change.secret().map(Secret::reveal)),
                () -> assertEquals(Optional.of("https://ntfy.example.org"), change.server()),
                () -> assertEquals(Optional.of("bwu-alerts"), change.topic()),
                () -> assertEquals(Optional.empty(), change.isMentioningHere()),
                () -> assertTrue(!settings.isExplicit(AlertSettingKeys.NTFY_TOPIC)
                        && !settings.isExplicit(AlertSettingKeys.NTFY_SERVER), "the old settings are reset"));
    }

    @Test
    void anNtfyTopicWithNoToken_movesToo() {
        settings.set(AlertSettingKeys.NTFY_TOPIC, "bwu-alerts");

        List<IntegrationMigration.Step> steps = migration.run();

        assertAll(
                () -> assertTrue(steps.contains(new IntegrationMigration.Step(AlertService.NTFY,
                        IntegrationMigration.Outcome.MOVED))),
                () -> assertEquals(Optional.empty(), launcher.changes().getFirst().secret()),
                () -> assertEquals(List.of(AlertService.NTFY.credentialTarget()), credentials.deleted(),
                        "deleting an entry that is not there is harmless"));
    }

    @Test
    void discord_movesItsMentionSettingWithTheSecret() {
        credentials.seed(AlertService.DISCORD.credentialTarget(), DISCORD_HOOK);
        settings.set(AlertSettingKeys.DISCORD_MENTION_HERE, true);

        migration.run();

        assertAll(() -> assertEquals(Optional.of(true), launcher.changes().getFirst().isMentioningHere()),
                () -> assertTrue(!settings.isExplicit(AlertSettingKeys.DISCORD_MENTION_HERE)));
    }

    /** An in-memory store that records which targets were read, written and deleted. */
    private static final class RecordingStore implements CredentialStore {

        private final InMemoryCredentialStore store = new InMemoryCredentialStore();
        private final List<String> touched = new ArrayList<>();
        private final List<String> written = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        void seed(String target, String value) {
            store.write(target, new Secret(value));
        }

        Optional<Secret> peek(String target) {
            return store.read(target);
        }

        List<String> touched() {
            return List.copyOf(touched);
        }

        List<String> written() {
            return List.copyOf(written);
        }

        List<String> deleted() {
            return List.copyOf(deleted);
        }

        @Override
        public Optional<Secret> read(String target) {
            touched.add(target);
            return store.read(target);
        }

        @Override
        public void write(String target, Secret secret) {
            touched.add(target);
            written.add(target);
            store.write(target, secret);
        }

        @Override
        public boolean delete(String target) {
            touched.add(target);
            deleted.add(target);
            return store.delete(target);
        }
    }
}
