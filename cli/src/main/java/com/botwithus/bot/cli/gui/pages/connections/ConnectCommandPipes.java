package com.botwithus.bot.cli.gui.pages.connections;

import com.botwithus.bot.cli.CliContext;
import com.botwithus.bot.cli.GameState;
import com.botwithus.bot.cli.GameStatus;
import com.botwithus.bot.cli.command.Command;
import com.botwithus.bot.cli.command.CommandRegistry;
import com.botwithus.bot.cli.command.CommandResult;
import com.botwithus.bot.cli.command.ParsedCommand;
import com.botwithus.bot.cli.command.impl.ConnectCommand;
import com.botwithus.bot.cli.command.impl.ConnectCommand.PipeInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Scans and connects through the console's own {@code connect} command, so the
 * page and the console do exactly the same thing: a scan probes each pipe (and
 * sends a client at the login screen to the lobby to read its account), and a
 * connect starts the account's scripts.
 */
public final class ConnectCommandPipes implements LiveConnectionsModel.PipeCommands {

    private static final String CONNECT = "connect";
    private static final String SCAN = "scan";
    /** What the scan's probe reports as the name when a pipe did not answer in time. */
    private static final String TIMED_OUT = "(timeout)";

    private final CommandRegistry registry;
    private final CliContext ctx;

    public ConnectCommandPipes(CommandRegistry registry, CliContext ctx) {
        this.registry = registry;
        this.ctx = ctx;
    }

    @Override
    public LiveConnectionsModel.ScanOutcome scan(String prefix) {
        Command command = registry.resolve(CONNECT);
        if (command == null) {
            return new LiveConnectionsModel.ScanOutcome(List.of(), "The connect command is not available.");
        }
        CommandResult result = command.executeWithResult(
                new ParsedCommand(CONNECT, List.of(SCAN, prefix), Map.of()), ctx);
        List<PipeInfo> infos = result.get(ConnectCommand.SCAN_RESULTS);
        List<FoundPipe> found = infos == null ? List.of() : infos.stream().map(ConnectCommandPipes::foundOf).toList();
        String message = result.message() != null ? result.message() : "No pipes found.";
        return new LiveConnectionsModel.ScanOutcome(found, message);
    }

    @Override
    public void connect(String pipe) {
        Command command = registry.resolve(CONNECT);
        if (command != null) {
            command.execute(new ParsedCommand(CONNECT, List.of(pipe), Map.of()), ctx);
        }
    }

    /**
     * What one probe says. It can only tell logged in (with a world, when the
     * world reply came back) from not; a named client that is not logged in is in
     * the lobby, and one it could not read at all is unknown.
     */
    static FoundPipe foundOf(PipeInfo info) {
        Optional<String> name = Optional.ofNullable(info.displayName())
                .filter(n -> !n.isBlank() && !TIMED_OUT.equals(n));
        GameStatus game;
        if (info.loggedIn()) {
            OptionalInt world = info.worldId() > 0 ? OptionalInt.of(info.worldId()) : OptionalInt.empty();
            game = new GameStatus(GameState.IN_GAME, world, info.isMember());
        } else if (name.isPresent()) {
            game = new GameStatus(GameState.LOBBY, OptionalInt.empty(), false);
        } else {
            game = GameStatus.UNKNOWN;
        }
        return new FoundPipe(info.pipeName(), name, game);
    }
}
