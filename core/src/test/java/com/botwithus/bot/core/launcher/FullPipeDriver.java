package com.botwithus.bot.core.launcher;

import com.botwithus.bot.api.script.StopMode;
import com.botwithus.msgpack.MsgMap;
import com.botwithus.msgpack.MsgValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A development tool for the launcher acceptance runs: drives the service's
 * <em>full</em> pipe the way the launcher's own front ends do, for the few
 * calls a host test needs and a host cannot make (signing a Debug service in,
 * asking hosts to close, reading the host table). Run through
 * {@code :core:launcherFullPipe --args="<command>"}; prints each reply body as JSON.
 *
 * <p>It can do nothing else. In particular it has no way to sign out, remove or
 * rename an account, or save a token: those are destructive against the user's
 * real stores, and no test here needs them.</p>
 */
final class FullPipeDriver {

    private static final Duration TIMEOUT = Duration.ofSeconds(40);
    private static final Duration READ_POLL = Duration.ofMillis(5);

    /** The only calls this tool makes. */
    private static final Map<String, Function<List<String>, MsgValue>> COMMANDS = Map.of(
            "login-dev", args -> body("mode", MsgValue.of("dev_bypass")),
            "status", args -> Envelope.emptyBody(),
            "accounts", args -> Envelope.emptyBody(),
            "clients", args -> Envelope.emptyBody(),
            "hosts", args -> Envelope.emptyBody(),
            "request-close", args -> args.isEmpty() ? Envelope.emptyBody()
                    : body("pid", MsgValue.of(Long.parseLong(args.getFirst()))),
            "stop", args -> LauncherRequests.clientStop(args.getFirst(),
                    args.size() > 1 && args.get(1).equals("kill")
                            ? StopMode.KILL
                            : StopMode.GRACEFUL));

    private static final Map<String, String> METHODS = Map.of(
            "login-dev", "auth.login", "status", "service.status", "accounts", "accounts.list",
            "clients", LauncherProtocol.METHOD_CLIENT_LIST, "hosts", "hosts.list",
            "request-close", "hosts.request_close", "stop", LauncherProtocol.METHOD_CLIENT_STOP);

    private FullPipeDriver() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || !COMMANDS.containsKey(args[0])) {
            System.err.println("usage: " + String.join(" | ", COMMANDS.keySet()));
            System.exit(2);
        }
        List<String> rest = List.of(args).subList(1, args.length);
        ServiceLocation location = ServiceLocation.resolve(ServiceScope.current(), DevGate.fromSystemProperties(),
                System::getenv);
        String pipe = "bwu_svc_" + location.scope();
        FrameChannel channel = PipeFrameChannel.opener(pipe).open();
        try (ServiceSession session = new ServiceSession(channel, READ_POLL, new Quiet())) {
            session.start();
            print("hello", session.call(LauncherProtocol.METHOD_HELLO, cliHello(), TIMEOUT));
            print(args[0], session.call(METHODS.get(args[0]), COMMANDS.get(args[0]).apply(rest), TIMEOUT));
        }
    }

    private static MsgValue cliHello() {
        return MsgMap.builder()
                .put(MsgValue.of("protocolMin"), MsgValue.of(LauncherProtocol.PROTOCOL_VERSION))
                .put(MsgValue.of("protocolMax"), MsgValue.of(LauncherProtocol.PROTOCOL_VERSION))
                .put(MsgValue.of("clientKind"), MsgValue.of("cli"))
                .put(MsgValue.of("clientVersion"), MsgValue.of("a6-java-test-driver"))
                .build();
    }

    private static MsgValue body(String key, MsgValue value) {
        return MsgMap.builder().put(MsgValue.of(key), value).build();
    }

    private static void print(String what, Envelope reply) {
        Optional<WireError> error = reply.error();
        System.out.println("{\"call\":\"" + what + "\",\"ok\":" + reply.isOk() + ",\"body\":" + MsgJson.toJson(reply.body())
                + error.map(e -> ",\"error\":\"" + e.code() + ": " + e.message().replace("\"", "'") + "\"").orElse("")
                + "}");
    }

    /** Events are not this tool's business. */
    private static final class Quiet implements ServiceSession.Listener {
        @Override
        public void onEvent(ServiceSession source, Envelope event, long missed) {
        }

        @Override
        public void onClosed(ServiceSession session, Throwable cause) {
        }
    }
}
