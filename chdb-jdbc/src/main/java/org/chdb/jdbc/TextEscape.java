package org.chdb.jdbc;

/**
 * Encodes a parameter value in the escaped-text form chDB parses bound parameters with.
 *
 * <h2>Why this exists</h2>
 * A bound parameter is not handed to the engine as opaque bytes. {@code
 * ReplaceQueryParameterVisitor::resolveParameterValueAsField} reads it with
 * {@code serialization->deserializeTextEscaped(...)} and then insists the whole value was
 * consumed:
 *
 * <pre>
 * ReadBufferFromString read_buffer{value};
 * serialization-&gt;deserializeTextEscaped(temp_column, read_buffer, format_settings);
 * if (!read_buffer.eof())
 *     throw Exception(BAD_QUERY_PARAMETER, "Value {} cannot be parsed as {} ...");
 * </pre>
 *
 * That is TSV field syntax. Three consequences, each of which is a bug if ignored:
 *
 * <ul>
 *   <li>a backslash starts an escape sequence, so {@code \'} arrives as {@code '} and the
 *       backslash is lost;
 *   <li>a raw newline or tab ends the field, so the value "is not parsed completely" and the
 *       query fails with ClickHouse error 457;
 *   <li>{@code \N} is the NULL marker, so a value that happens to be the two characters
 *       {@code \N} would arrive as NULL.
 * </ul>
 *
 * <h2>This is not SQL quoting</h2>
 * Nothing here protects against injection, and nothing here needs to: the value travels
 * outside the SQL text through {@code chdb_query_with_params_n} and is bound after the
 * statement has been parsed. A maximally hostile value can only ever become a wrong
 * <em>value</em> -- it cannot change what the statement does. This is a wire encoding for the
 * value channel, and its whole job is round-tripping the bytes the caller set.
 */
final class TextEscape {

    private TextEscape() {
    }

    /**
     * Escapes {@code value} so the engine's escaped-text reader reconstructs it exactly.
     *
     * <p>Only the characters that the reader treats specially are escaped. Everything else --
     * quotes, UTF-8 of any length, astral characters -- passes through untouched, because the
     * reader has no rule for them.
     */
    static String escape(String value) {
        int special = countSpecial(value);
        if (special == 0) {
            // The common case: no allocation and no copy.
            return value;
        }

        StringBuilder out = new StringBuilder(value.length() + special);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    // First, and for the same reason it is first in every escaping scheme:
                    // escaping it later would double the backslashes this pass introduces.
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\0':
                    out.append("\\0");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case 0x0b:
                    out.append("\\v");
                    break;
                case 0x07:
                    out.append("\\a");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    /** Extra characters {@link #escape(String)} would add, or 0 if it would change nothing. */
    private static int countSpecial(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (isSpecial(value.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isSpecial(char c) {
        switch (c) {
            case '\\':
            case '\n':
            case '\r':
            case '\t':
            case '\0':
            case '\b':
            case '\f':
            case 0x0b:
            case 0x07:
                return true;
            default:
                return false;
        }
    }

    /**
     * The escaped-text NULL marker.
     *
     * <p>Recognized by {@code SerializationNullable::deserializeTextEscaped}, so a parameter
     * bound to this reads as SQL NULL -- but only where the placeholder's declared type is
     * nullable. A {@code :String} placeholder would read it as the single character {@code N}.
     * {@link SqlParameterLexer#render} is what declares the right type per execution.
     */
    static final String NULL_MARKER = "\\N";
}
