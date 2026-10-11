package com.atakmap.android.otsadmin;

import java.util.List;

/**
 * The small amount of JSON this plugin has to <i>write</i>.
 *
 * Reading uses {@code org.json}, which is the right tool and is already a
 * test dependency across this portfolio. Writing does not, for one reason:
 * {@code JSONObject} does not preserve key order, and a request body whose
 * fields move around between runs is miserable to assert on and miserable to
 * read in a log next to the server's own error. Six fields do not justify
 * giving that up.
 *
 * Plain Java, no Android imports.
 */
public final class OtsJson {

    private OtsJson() {
    }

    /** {@code "key":"value"}, with the value escaped. */
    public static String pair(String key, String value) {
        return quote(key) + ":" + quote(value == null ? "" : value);
    }

    /** {@code "key":["a","b"]} — needed because OTS wants a list. */
    public static String arrayPair(String key, List<String> values) {
        StringBuilder sb = new StringBuilder(quote(key)).append(":[");
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (i > 0)
                    sb.append(',');
                sb.append(quote(values.get(i)));
            }
        }
        return sb.append(']').toString();
    }

    /** Wrap pre-built pairs into an object. */
    public static String of(List<String> parts) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                sb.append(',');
            sb.append(parts.get(i));
        }
        return sb.append('}').toString();
    }

    /** A two-field object, the common case. */
    public static String object(String k1, String v1, String k2, String v2) {
        return "{" + pair(k1, v1) + "," + pair(k2, v2) + "}";
    }

    /** A one-field object. */
    public static String object(String key, String value) {
        return "{" + pair(key, value) + "}";
    }

    /**
     * A JSON string literal.
     *
     * Escapes the two characters that would break the document and the
     * control range that would make it invalid. Passwords are the reason
     * this is careful: a backslash or a quote in one is perfectly legal and
     * would otherwise produce a body the server cannot parse, reported back
     * as a login failure that looks like a wrong password.
     */
    public static String quote(String s) {
        if (s == null)
            return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20)
                        sb.append(String.format("\\u%04x", (int) c));
                    else
                        sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * A body with every password value replaced, for logging.
     *
     * The plugin logs what it sent when a call fails, and a request body is
     * the most useful thing in that line — but an administrator's password
     * must not reach logcat, which any app with READ_LOGS or anyone with a
     * cable can read.
     */
    public static String redact(String body) {
        if (body == null)
            return null;
        String out = body;
        // new_password was missing here until a test caught it: an
        // administrator resetting somebody else's password would have had
        // the new one written to logcat.
        for (String key : new String[] { "password", "confirm_password",
                "new_password" })
            out = out.replaceAll(quote(key) + "\\s*:\\s*\"(\\\\.|[^\"\\\\])*\"",
                    quote(key) + ":\"***\"");
        return out;
    }
}
