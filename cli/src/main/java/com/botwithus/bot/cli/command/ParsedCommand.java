package com.botwithus.bot.cli.command;

import java.util.List;
import java.util.Map;

public record ParsedCommand(String name, List<String> args, Map<String, String> flags) {

    public String arg(int index) {
        return index >= 0 && index < args.size() ? args.get(index) : null;
    }

    public String flag(String name) {
        return flags.get(name);
    }

    public boolean hasFlag(String name) {
        return flags.containsKey(name);
    }

    public int intFlag(String name, int defaultValue) {
        String val = flags.get(name);
        if (val == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
