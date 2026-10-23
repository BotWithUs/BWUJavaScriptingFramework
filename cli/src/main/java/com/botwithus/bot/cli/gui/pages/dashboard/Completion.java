package com.botwithus.bot.cli.gui.pages.dashboard;

import java.util.List;
import java.util.Locale;

/** Tab completion for the console prompt: command names and aliases, matched case-insensitively. */
final class Completion {

    /**
     * What Tab does to {@code typed}.
     *
     * @param text    the prompt's text afterwards; unchanged when nothing matched or nothing was added
     * @param matches every name that starts with what was typed, for listing when there are several
     */
    record Result(String text, List<String> matches) { }

    private Completion() {}

    /**
     * One match completes to it; several complete to their longest common
     * prefix; none, or an empty prompt, leaves the text alone.
     */
    static Result complete(String typed, List<String> names) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        if (prefix.isEmpty()) {
            return new Result(typed, List.of());
        }
        List<String> matches = names.stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .toList();
        if (matches.size() == 1) {
            return new Result(matches.getFirst() + " ", matches);
        }
        if (matches.isEmpty()) {
            return new Result(typed, matches);
        }
        String common = matches.getFirst();
        for (String m : matches) {
            common = commonPrefix(common, m);
        }
        return new Result(common.length() > typed.length() ? common : typed, matches);
    }

    private static String commonPrefix(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i = 0;
        while (i < len && Character.toLowerCase(a.charAt(i)) == Character.toLowerCase(b.charAt(i))) {
            i++;
        }
        return a.substring(0, i);
    }
}
