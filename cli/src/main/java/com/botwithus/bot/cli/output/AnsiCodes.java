package com.botwithus.bot.cli.output;

public final class AnsiCodes {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";

    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    public static final String CLEAR_SCREEN = "\u001B[2J\u001B[H";

    private AnsiCodes() {}

    public static boolean isSupported() {
        String term = System.getenv("TERM");
        if (term != null && !term.equals("dumb")) {
            return true;
        }
        // Windows Terminal and modern consoles support ANSI
        String wtSession = System.getenv("WT_SESSION");
        if (wtSession != null) {
            return true;
        }
        // ConEmu/Cmder
        String conEmu = System.getenv("ConEmuANSI");
        return "ON".equalsIgnoreCase(conEmu);
    }

    public static String colorize(String text, String color) {
        if (!isSupported()) {
            return text;
        }
        return color + text + RESET;
    }

    public static String bold(String text) {
        if (!isSupported()) {
            return text;
        }
        return BOLD + text + RESET;
    }

    public static String dim(String text) {
        if (!isSupported()) {
            return text;
        }
        return DIM + text + RESET;
    }
}
