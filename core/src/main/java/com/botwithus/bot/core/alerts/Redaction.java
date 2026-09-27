package com.botwithus.bot.core.alerts;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shortens URLs to their host before they reach a log, an error or the UI.
 *
 * <p>A webhook URL <em>is</em> its secret — anyone holding it can post to the
 * channel — and an ntfy topic is readable by anyone who knows it. So a URL is
 * only ever shown as its host (and port, if one is given) followed by {@code /…};
 * user info, path, query and fragment are dropped.</p>
 */
public final class Redaction {

    private static final String ELLIPSIS = "…";
    private static final String HOST_SUFFIX = "/" + ELLIPSIS;
    private static final int NO_PORT = -1;

    /** scheme://[userinfo@]authority, then anything up to whitespace. Group "host" is the authority. */
    private static final Pattern URL = Pattern.compile(
            "\\b[A-Za-z][A-Za-z0-9+.-]*://(?:[^\\s/?#@]*@)?(?<host>[^\\s/?#]*)\\S*");

    private Redaction() {
    }

    /** {@code uri} as {@code host/…}, e.g. {@code discord.com/…}; just {@code …} with no host. */
    public static String url(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return ELLIPSIS;
        }
        String authority = uri.getPort() == NO_PORT ? host : host + ":" + uri.getPort();
        return authority + HOST_SUFFIX;
    }

    /** {@code text} with every URL in it replaced by {@code host/…}. */
    public static String scrub(String text) {
        Matcher matcher = URL.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group("host") + HOST_SUFFIX));
    }
}
