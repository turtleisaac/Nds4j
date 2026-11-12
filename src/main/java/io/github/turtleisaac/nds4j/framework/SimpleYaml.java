/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 *
 * Nds4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Nds4j is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Nds4j. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.turtleisaac.nds4j.framework;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small YAML 1.1 subset reader for ds-rom ({@code dsrom}) extract configs: maps, lists of maps,
 * scalars (strings, decimals, {@code 0x} hex, booleans, {@code null}), literal blocks, and
 * {@code !!binary}. No anchors or flow-style nested collections — matching the files {@code serde}
 * writes for <a href="https://github.com/AetiasHax/ds-rom">ds-rom</a>.
 */
public final class SimpleYaml
{
    // Strings serde_saphyr quotes so they cannot read back as null, a YAML 1.1 bool, or a special float.
    private static final List<String> AMBIGUOUS_WORDS = Arrays.asList("~", "null", "true", "false", "yes", "no", "y", "n", "on", "off", "nan", "inf", "+inf", "-inf", ".nan", ".inf", "+.nan", "+.inf", "-.nan", "-.inf");
    private static final String NUMBER_PATTERN = "[+-]?(0x[0-9A-Fa-f_]+|0o[0-7_]+|0b[01_]+|([0-9][0-9_]*\\.[0-9_]*|\\.[0-9][0-9_]*)([eE][+-]?[0-9][0-9_]*)?|[0-9][0-9_]*[eE][+-]?[0-9][0-9_]*|[0-9][0-9_]*)";
    private static final String INDICATOR_CHARS = ",:[]{}#&*!|>'\"%@`";
    private static final int FOLDED_WRAP_CHARS = 80;
    private static final String NAMED_ESCAPES = "\\\"\0\u0007\b\t\n\u000b\f\r\u001b\u0085\u2028\u2029";
    private static final String NAMED_ESCAPE_LETTERS = "\\\"0abtnvfreNLP";
    private static final String BINARY_TAG = "!!binary ";
    private static final Map<String, Boolean> BOOLEAN_WORDS = new HashMap<String, Boolean>();

    static
    {
        for (String word : Arrays.asList("true", "yes", "on"))
            BOOLEAN_WORDS.put(word, Boolean.TRUE);

        for (String word : Arrays.asList("false", "no", "off"))
            BOOLEAN_WORDS.put(word, Boolean.FALSE);
    }

    private SimpleYaml()
    {
    }

    public static Object parse(String text)
    {
        if (text == null)
            return null;
        Parser p = new Parser(text);
        Object value = p.parseBlock(-1);
        return value == null ? new LinkedHashMap<String, Object>() : value;
    }

    /** Writes {@code root} as block-style YAML laid out the way {@code serde_saphyr} writes ds-rom's files. */
    public static String dump(Object root)
    {
        StringBuilder sb = new StringBuilder();
        if (root instanceof Map)
            dumpMap(sb, asMap(root), 0);
        else if (root instanceof List)
            dumpList(sb, asList(root), 0);
        else
            throw new IllegalArgumentException("expected a mapping or sequence at the document root, got " + typeName(root));
        return sb.toString();
    }

    private static void dumpMap(StringBuilder sb, Map<String, Object> map, int indent)
    {
        for (Map.Entry<String, Object> e : map.entrySet())
        {
            sb.append(spaces(indent));
            dumpEntry(sb, e.getKey(), e.getValue(), indent);
        }
    }

    /** Writes {@code key: value}; the caller has already written the indentation or the {@code "- "}. */
    private static void dumpEntry(StringBuilder sb, String key, Object value, int indent)
    {
        sb.append(key).append(':');
        if (isNonEmptyMap(value))
        {
            sb.append('\n');
            dumpMap(sb, asMap(value), indent + 2);
        }
        else if (isNonEmptyList(value))
        {
            sb.append('\n');
            dumpList(sb, asList(value), indent + 2);
        }
        else
        {
            sb.append(' ');
            appendScalarLine(sb, value, indent);
        }
    }

    private static void dumpList(StringBuilder sb, List<Object> list, int indent)
    {
        for (Object item : list)
        {
            sb.append(spaces(indent)).append("- ");
            dumpListItem(sb, item, indent + 2);
        }
    }

