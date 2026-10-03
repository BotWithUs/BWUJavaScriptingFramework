package com.botwithus.bot.core.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where this host finds the launcher service: the automation pipe for its
 * scope, and the file that says the user stopped the service.
 *
 * @param scope        the scope in the pipe name; {@code "dev..."} under a development override
 * @param isDevScope   whether the scope is a development override
 * @param sessionId    the logon session id
 * @param localAppData {@code %LOCALAPPDATA%}, when set
 */
public record ServiceLocation(String scope, boolean isDevScope, long sessionId, Optional<Path> localAppData) {

    private static final String STOPPED_FLAG_PREFIX = "stopped_by_user-";

    public ServiceLocation {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(localAppData, "localAppData");
    }

    /**
     * Resolves the location for this process.
     *
     * @param identity    who this process runs as
     * @param gate        whether development overrides apply
     * @param environment reads an environment variable
     * @return the location
     */
    public static ServiceLocation resolve(ServiceScope.ScopeIdentity identity, DevGate gate,
                                          UnaryOperator<String> environment) {
        Optional<String> override = gate.scopeOverride(environment);
        Optional<Path> localAppData = Optional.ofNullable(environment.apply("LOCALAPPDATA")).map(Path::of);
        return new ServiceLocation(override.orElseGet(identity::scope), override.isPresent(),
                identity.sessionId(), localAppData);
    }

    /** @return the automation pipe's name, without the pipe namespace prefix */
    public String pipeName() {
        return LauncherProtocol.automationPipeName(scope);
    }

    /**
     * The user-stopped flag (launcher ADR 0007, section 9.3). This host only
     * reads it, to tell {@code service_stopped} from {@code service_unavailable}.
     *
     * <p><b>Provisional until launcher A7</b>, which writes the flag and must
     * confirm both paths: {@code %LOCALAPPDATA%\BotWithUs\service\stopped_by_user-<session>},
     * and under a development scope {@code ...\service\dev-<scope>\stopped_by_user-<session>},
     * mirroring the sandboxed service's own directory. The native host uses the
     * same two paths.</p>
     *
     * @return the flag's path, when {@code %LOCALAPPDATA%} is known
     */
    public Optional<Path> stoppedFlag() {
        return localAppData.map(base -> {
            Path dir = base.resolve("BotWithUs").resolve("service");
            if (isDevScope) {
                dir = dir.resolve("dev-" + scope);
            }
            return dir.resolve(STOPPED_FLAG_PREFIX + sessionId);
        });
    }

    /** @return whether the user-stopped flag exists now */
    public boolean isUserStopped() {
        return stoppedFlag().map(Files::exists).orElse(false);
    }
}
