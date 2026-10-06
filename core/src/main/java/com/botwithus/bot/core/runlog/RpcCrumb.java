package com.botwithus.bot.core.runlog;

import java.util.Map;

/**
 * The breadcrumb text for one RPC call: {@code <method> k=v k=v}.
 *
 * <p>A value longer than {@value #MAX_VALUE} characters is replaced by its
 * length, never cut: cutting can split a token so that the redactor no longer
 * recognises the half that remains. The whole detail is clipped only after
 * redaction, when the crumb is rendered.</p>
 */
final class RpcCrumb {

    static final String KIND = "rpc";
    /** Longest rendering of one parameter value. */
    static final int MAX_VALUE = 48;

    private RpcCrumb() {
    }

    static String describe(String method, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(method == null ? "?" : method);
        if (params == null) {
            return sb.toString();
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            sb.append(' ').append(e.getKey()).append('=').append(render(String.valueOf(e.getValue())));
            if (sb.length() > Breadcrumbs.MAX_DETAIL) {
                break;
            }
        }
        return sb.toString();
    }

    private static String render(String value) {
        String flat = value.replaceAll("\\s+", " ");
        return flat.length() <= MAX_VALUE ? flat : "<" + flat.length() + " chars>";
    }
}