    /** Writes one sequence item; {@code indent} is the column after its {@code "- "}. */
    private static void dumpListItem(StringBuilder sb, Object item, int indent)
    {
        if (isNonEmptyMap(item))
            dumpCompactMap(sb, asMap(item), indent);
        else if (isNonEmptyList(item))
            throw new IllegalArgumentException("nested sequences are not supported");
        else
            appendScalarLine(sb, item, indent);
    }

    /** Writes a mapping whose first key shares the line with its {@code "- "}. */
    private static void dumpCompactMap(StringBuilder sb, Map<String, Object> map, int indent)
    {
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet())
        {
            if (!first)
                sb.append(spaces(indent));
            dumpEntry(sb, e.getKey(), e.getValue(), indent);
            first = false;
        }
    }

    private static boolean isNonEmptyMap(Object value)
    {
        return value instanceof Map && !((Map<?, ?>) value).isEmpty();
    }

    private static boolean isNonEmptyList(Object value)
    {
        return value instanceof List && !((List<?>) value).isEmpty();
    }

    private static void appendScalarLine(StringBuilder sb, Object value, int indent)
    {
        appendScalar(sb, value, indent);
        sb.append('\n');
    }

    private static void appendScalar(StringBuilder sb, Object value, int indent)
    {
        String literal = literalScalar(value);
        if (literal != null)
            sb.append(literal);
        else
            appendString(sb, String.valueOf(value), indent);
    }

    /** The fixed spelling of a non-string scalar, or {@code null} when {@code value} is written as a string. */
    private static String literalScalar(Object value)
    {
        if (value == null)
            return "null";
        if (value instanceof Boolean || value instanceof Number)
            return String.valueOf(value);
        if (value instanceof byte[])
            return BINARY_TAG + Base64.getEncoder().encodeToString((byte[]) value);

        return emptyCollection(value);
    }

    private static String emptyCollection(Object value)
    {
        if (value instanceof Map)
            return "{}";

        return value instanceof List ? "[]" : null;
    }

    /** Picks plain, literal block, or double-quoted style the way {@code serde_saphyr} 0.0.19 does. */
    private static void appendString(StringBuilder sb, String s, int indent)
    {
        if (s.indexOf('\n') >= 0)
        {
            String content = s.replaceAll("\n+$", "");
            if (s.codePointCount(0, s.length()) > FOLDED_WRAP_CHARS || isPlainValueSafe(content.replace('\n', ' ')))
            {
                appendLiteral(sb, s, content, indent);
                return;
            }
        }
        else if (isPlainValueSafe(s))
        {
            sb.append(s);
            return;
        }

        appendQuoted(sb, s);
    }

    private static void appendLiteral(StringBuilder sb, String s, String content, int indent)
    {
        int trailingNewlines = s.length() - content.length();
        sb.append('|');
        if (content.startsWith(" "))
            sb.append(indent + 2);
        sb.append(chompIndicator(trailingNewlines));
        String pad = spaces(indent + 2);
        for (String line : content.split("\n", -1))
        {
            sb.append('\n').append(pad).append(line);
        }

        for (int i = 1; i < trailingNewlines; i++)
        {
            sb.append('\n').append(pad);
        }
    }

    private static String chompIndicator(int trailingNewlines)
    {
        if (trailingNewlines == 0)
            return "-";

        return trailingNewlines == 1 ? "" : "+";
    }

    private static void appendQuoted(StringBuilder sb, String s)
    {
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
            appendQuotedChar(sb, s.charAt(i));

        sb.append('"');
    }

    private static void appendQuotedChar(StringBuilder sb, char c)
    {
        int named = NAMED_ESCAPES.indexOf(c);
        if (named >= 0)
            sb.append('\\').append(NAMED_ESCAPE_LETTERS.charAt(named));
        else if (c == 0xFEFF)
            sb.append("\\uFEFF");
        else if (isControl(c))
            sb.append(String.format("\\x%02X", (int) c));
        else
            sb.append(c);
    }

    /** Port of {@code serde_saphyr}'s {@code is_plain_value_safe} for a block-context value. */
    private static boolean isPlainValueSafe(String s)
    {
        return !s.isEmpty() && !isAmbiguousWord(s) && !startsUnsafe(s) && !hasUnsafeContent(s);
    }

    private static boolean isAmbiguousWord(String s)
    {
        return AMBIGUOUS_WORDS.contains(s.toLowerCase()) || s.matches(NUMBER_PATTERN);
    }

    private static boolean startsUnsafe(String s)
    {
        char first = s.charAt(0);

        return isAsciiWhitespace(first) || INDICATOR_CHARS.indexOf(first) >= 0 || isBareIndicator(s);
    }

    /** A {@code -} or {@code ?} that would read as a sequence entry or complex key. */
    private static boolean isBareIndicator(String s)
    {
        char first = s.charAt(0);

        return (first == '-' || first == '?') && (s.length() == 1 || isAsciiWhitespace(s.charAt(1)));
    }

    private static boolean hasUnsafeContent(String s)
    {
        return s.contains(": ") || s.trim().endsWith(":") || s.indexOf('#') >= 0 || containsControl(s);
    }

    private static boolean containsControl(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            if (isControl(s.charAt(i)))
                return true;
        }

        return false;
    }

    private static boolean isAsciiWhitespace(char c)
    {
        return Character.isWhitespace(c) && c < 0x80;
    }

    private static boolean isControl(char c)
    {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }

    private static boolean hasHexPrefix(String s)
    {
        return s.regionMatches(true, 0, "0x", 0, 2);
    }

    private static String spaces(int n)
    {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++)
        {
            sb.append(' ');
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value)
    {
        if (value instanceof Map)
            return (Map<String, Object>) value;
        throw new IllegalArgumentException("expected a YAML mapping, got " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value)
    {
        if (value instanceof List)
            return (List<Object>) value;
        throw new IllegalArgumentException("expected a YAML sequence, got " + typeName(value));
    }

    public static String asString(Object value)
    {
        if (value == null)
            return null;
        return String.valueOf(value);
    }

    public static int asInt(Object value)
    {
        if (value instanceof Number)
            return ((Number) value).intValue();
        if (value instanceof String)
            return parseIntText(((String) value).trim());
        throw new IllegalArgumentException("expected an integer, got " + typeName(value));
    }

    private static int parseIntText(String s)
    {
        if (hasHexPrefix(s))
            return (int) Long.parseLong(s.substring(2), 16);

        return (int) Long.parseLong(s);
    }

    public static boolean asBoolean(Object value)
    {
        if (value instanceof Boolean)
            return (Boolean) value;
        Boolean word = value instanceof String ? BOOLEAN_WORDS.get(((String) value).trim().toLowerCase()) : null;
        if (word == null)
            throw new IllegalArgumentException("expected a boolean, got " + typeName(value));

        return word;
    }

    public static Object get(Map<String, Object> map, String key)
    {
        return map == null ? null : map.get(key);
    }

    public static String getString(Map<String, Object> map, String key, String fallback)
    {
        Object v = get(map, key);
        return v == null ? fallback : asString(v);
    }

    public static int getInt(Map<String, Object> map, String key, int fallback)
    {
        Object v = get(map, key);
        return v == null ? fallback : asInt(v);
    }

    public static boolean getBoolean(Map<String, Object> map, String key, boolean fallback)
    {
        Object v = get(map, key);
        return v == null ? fallback : asBoolean(v);
    }

    private static String typeName(Object value)
    {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser
    {
        private static final Set<String> NULL_WORDS = new HashSet<String>(Arrays.asList("", "~", "null", "Null", "NULL"));
        private static final Set<String> TRUE_WORDS = new HashSet<String>(Arrays.asList("true", "True", "TRUE"));
        private static final Set<String> FALSE_WORDS = new HashSet<String>(Arrays.asList("false", "False", "FALSE"));
        private static final Set<String> BLOCK_SCALAR_HEADERS = new HashSet<String>(Arrays.asList("|", "|-", "|+"));

        private final String[] lines;
        private int i;

        Parser(String text)
        {
            this.lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        }

        Object parseBlock(int parentIndent)
        {
            skipEmpty();
            if (i >= lines.length)
                return null;
            int indent = indentOf(lines[i]);
            if (indent <= parentIndent)
                return null;
            if (isListItem(lines[i], indent))
                return parseList(indent);
            return parseMap(indent);
        }

        private Map<String, Object> parseMap(int indent)
        {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            while (atMapEntry(indent))
                parseMapEntry(map, indent);

            return map;
        }

        /** Whether the next content line is a {@code key:} line of the mapping at {@code indent}. */
        private boolean atMapEntry(int indent)
        {
            skipEmpty();
            if (i >= lines.length)
                return false;
            int here = indentOf(lines[i]);
            if (here > indent)
                throw new IllegalArgumentException("unexpected indent at line " + (i + 1) + ": " + lines[i]);

            return here == indent && !isListItem(lines[i], here);
        }

        private void parseMapEntry(Map<String, Object> map, int indent)
        {
            String raw = stripComment(lines[i].substring(indent));
            int colon = indexOfKeyColon(raw);
            if (colon < 0)
                throw new IllegalArgumentException("expected 'key:' at line " + (i + 1) + ": " + lines[i]);

            String key = unquote(raw.substring(0, colon).trim());
            String rest = raw.substring(colon + 1).trim();
            i++;
            map.put(key, parseMapValue(rest, indent));
        }

        private Object parseMapValue(String rest, int indent)
        {
            if (rest.isEmpty())
                return parseNestedValue(indent);
            if (BLOCK_SCALAR_HEADERS.contains(rest))
                return parseBlockScalar(indent, !rest.equals("|-"));

            return parseScalar(rest);
        }

        /** Reads the block under a bare {@code key:}, or {@code null} when there is none. */
        private Object parseNestedValue(int indent)
        {
            skipEmpty();
            if (hasLineIndentedPast(indent))
                return parseBlock(indent);
            // A block sequence may sit at the key's own column (`overlays:\n- id: 0`), which ds-rom writes.
            if (i < lines.length && indentOf(lines[i]) == indent && isListItem(lines[i], indent))
                return parseList(indent);

            return null;
        }

        private List<Object> parseList(int indent)
        {
            List<Object> list = new ArrayList<Object>();
            while (atListItem(indent))
                list.add(parseListItem(indent));

            return list;
        }

        private boolean atListItem(int indent)
        {
            skipEmpty();
            if (i >= lines.length)
                return false;
            int here = indentOf(lines[i]);

            return here >= indent && isListItem(lines[i], here);
        }

        private Object parseListItem(int indent)
        {
            String raw = stripComment(lines[i].substring(indentOf(lines[i]) + 1).trim());
            i++;
            if (raw.isEmpty())
                return parseNestedItem(indent);
            int colon = indexOfKeyColon(raw);
            if (colon >= 0)
                return parseCompactMap(raw, colon, indent);

            return parseScalar(raw);
        }

        private Object parseNestedItem(int indent)
        {
            skipEmpty();
            if (hasLineIndentedPast(indent))
                return parseBlock(indent);

            return null;
        }

        /** Reads {@code - key: value} plus any further keys indented under it. */
        private Map<String, Object> parseCompactMap(String raw, int colon, int indent)
        {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put(unquote(raw.substring(0, colon).trim()), parseInlineOrNull(raw.substring(colon + 1).trim()));
            skipEmpty();
            if (hasLineIndentedPast(indent) && !isListItem(lines[i], indentOf(lines[i])))
                item.putAll(parseMap(indentOf(lines[i])));

            return item;
        }

        private boolean hasLineIndentedPast(int indent)
        {
            return i < lines.length && indentOf(lines[i]) > indent;
        }

        /** Reads a literal block scalar's lines, all indented past {@code keyIndent}. */
        private String parseBlockScalar(int keyIndent, boolean keepNewline)
        {
            List<String> body = readBlockLines(keyIndent);
            dropTrailingBlankLines(body);
            String text = String.join("\n", body);

            return keepNewline && !body.isEmpty() ? text + "\n" : text;
        }

        private List<String> readBlockLines(int keyIndent)
        {
            List<String> body = new ArrayList<String>();
            int blockIndent = -1;
            while (i < lines.length && isBlockLine(lines[i], keyIndent, blockIndent))
            {
                String line = lines[i];
                i++;
                if (blockIndent < 0 && !isBlank(line))
                    blockIndent = indentOf(line);
                body.add(isBlank(line) ? "" : line.substring(blockIndent));
            }

            return body;
        }

        /** Whether {@code line} still belongs to a block scalar whose content starts at {@code blockIndent}. */
        private static boolean isBlockLine(String line, int keyIndent, int blockIndent)
        {
            if (isBlank(line))
                return true;
            int here = indentOf(line);

            return here > keyIndent && (blockIndent < 0 || here >= blockIndent);
        }

        private static void dropTrailingBlankLines(List<String> body)
        {
            while (!body.isEmpty() && body.get(body.size() - 1).isEmpty())
                body.remove(body.size() - 1);
        }

        private static boolean isBlank(String line)
        {
            return line.trim().isEmpty();
        }

        private Object parseInlineOrNull(String rest)
        {
            return rest.isEmpty() ? null : parseScalar(rest);
        }

        private void skipEmpty()
        {
            while (i < lines.length)
            {
                String t = lines[i].trim();
                if (t.isEmpty() || t.startsWith("#") || t.equals("---") || t.equals("..."))
                {
                    i++;
                    continue;
                }
                break;
            }
        }

        private static boolean isListItem(String line, int indent)
        {
            String s = line.substring(indent);
            return s.startsWith("- ") || s.equals("-");
        }

        private static int indentOf(String line)
        {
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ')
                n++;
            return n;
        }

        private static String stripComment(String s)
        {
            int hash = indexOfUnquoted(s, '#');

            return (hash < 0 ? s : s.substring(0, hash)).trim();
        }

        private static int indexOfKeyColon(String s)
        {
            return indexOfUnquoted(s, ':');
        }

        /** The first {@code target} outside single or double quotes, or -1. */
        private static int indexOfUnquoted(String s, char target)
        {
            char quote = 0;
            for (int n = 0; n < s.length(); n++)
            {
                char c = s.charAt(n);
                if (quote == 0 && c == target)
                    return n;
                quote = nextQuote(quote, c);
            }

            return -1;
        }

        /** The quote open after {@code c}, given {@code quote} was open before it (0 for none). */
        private static char nextQuote(char quote, char c)
        {
            if (quote != 0)
                return c == quote ? 0 : quote;

            return c == '\'' || c == '"' ? c : 0;
        }

        private static Object parseScalar(String s)
        {
            if (NULL_WORDS.contains(s))
                return null;
            if (isQuoted(s))
                return unquote(s);
            if (s.startsWith(BINARY_TAG))
                return Base64.getMimeDecoder().decode(s.substring(BINARY_TAG.length()).trim());
            if (TRUE_WORDS.contains(s))
                return Boolean.TRUE;
            if (FALSE_WORDS.contains(s))
                return Boolean.FALSE;

            return parseNumberOrString(s);
        }

        private static Object parseNumberOrString(String s)
        {
            if (hasHexPrefix(s))
                return parseLongOr(s, s.substring(2), 16);
            if (s.matches("-?\\d+"))
                return parseLongOr(s, s, 10);

            return s;
        }

        private static Object parseLongOr(String fallback, String digits, int radix)
        {
            try
            {
                return Long.parseLong(digits, radix);
            }
            catch (NumberFormatException ignored)
            {
                return fallback;
            }
        }

        private static boolean isQuoted(String s)
        {
            if (s.length() < 2)
                return false;
            char first = s.charAt(0);

            return (first == '"' || first == '\'') && s.charAt(s.length() - 1) == first;
        }

        private static String unquote(String s)
        {
            if (!isQuoted(s))
                return s;
            String body = s.substring(1, s.length() - 1);

            return s.charAt(0) == '"' ? unescapeDouble(body) : body.replace("''", "'");
        }

        private static String unescapeDouble(String s)
        {
            StringBuilder out = new StringBuilder(s.length());
            int n = 0;
            while (n < s.length())
                n = appendUnescaped(out, s, n);

            return out.toString();
        }

        /** Appends the character or escape at {@code n} and returns the index after it. */
        private static int appendUnescaped(StringBuilder out, String s, int n)
        {
            char c = s.charAt(n);
            if (c != '\\' || n + 1 >= s.length())
            {
                out.append(c);
                return n + 1;
            }

            return appendEscape(out, s, n + 1);
        }

        /** Appends the escape whose letter is at {@code n} and returns the index after it. */
        private static int appendEscape(StringBuilder out, String s, int n)
        {
            char letter = s.charAt(n);
            int named = NAMED_ESCAPE_LETTERS.indexOf(letter);
            if (named >= 0)
                out.append(NAMED_ESCAPES.charAt(named));
            else if (letter == 'x')
                return appendHexEscape(out, s, n, 2);
            else if (letter == 'u' && n + 4 < s.length())
                return appendHexEscape(out, s, n, 4);
            else
                out.append(letter);

            return n + 1;
        }

        private static int appendHexEscape(StringBuilder out, String s, int n, int digits)
        {
            int end = n + 1 + digits;
            out.append((char) Integer.parseInt(s.substring(n + 1, end), 16));

            return end;
        }
    }
}
