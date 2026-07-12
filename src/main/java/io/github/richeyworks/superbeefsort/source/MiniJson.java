package io.github.richeyworks.superbeefsort.source;

/**
 * A deliberately minimal JSON field extractor for <b>flat</b> objects — the ecosystem's
 * zero-dependency tradition applied to JSONL ingestion and trace files. It finds
 * {@code "field":} at the top level of one object and returns the following string or number
 * token. Documented limits, loudly: no nested objects/arrays as extracted values, standard
 * escapes only ({@code \" \\ \/ \n \t \r \b \f}; no {@code \.uXXXX}), one object per call.
 * If a workload needs more, that is a real JSON dependency decision for a future ADR — not a
 * silent extension of this scanner.
 */
public final class MiniJson {

    private MiniJson() {
    }

    /**
     * The raw value of {@code field} in a flat JSON object: unescaped string content, or the
     * number/literal token verbatim; {@code null} if absent or unparsable.
     */
    public static String field(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = -1;
        // Find the needle OUTSIDE any string value (scan with in-string tracking).
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                if (json.regionMatches(i, needle, 0, needle.length())) {
                    at = i + needle.length();
                    break;
                }
                inString = true;
            }
        }
        if (at < 0) {
            return null;
        }
        int i = skipWs(json, at);
        if (i >= json.length() || json.charAt(i) != ':') {
            return null;
        }
        i = skipWs(json, i + 1);
        if (i >= json.length()) {
            return null;
        }
        char c = json.charAt(i);
        if (c == '"') {
            StringBuilder out = new StringBuilder();
            i++;
            while (i < json.length()) {
                char ch = json.charAt(i);
                if (ch == '\\' && i + 1 < json.length()) {
                    char esc = json.charAt(++i);
                    out.append(switch (esc) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        default -> esc;          // \" \\ \/ and anything exotic: verbatim
                    });
                } else if (ch == '"') {
                    return out.toString();
                } else {
                    out.append(ch);
                }
                i++;
            }
            return null;                          // unterminated string
        }
        int start = i;
        while (i < json.length() && ",}\n\r\t ".indexOf(json.charAt(i)) < 0) {
            i++;
        }
        return i > start ? json.substring(start, i) : null;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Escape a string for embedding in a flat JSON object (the recorder's write side). */
    public static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
