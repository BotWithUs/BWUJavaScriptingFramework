package com.botwithus.bot.cli.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, Command> aliasMap = new LinkedHashMap<>();

    public void register(Command command) {
        commands.put(command.name().toLowerCase(), command);
        for (String alias : command.aliases()) {
            aliasMap.put(alias.toLowerCase(), command);
        }
    }

    public Command resolve(String name) {
        Command cmd = commands.get(name.toLowerCase());
        if (cmd != null) {
            return cmd;
        }
        return aliasMap.get(name.toLowerCase());
    }

    public Collection<Command> all() {
        return commands.values();
    }
}
