package com.botwithus.bot.core.runlog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips what identifies a user from a line before it is written anywhere: the
 * spec's rules R1 to R7, in that order. One instance per run, because R1's
 * {@code Account#n} / {@code Player#n} numbering is stable within a run and
 * counts from 1 in first-seen order.
 *
 * <p>The known names are re-read from a supplier on every call, so a character
 * name the host learns after the run started is redacted from then on. The
 * patterns are rebuilt only when the set actually changes.</p>
 *
 * <p>Readings of the spec worth knowing (spec §3, revision 3):</p>
 * <ul>
 *   <li><b>R1 treats a space, {@code _}, a no-break space and {@code -} inside a
 *   name as the same character</b>, because the game writes the same name with
 *   any of them. A match needs a word boundary on both sides (no letter, digit
 *   or {@code _}), so a short name does not eat the middle of a longer word, and
 *   a name shorter than {@value #MIN_NAME_LENGTH} characters is ignored.</li>
 *   <li><b>R4 redacts any length for {@code password}, {@code passwd} and
 *   {@code secret}</b> and 8 or more characters for the other keywords. Its
 *   separator may be quoted on either side, which covers {@code ": "} and the
 *   compact JSON {@code ":"}.</li>
 *   <li><b>R5 base64 needs upper case, lower case and a digit</b> in the run, so a
 *   long path or identifier is not mistaken for one. Hex of 32 or more is
 *   redacted whatever its case.</li>
 * </ul>
 *
 * <p>Thread-safe: lines from several threads of one run may be redacted at once.</p>
 */
public final class Redactor {

    static final String ACCOUNT_LABEL = "Account#";
    static final String PLAYER_LABEL = "Player#";
    static final String EMAIL = "<email>";
    static final String TOKEN = "<token>";
    static final String REDACTED = "<redacted>";
    static final String IP = "<ip>";

    private static final String NAME_SEPARATORS = " _\u00A0-";
    private static final String NAME_SEPARATOR_CLASS = "[ _\\u00A0-]";
    private static final String NOT_WORD_BEFORE = "(?<![\\p{L}\\p{N}_])";
    private static final String NOT_WORD_AFTER = "(?![\\p{L}\\p{N}_])";
    /** Shortest name R1 acts on; a one-character name would match every lone letter. */
    static final int MIN_NAME_LENGTH = 2;
    private static final int NAME_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** Fewest hex digits an IPv6 candidate needs, so a bare {@code :::} is left alone. */
    private static final int MIN_IPV6_HEX_GROUPS = 2;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final Pattern JWT = Pattern.compile("eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+");
    private static final String KEY_SEPARATOR = "([\"']?[ \\t]*[:=][ \\t]*[\"']?)";
    private static final String KEY_VALUE_STOP = "[^\\s\"',;]";
    private static final Pattern SECRET_ANY_LENGTH = Pattern.compile(
            "(?i)(password|passwd|secret)" + KEY_SEPARATOR + "(" + KEY_VALUE_STOP + "+)");
    private static final Pattern SECRET_LONG = Pattern.compile(
            "(?i)(token|session|auth|key|cookie)" + KEY_SEPARATOR + "(" + KEY_VALUE_STOP + "{8,})");
    private static final Pattern HEX = Pattern.compile(
            "(?<![0-9A-Za-z])[0-9A-Fa-f]{32,}(?![0-9A-Za-z])");
    private static final Pattern BASE64 = Pattern.compile(
            "(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{40,}={0,2}(?![A-Za-z0-9+/=])");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}\\.){3}\\d{1,3}\\b");
    /** Three or more colons between hex groups; may end in an IPv4 tail R6 already replaced. */
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![\\w:.])(?:[0-9A-Fa-f]{0,4}:){3,8}(?:[0-9A-Fa-f]{1,4}|<ip>)?(?![\\w:])");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");
    private static final Pattern WINDOWS_HOME = Pattern.compile(
            "(?i)(?<![A-Za-z])[A-Z]:(\\\\+|/+)Users\\1[^\\\\/\"'\\r\\n]+\\1");
    private static final Pattern UNIX_HOME = Pattern.compile("(?<![\\w.])/home/[^/\\s\"']+/");

    private final Supplier<KnownNames> names;
    private final Object lock = new Object();
    // Guarded by lock.
    private KnownNames compiledFor;
    private Pattern namePattern;
    private Map<String, NameKind> kindByKey = Map.of();
    private final Map<String, String> replacementByKey = new HashMap<>();
    private final Map<NameKind, Integer> nextNumber = new HashMap<>();

    /** A redactor over a live view of the run's names. */
    public Redactor(Supplier<KnownNames> names) {
        this.names = names != null ? names : () -> KnownNames.NONE;
    }

    /** A redactor over a fixed set of names. */
    public static Redactor withNames(KnownNames names) {
        KnownNames fixed = names != null ? names : KnownNames.NONE;
        return new Redactor(() -> fixed);
    }

    /** Applies R1 to R7 to one line. */
    public String redact(String line) {
        return apply(line, true);
    }

    /**
     * Applies every rule except R5. Only for the header values the spec exempts
     * ({@code run_id}, {@code script_sha256}, {@code agent_build}), which are
     * host-written hashes that R5 would otherwise erase.
     */
    public String redactKeepingHashes(String line) {
        return apply(line, false);
    }

    private String apply(String line, boolean isHashRuleOn) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        String out = replaceNames(line);
        out = EMAIL_PATTERN.matcher(out).replaceAll(EMAIL);
        out = JWT.matcher(out).replaceAll(TOKEN);
        out = SECRET_ANY_LENGTH.matcher(out).replaceAll("$1$2" + REDACTED);
        out = SECRET_LONG.matcher(out).replaceAll("$1$2" + REDACTED);
        if (isHashRuleOn) {
            out = HEX.matcher(out).replaceAll(REDACTED);
            out = replaceEach(BASE64, out, Redactor::base64Replacement);
        }
        out = IPV4.matcher(out).replaceAll(IP);
        out = replaceEach(IPV6, out, Redactor::ipv6Replacement);
        out = WINDOWS_HOME.matcher(out).replaceAll(m -> Matcher.quoteReplacement("~" + m.group(1)));
        return UNIX_HOME.matcher(out).replaceAll("~/");
    }

    private String replaceNames(String line) {
        synchronized (lock) {
            refreshNames();
            if (namePattern == null) {
                return line;
            }
            return replaceEach(namePattern, line, this::numberFor);
        }
    }

    /** Rebuilds the R1 pattern when the supplier's set differs from the compiled one. */
    private void refreshNames() {
        KnownNames current = names.get();
        if (current == null) {
            current = KnownNames.NONE;
        }
        if (current.equals(compiledFor)) {
            return;
        }
        Map<String, NameKind> kinds = new LinkedHashMap<>();
        // Accounts first: a value known both ways is numbered as an account.
        current.accounts().stream().filter(Redactor::isLongEnough)
                .forEach(n -> kinds.putIfAbsent(keyOf(n), NameKind.ACCOUNT));
        current.players().stream().filter(Redactor::isLongEnough)
                .forEach(n -> kinds.putIfAbsent(keyOf(n), NameKind.PLAYER));
        List<String> alternatives = new ArrayList<>(kinds.keySet());
        // Longest first, so "Main Acc" wins over a shorter name inside it.
        alternatives.sort(Comparator.comparingInt(String::length).reversed());
        this.kindByKey = kinds;
        this.namePattern = alternatives.isEmpty() ? null : Pattern.compile(
                NOT_WORD_BEFORE + "(?:" + String.join("|", alternatives.stream()
                        .map(Redactor::nameRegex).toList()) + ")" + NOT_WORD_AFTER, NAME_FLAGS);
        this.compiledFor = current;
    }

    private String numberFor(Matcher match) {
        String key = keyOf(match.group());
        NameKind kind = kindByKey.getOrDefault(key, NameKind.ACCOUNT);
        return replacementByKey.computeIfAbsent(key, k -> {
            int n = nextNumber.merge(kind, 1, Integer::sum);
            return kind.label + n;
        });
    }

    private static boolean isLongEnough(String name) {
        return name.codePointCount(0, name.length()) >= MIN_NAME_LENGTH;
    }

    /** A base64-shaped run counts only when it mixes upper case, lower case and digits. */
    private static String base64Replacement(Matcher match) {
        String run = match.group();
        boolean hasUpper = run.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = run.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = run.chars().anyMatch(Character::isDigit);
        return hasUpper && hasLower && hasDigit ? REDACTED : run;
    }

    private static String ipv6Replacement(Matcher match) {
        Matcher groups = IPV6_GROUP.matcher(match.group());
        int count = 0;
        while (groups.find()) {
            count++;
        }
        boolean hasIpv4Tail = match.group().endsWith(IP);
        return count >= MIN_IPV6_HEX_GROUPS || hasIpv4Tail ? IP : match.group();
    }

    private static String replaceEach(Pattern pattern, String text, Function<Matcher, String> with) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(with.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Case- and separator-insensitive identity of a name, used for numbering. */
    static String keyOf(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(NAME_SEPARATORS.indexOf(c) >= 0 ? ' ' : c);
        }
        return sb.toString();
    }

    private static String nameRegex(String key) {
        StringBuilder sb = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c == ' ') {
                appendLiteral(sb, literal);
                sb.append(NAME_SEPARATOR_CLASS);
            } else {
                literal.append(c);
            }
        }
        appendLiteral(sb, literal);
        return sb.toString();
    }

    private static void appendLiteral(StringBuilder sb, StringBuilder literal) {
        if (!literal.isEmpty()) {
            sb.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    private enum NameKind {
        ACCOUNT(ACCOUNT_LABEL),
        PLAYER(PLAYER_LABEL);

        private final String label;

        NameKind(String label) {
            this.label = label;
        }
    }
}
