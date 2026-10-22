package com.fileverifier;

import java.nio.charset.StandardCharsets;

/**
 * Decodes the file names rsync prints, exactly as rsync encodes them.
 *
 * <p>Measured on rsync 3.1.3 and 3.4.0 (and matching the {@code "\\#%03o"} format string in
 * rsync's own logging code): the only escape is {@code \#ooo}, three <b>octal</b> digits for one
 * byte. A real newline comes out as {@code \#012}, a control byte as {@code \#001}, a backslash as
 * {@code \#134}, and the two UTF-8 bytes of e-acute as {@code \#303\#251} when rsync runs under a
 * non-UTF-8 locale. Everything else - including a backslash followed by digits inside an actual
 * filename, which is emitted as-is - is literal text. Decoding {@code \134} would invent a file
 * nobody asked for, so it is left alone.
 *
 * <p>Decoding happens in one pass over bytes, and the bytes are turned into text once at the end:
 * a multi-byte character can legally arrive as a run of escapes, so decoding character by character
 * would corrupt it.
 */
final class RsyncNameCodec {

    private RsyncNameCodec() {
    }

    /** Decodes rsync's {@code \#ooo} byte escapes and nothing else. */
    static String decode(String escaped) {
        if (escaped.indexOf('\\') < 0) {
            return escaped;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(escaped.length());
        int literalStart = 0;
        int i = 0;
        while (i < escaped.length()) {
            if (isEscape(escaped, i)) {
                if (literalStart < i) {
                    // Literal runs are encoded in bulk so that characters outside the Basic
                    // Multilingual Plane are not split into halves.
                    writeString(bytes, escaped.substring(literalStart, i));
                }
                bytes.write(octalValue(escaped, i));
                i += 5;
                literalStart = i;
            } else {
                i++;
            }
        }
        if (literalStart < escaped.length()) {
            writeString(bytes, escaped.substring(literalStart));
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** True when an escape ({@code \#} plus exactly three octal digits) starts at {@code at}. */
    private static boolean isEscape(String value, int at) {
        if (at + 5 > value.length() || value.charAt(at) != '\\' || value.charAt(at + 1) != '#') {
            return false;
        }
        return isOctalDigit(value.charAt(at + 2)) && isOctalDigit(value.charAt(at + 3))
                && isOctalDigit(value.charAt(at + 4));
    }

    private static int octalValue(String value, int at) {
        return (value.charAt(at + 2) - '0') * 64
                + (value.charAt(at + 3) - '0') * 8
                + (value.charAt(at + 4) - '0');
    }

    private static void writeString(java.io.ByteArrayOutputStream bytes, String value) {
        try {
            bytes.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean isOctalDigit(char c) {
        return c >= '0' && c <= '7';
    }

    /**
     * The file name in one of rsync's mention lines.
     *
     * <p>With {@code --out-format='%i|%n'} the two fields are separated by the first {@code '|'} and
     * the name is the rest of the line, kept verbatim: a name containing {@code '|'}, a leading or
     * trailing space, or a tab survives intact. Without an out-format, rsync separates the itemize
     * column from the name with a single space, and the same "first separator, rest verbatim" rule
     * applies. Both forms are accepted here so the engine keeps working whichever way it asked.
     */
    static String nameOf(String line) {
        if (line.isEmpty()) {
            return null;
        }
        int separator = line.indexOf('|');
        int splitAt = separator >= 0 ? separator : line.indexOf(' ');
        if (splitAt < 0 || splitAt == line.length() - 1) {
            return null;
        }
        String name = line.substring(splitAt + 1);
        if (separator < 0) {
            // rsync pads the name with the separator space; a name may itself start with a space,
            // so only the one separating space is dropped - never anything else.
            name = name.startsWith(" ") ? name.substring(1) : name;
        }
        name = stripTrailingLineBreak(name);
        return name.isEmpty() ? null : decode(name);
    }

    private static String stripTrailingLineBreak(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * How a name is shown to a person. The identity stays exactly as scanned; only the rendering
     * escapes the characters that would otherwise forge a log line or break a table row.
     */
    static String forDisplay(String name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        out.append("\\#").append(String.format("%03o", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
