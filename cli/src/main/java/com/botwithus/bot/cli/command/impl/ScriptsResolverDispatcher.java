package com.botwithus.bot.cli.command.impl;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.output.AnsiCodes;
import com.botwithus.bot.cli.output.TableFormatter;
import com.botwithus.bot.core.resolver.Credentials;
import com.botwithus.bot.core.resolver.InstallResult;
import com.botwithus.bot.core.resolver.MavenCoord;
import com.botwithus.bot.core.resolver.Repository;
import com.botwithus.bot.core.resolver.ResolveOutcome;
import com.botwithus.bot.core.resolver.SearchOutcome;
import com.botwithus.bot.core.resolver.config.CredentialsStore;
import com.botwithus.bot.core.resolver.config.RepositoryConfigStore;
import com.botwithus.bot.core.resolver.driver.MavenRepositoryDriver;
import com.botwithus.bot.core.resolver.install.InstalledEntry;
import com.botwithus.bot.core.resolver.install.ScriptInstaller;
import com.botwithus.bot.core.resolver.pgp.TrustedKey;
import com.botwithus.bot.core.resolver.search.SearchService;
import com.botwithus.bot.core.resolver.transport.TransportResult;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Subcommand dispatcher for the resolver-backed {@code scripts} verbs
 * (install / update / uninstall / search / list --installed / repo * /
 * adopt). Kept in its own class so {@link ScriptsCommand} stays small and
 * the resolver wiring is testable without spinning up the full CLI.
 *
 * <p>Stateless except for the constructor-injected
 * {@link CliContext#getResolverWiring() wiring} bundle — every method
 * reads the latest stores so a freshly-added repository takes effect on
 * the next invocation without restart.</p>
 */
public final class ScriptsResolverDispatcher {

    private static final String FLAG_REPO = "repo";
    private static final String FLAG_LIMIT = "limit";
    private static final String FLAG_TYPE = "type";
    private static final String FLAG_DRIVER = "driver";
    private static final String FLAG_REQUIRE_SIGNATURE = "require-signature";
    private static final String FLAG_USER = "user";
    private static final String FLAG_PASSWORD = "password";
    private static final String FLAG_INSTALLED = "installed";
    private static final String FLAG_OUTDATED = "outdated";
    private static final String FLAG_ALL = "all";
    private static final int DEFAULT_SEARCH_LIMIT = 50;

    private final CliContext ctx;

    public ScriptsResolverDispatcher(CliContext ctx) {
        this.ctx = ctx;
    }

    /** Returns true if {@code sub} is a resolver subcommand we handle. */
    public static boolean handles(String sub) {
        if (sub == null) {
            return false;
        }
        return switch (sub) {
            case "install", "update", "uninstall", "search", "repo", "adopt", "trust" -> true;
            default -> false;
        };
    }

    public void dispatch(ParsedCommand parsed) {
        String sub = parsed.arg(0);
        if (sub == null) {
            ctx.out().println("Usage: scripts <install|update|uninstall|search|repo|adopt|trust|list --installed> ...");
            return;
        }
        switch (sub) {
            case "install" -> install(parsed);
            case "update" -> update(parsed);
            case "uninstall" -> uninstall(parsed);
            case "search" -> search(parsed);
            case "repo" -> repo(parsed);
            case "adopt" -> adopt(parsed);
            case "trust" -> trust(parsed);
            default -> ctx.out().println("Unknown subcommand: " + sub);
        }
    }

    /** Renders the {@code scripts list --installed [--outdated]} table. */
    public void listInstalled(boolean outdated) {
        ScriptInstaller installer = ctx.getInstaller();
        if (outdated) {
            List<ScriptInstaller.OutdatedEntry> stale = installer.listOutdated();
            if (stale.isEmpty()) {
                ctx.out().println("All installed scripts are up to date.");
                return;
            }
            TableFormatter table = new TableFormatter().headers("Coord", "Installed", "Latest");
            for (var e : stale) {
                table.row(e.installed().key(), e.installed().version(), e.latestVersion());
            }
            ctx.out().print(table.build());
            return;
        }
        List<InstalledEntry> entries = installer.listInstalled();
        if (entries.isEmpty()) {
            ctx.out().println("No scripts installed via resolver.");
            return;
        }
        TableFormatter table = new TableFormatter().headers("Coord", "Version", "Repo", "JAR");
        for (InstalledEntry e : entries) {
            table.row(e.key(), e.version(), e.repoId(), e.jarFilename());
        }
        ctx.out().print(table.build());
    }

    private void install(ParsedCommand parsed) {
        String spec = parsed.arg(1);
        if (spec == null) {
            ctx.out().println("Usage: scripts install <groupId:artifactId>[:version] [--repo=<id>]");
            return;
        }
        Optional<MavenCoord> coord = MavenCoord.parse(spec);
        if (coord.isEmpty()) {
            ctx.out().println("Invalid coordinate: " + spec);
            return;
        }
        InstallResult result = ctx.getInstaller().install(coord.get());
        printInstallResult(result);
    }

    private void update(ParsedCommand parsed) {
        if ("--all".equals(parsed.arg(1)) || parsed.hasFlag(FLAG_ALL)) {
            for (InstalledEntry entry : ctx.getInstaller().listInstalled()) {
                MavenCoord coord = MavenCoord.of(entry.coord().groupId(), entry.coord().artifactId());
                ctx.out().println("Updating " + coord + " (was " + entry.version() + ")");
                printInstallResult(ctx.getInstaller().update(coord));
            }
            return;
        }
        String spec = parsed.arg(1);
        if (spec == null) {
            ctx.out().println("Usage: scripts update <groupId:artifactId> | scripts update --all");
            return;
        }
        Optional<MavenCoord> coord = MavenCoord.parse(spec);
        if (coord.isEmpty()) {
            ctx.out().println("Invalid coordinate: " + spec);
            return;
        }
        printInstallResult(ctx.getInstaller().update(coord.get()));
    }

    private void uninstall(ParsedCommand parsed) {
        String spec = parsed.arg(1);
        if (spec == null) {
            ctx.out().println("Usage: scripts uninstall <groupId:artifactId>");
            return;
        }
        Optional<MavenCoord> coord = MavenCoord.parse(spec);
        if (coord.isEmpty()) {
            ctx.out().println("Invalid coordinate: " + spec);
            return;
        }
        printInstallResult(ctx.getInstaller().uninstall(coord.get()));
    }

    private void search(ParsedCommand parsed) {
        String query = parsed.arg(1);
        if (query == null) {
            ctx.out().println("Usage: scripts search <query> [--repo=<id>] [--limit=N]");
            return;
        }
        String repoId = parsed.flag(FLAG_REPO);
        int limit = parsePositiveIntFlag(parsed, FLAG_LIMIT, DEFAULT_SEARCH_LIMIT);

        List<Repository> targets = repoId != null
                ? ctx.getRepositoryConfigStore().find(repoId)
                .map(List::of).orElse(List.of())
                : ctx.getRepositoryConfigStore().all();

        if (targets.isEmpty()) {
            ctx.out().println(repoId != null
                    ? "Repository not found: " + repoId
                    : "No repositories configured.");
            return;
        }

        SearchService service = ctx.getSearchService();
        for (Repository repo : targets) {
            SearchOutcome outcome = service.search(repo, query, limit);
            printSearchOutcome(repo, outcome);
        }
    }

    private void repo(ParsedCommand parsed) {
        String op = parsed.arg(1);
        if (op == null) {
            ctx.out().println("Usage: scripts repo <add|remove|list|login|logout> ...");
            return;
        }
        switch (op) {
            case "add" -> repoAdd(parsed);
            case "remove" -> repoRemove(parsed);
            case "list" -> repoList();
            case "login" -> repoLogin(parsed);
            case "logout" -> repoLogout(parsed);
            default -> ctx.out().println("Unknown repo op: " + op);
        }
    }

    private void repoAdd(ParsedCommand parsed) {
        String id = parsed.arg(2);
        String url = parsed.arg(3);
        if (id == null || url == null) {
            ctx.out().println("Usage: scripts repo add <id> <url> [--type=release|snapshot] [--require-signature]");
            return;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            ctx.out().println("Invalid URL: " + url);
            return;
        }
        boolean snapshots = "snapshot".equalsIgnoreCase(parsed.flag(FLAG_TYPE));
        boolean requireSig = parsed.hasFlag(FLAG_REQUIRE_SIGNATURE);
        String driverId = parsed.flag(FLAG_DRIVER);
        if (driverId == null || driverId.isBlank()) {
            driverId = MavenRepositoryDriver.TYPE_ID;
        }

        Repository repo = new Repository(id, uri, driverId, snapshots, requireSig,
                Optional.empty(), Optional.empty());
        RepositoryConfigStore store = ctx.getRepositoryConfigStore();
        store.put(repo);
        saveOrWarn(() -> store.save(), "repositories");
        ctx.out().println("Added repository: " + id + " -> " + uri
                + (requireSig ? " (PGP required)" : ""));
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            ctx.out().println(AnsiCodes.colorize(
                    "Warning: plain HTTP — credentials and artifacts are sent in the clear.",
                    AnsiCodes.YELLOW));
        }
    }

    private void repoRemove(ParsedCommand parsed) {
        String id = parsed.arg(2);
        if (id == null) {
            ctx.out().println("Usage: scripts repo remove <id>");
            return;
        }
        RepositoryConfigStore store = ctx.getRepositoryConfigStore();
        if (!store.remove(id)) {
            ctx.out().println("Repository not found: " + id);
            return;
        }
        saveOrWarn(() -> store.save(), "repositories");
        ctx.out().println("Removed repository: " + id);
    }

    private void repoList() {
        List<Repository> repos = ctx.getRepositoryConfigStore().all();
        if (repos.isEmpty()) {
            ctx.out().println("No repositories configured.");
            return;
        }
        TableFormatter table = new TableFormatter().headers("ID", "URL", "Driver", "Snapshots", "Signed", "Search");
        for (Repository r : repos) {
            table.row(r.id(),
                    r.url().toString(),
                    r.driverId(),
                    r.snapshots() ? "yes" : "no",
                    r.requireSignature() ? "yes" : "no",
                    r.searchEndpoint().map(URI::toString).orElse("-"));
        }
        ctx.out().print(table.build());
    }

    private void repoLogin(ParsedCommand parsed) {
        String id = parsed.arg(2);
        String user = parsed.flag(FLAG_USER);
        String password = parsed.flag(FLAG_PASSWORD);
        if (id == null || user == null || password == null) {
            ctx.out().println("Usage: scripts repo login <id> --user <u> --password <p>");
            return;
        }
        CredentialsStore store = ctx.getCredentialsStore();
        store.put(id, new Credentials(user, password));
        saveOrWarn(() -> store.save(), "credentials");
        ctx.out().println("Credentials stored for " + id + " (user " + user + ").");
    }

    private void repoLogout(ParsedCommand parsed) {
        String id = parsed.arg(2);
        if (id == null) {
            ctx.out().println("Usage: scripts repo logout <id>");
            return;
        }
        CredentialsStore store = ctx.getCredentialsStore();
        if (!store.remove(id)) {
            ctx.out().println("No stored credentials for: " + id);
            return;
        }
        saveOrWarn(() -> store.save(), "credentials");
        ctx.out().println("Credentials cleared for " + id);
    }

    private void adopt(ParsedCommand parsed) {
        String jarName = parsed.arg(1);
        if (jarName == null) {
            ctx.out().println("Usage: scripts adopt <jar-filename>");
            return;
        }
        InstallResult result = ctx.getInstaller().adopt(jarName);
        printInstallResult(result);
    }

    private void trust(ParsedCommand parsed) {
        String op = parsed.arg(1);
        if (op == null) {
            ctx.out().println("Usage: scripts trust <add|remove|list> ...");
            return;
        }
        switch (op) {
            case "add" -> trustAdd(parsed);
            case "remove" -> trustRemove(parsed);
            case "list" -> trustList();
            default -> ctx.out().println("Unknown trust op: " + op);
        }
    }

    private void trustAdd(ParsedCommand parsed) {
        String pathStr = parsed.arg(2);
        if (pathStr == null) {
            ctx.out().println("Usage: scripts trust add <keyfile>");
            return;
        }
        Path keyFile = Path.of(pathStr);
        try {
            List<String> imported = ctx.getKeyRingStore().addKey(keyFile);
            if (imported.isEmpty()) {
                ctx.out().println("No keys imported from " + keyFile);
                return;
            }
            for (String keyId : imported) {
                ctx.out().println(AnsiCodes.colorize("Trusted ", AnsiCodes.GREEN) + keyId);
            }
        } catch (IOException e) {
            ctx.out().println(AnsiCodes.colorize("Failed to import key: ", AnsiCodes.RED) + e.getMessage());
        }
    }

    private void trustRemove(ParsedCommand parsed) {
        String keyId = parsed.arg(2);
        if (keyId == null) {
            ctx.out().println("Usage: scripts trust remove <keyId>");
            return;
        }
        try {
            if (ctx.getKeyRingStore().removeKey(keyId)) {
                ctx.out().println(AnsiCodes.colorize("Removed ", AnsiCodes.GREEN) + keyId);
            } else {
                ctx.out().println("Key not in trust store: " + keyId);
            }
        } catch (IOException e) {
            ctx.out().println(AnsiCodes.colorize("Failed to remove key: ", AnsiCodes.RED) + e.getMessage());
        }
    }

    private void trustList() {
        List<TrustedKey> keys = ctx.getKeyRingStore().list();
        if (keys.isEmpty()) {
            ctx.out().println("No trusted keys. Use 'scripts trust add <keyfile>' to trust one.");
            return;
        }
        TableFormatter table = new TableFormatter().headers("Key ID", "User ID", "Added");
        for (TrustedKey k : keys) {
            table.row(k.keyId(), k.userId(), k.addedAt().toString());
        }
        ctx.out().print(table.build());
    }

    private void printInstallResult(InstallResult result) {
        switch (result) {
            case InstallResult.Installed r ->
                    ctx.out().println(AnsiCodes.colorize("Installed ", AnsiCodes.GREEN) + r.coord() + " -> " + r.jar());
            case InstallResult.Updated r ->
                    ctx.out().println(AnsiCodes.colorize("Updated ", AnsiCodes.GREEN) + r.coord()
                            + " (was " + r.oldJar().getFileName() + ", now " + r.newJar().getFileName() + ")");
            case InstallResult.AlreadyInstalled r ->
                    ctx.out().println(r.coord() + " is already installed at " + r.existingJar().getFileName());
            case InstallResult.NoUpdateAvailable r ->
                    ctx.out().println(r.coord() + " is up to date (" + r.installedVersion() + ")");
            case InstallResult.Uninstalled r ->
                    ctx.out().println(AnsiCodes.colorize("Removed ", AnsiCodes.GREEN) + r.coord());
            case InstallResult.NotInstalled r ->
                    ctx.out().println(r.coord() + " is not installed.");
            case InstallResult.ResolveFailed r -> printResolveFailure(r.coord(), r.outcome());
            case InstallResult.IoError r ->
                    ctx.out().println(AnsiCodes.colorize("IO error: ", AnsiCodes.RED)
                            + r.cause().getMessage());
        }
    }

    private void printResolveFailure(MavenCoord coord, ResolveOutcome outcome) {
        switch (outcome) {
            case ResolveOutcome.Resolved ignored ->
                    ctx.out().println("(internal) ResolveFailed wrapping Resolved for " + coord);
            case ResolveOutcome.NotFound nf ->
                    ctx.out().println(AnsiCodes.colorize("Not found: ", AnsiCodes.RED) + coord + " (" + nf.reason() + ")");
            case ResolveOutcome.ChecksumMismatch cm ->
                    ctx.out().println(AnsiCodes.colorize("Checksum mismatch: ", AnsiCodes.RED)
                            + coord + " in repo " + cm.repository().id());
            case ResolveOutcome.SignatureInvalid si ->
                    ctx.out().println(AnsiCodes.colorize("Signature invalid: ", AnsiCodes.RED)
                            + coord + " in repo " + si.repository().id() + " — " + si.signatureResult());
            case ResolveOutcome.TransportFailure tf ->
                    ctx.out().println(AnsiCodes.colorize("Transport failure: ", AnsiCodes.RED)
                            + coord + " (" + tf.stage() + ", repo " + tf.repository().id() + ")");
        }
    }

    private void printSearchOutcome(Repository repo, SearchOutcome outcome) {
        switch (outcome) {
            case SearchOutcome.Hits hits -> {
                if (hits.hits().isEmpty()) {
                    ctx.out().println("[" + repo.id() + "] no hits");
                    return;
                }
                ctx.out().println("[" + repo.id() + "] " + hits.hits().size() + " hit(s):");
                TableFormatter table = new TableFormatter().headers("Coord", "Latest", "Description");
                for (SearchOutcome.Hit h : hits.hits()) {
                    table.row(h.coord().ga(), h.latestVersion(), h.description());
                }
                ctx.out().print(table.build());
            }
            case SearchOutcome.NotSupported ns ->
                    ctx.out().println("[" + repo.id() + "] search not supported (" + ns.reason() + ")");
            case SearchOutcome.TransportFailure tf -> {
                TransportResult cause = tf.cause();
                ctx.out().println("[" + repo.id() + "] transport failure: " + cause);
            }
        }
    }

    private static int parsePositiveIntFlag(ParsedCommand parsed, String flagName, int fallback) {
        String raw = parsed.flag(flagName);
        if (raw == null) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void saveOrWarn(IoAction action, String what) {
        try {
            action.run();
        } catch (IOException e) {
            ctx.out().println(AnsiCodes.colorize("Failed to persist " + what + ": ", AnsiCodes.RED) + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
