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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final int FOLDED_WRAP_CHARS = 80;
    private static final String NAMED_ESCAPES = "\\\"\0\u0007\b\t\n\u000b\f\r\u001b\u0085\u2028\u2029";
    private static final String NAMED_ESCAPE_LETTERS = "\\\"0abtnvfreNLP";

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
        if (value instanceof Map && !asMap(value).isEmpty())
        {
            sb.append('\n');
            dumpMap(sb, asMap(value), indent + 2);
        }
        else if (value instanceof List && !asList(value).isEmpty())
        {
            sb.append('\n');
            dumpList(sb, asList(value), indent + 2);
        }
        else
        {
            sb.append(' ');
            appendScalar(sb, value, indent);
            sb.append('\n');
        }
    }

    private static void dumpList(StringBuilder sb, List<Object> list, int indent)
    {
        for (Object item : list)
        {
            sb.append(spaces(indent)).append("- ");
            if (item instanceof Map && !asMap(item).isEmpty())
            {
                boolean first = true;
                for (Map.Entry<String, Object> e : asMap(item).entrySet())
                {
                    if (!first)
                        sb.append(spaces(indent + 2));
                    dumpEntry(sb, e.getKey(), e.getValue(), indent + 2);
                    first = false;
                }
            }
            else if (item instanceof List && !asList(item).isEmpty())
            {
                throw new IllegalArgumentException("nested sequences are not supported");
            }
            else
            {
                appendScalar(sb, item, indent + 2);
                sb.append('\n');
            }
        }
    }

    private static void appendScalar(StringBuilder sb, Object value, int indent)
    {
        if (value == null)
            sb.append("null");
        else if (value instanceof Boolean || value instanceof Number)
            sb.append(value);
        else if (value instanceof byte[])
            sb.append("!!binary ").append(Base64.getEncoder().encodeToString((byte[]) value));
        else if (value instanceof Map)
            sb.append("{}");
        else if (value instanceof List)
            sb.append("[]");
        else
            appendString(sb, String.valueOf(value), indent);
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
        sb.append(trailingNewlines == 0 ? "-" : trailingNewlines == 1 ? "" : "+");
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

    private static void appendQuoted(StringBuilder sb, String s)
    {
        sb.append('"');
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
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

        sb.append('"');
    }

    /** Port of {@code serde_saphyr}'s {@code is_plain_value_safe} for a block-context value. */
    private static boolean isPlainValueSafe(String s)
    {
        if (s.isEmpty() || AMBIGUOUS_WORDS.contains(s.toLowerCase()) || s.matches(NUMBER_PATTERN))
            return false;
        char first = s.charAt(0);
        if (Character.isWhitespace(first) && first < 0x80)
            return false;
        if ((first == '-' || first == '?') && (s.length() == 1 || (Character.isWhitespace(s.charAt(1)) && s.charAt(1) < 0x80)))
            return false;
        if (",:[]{}#&*!|>'\"%@`".indexOf(first) >= 0)
            return false;
        if (s.contains(": ") || s.trim().endsWith(":") || s.indexOf('#') >= 0)
            return false;
        for (int i = 0; i < s.length(); i++)
        {
            if (isControl(s.charAt(i)))
                return false;
        }

        return true;
    }

    private static boolean isControl(char c)
    {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
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
        {
            String s = ((String) value).trim();
            if (s.startsWith("0x") || s.startsWith("0X"))
                return (int) Long.parseLong(s.substring(2), 16);
            return (int) Long.parseLong(s);
        }
        throw new IllegalArgumentException("expected an integer, got " + typeName(value));
    }

    public static boolean asBoolean(Object value)
    {
        if (value instanceof Boolean)
            return (Boolean) value;
        if (value instanceof String)
        {
            String s = ((String) value).trim().toLowerCase();
            if ("true".equals(s) || "yes".equals(s) || "on".equals(s))
                return true;
            if ("false".equals(s) || "no".equals(s) || "off".equals(s))
                return false;
        }
        throw new IllegalArgumentException("expected a boolean, got " + typeName(value));
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
            while (i < lines.length)
            {
                skipEmpty();
                if (i >= lines.length)
                    break;
                int here = indentOf(lines[i]);
                if (here < indent)
                    break;
                if (here > indent)
                    throw new IllegalArgumentException("unexpected indent at line " + (i + 1) + ": " + lines[i]);
                if (isListItem(lines[i], here))
                    break;

                String raw = stripComment(lines[i].substring(here));
                int colon = indexOfKeyColon(raw);
                if (colon < 0)
                    throw new IllegalArgumentException("expected 'key:' at line " + (i + 1) + ": " + lines[i]);
                String key = unquote(raw.substring(0, colon).trim());
                String rest = raw.substring(colon + 1).trim();
                i++;
                Object value;
                if (rest.isEmpty())
                {
                    skipEmpty();
                    // Nested maps are indented; a block sequence may sit at the same column as
                    // the key (`overlays:\n- id: 0`), which is valid YAML and what ds-rom writes.
                    if (i < lines.length && indentOf(lines[i]) > indent)
                        value = parseBlock(indent);
                    else if (i < lines.length && indentOf(lines[i]) == indent
                            && isListItem(lines[i], indent))
                        value = parseList(indent);
                    else
                        value = null;
                }
                else if (rest.equals("|") || rest.equals("|-") || rest.equals("|+"))
                {
                    value = parseBlockScalar(indent, !rest.equals("|-"));
                }
                else
                {
                    value = parseScalar(rest);
                }
                map.put(key, value);
            }
            return map;
        }

        private List<Object> parseList(int indent)
        {
            List<Object> list = new ArrayList<Object>();
            while (i < lines.length)
            {
                skipEmpty();
                if (i >= lines.length)
                    break;
                int here = indentOf(lines[i]);
                if (here < indent)
                    break;
                if (!isListItem(lines[i], here))
                    break;

                String raw = stripComment(lines[i].substring(here + 1).trim());
                i++;
                if (raw.isEmpty())
                {
                    skipEmpty();
                    if (i < lines.length && indentOf(lines[i]) > indent)
                        list.add(parseBlock(indent));
                    else
                        list.add(null);
                }
                else if (indexOfKeyColon(raw) >= 0)
                {
                    // compact "- key: value" then more keys at greater indent
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    int colon = indexOfKeyColon(raw);
                    item.put(unquote(raw.substring(0, colon).trim()),
                            parseInlineOrNull(raw.substring(colon + 1).trim()));
                    skipEmpty();
                    if (i < lines.length && indentOf(lines[i]) > indent && !isListItem(lines[i], indentOf(lines[i])))
                    {
                        Map<String, Object> rest = parseMap(indentOf(lines[i]));
                        item.putAll(rest);
                    }
                    list.add(item);
                }
                else
                {
                    list.add(parseScalar(raw));
                }
            }
            return list;
        }

        /** Reads a literal block scalar's lines, all indented past {@code keyIndent}. */
        private String parseBlockScalar(int keyIndent, boolean keepNewline)
        {
            List<String> body = new ArrayList<String>();
            int blockIndent = -1;
            while (i < lines.length)
            {
                String line = lines[i];
                if (line.trim().isEmpty())
                {
                    body.add("");
                    i++;
                    continue;
                }

                int here = indentOf(line);
                if (here <= keyIndent || (blockIndent >= 0 && here < blockIndent))
                    break;
                if (blockIndent < 0)
                    blockIndent = here;
                body.add(line.substring(blockIndent));
                i++;
            }

            while (!body.isEmpty() && body.get(body.size() - 1).isEmpty())
            {
                body.remove(body.size() - 1);
            }

            StringBuilder out = new StringBuilder();
            for (int n = 0; n < body.size(); n++)
            {
                if (n > 0)
                    out.append('\n');
                out.append(body.get(n));
            }

            if (keepNewline && !body.isEmpty())
                out.append('\n');

            return out.toString();
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
            boolean inSingle = false, inDouble = false;
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\'' && !inDouble)
                    inSingle = !inSingle;
                else if (c == '"' && !inSingle)
                    inDouble = !inDouble;
                else if (c == '#' && !inSingle && !inDouble)
                    return s.substring(0, i).trim();
            }
            return s.trim();
        }

        private static int indexOfKeyColon(String s)
        {
            boolean inSingle = false, inDouble = false;
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\'' && !inDouble)
                    inSingle = !inSingle;
                else if (c == '"' && !inSingle)
                    inDouble = !inDouble;
                else if (c == ':' && !inSingle && !inDouble)
                    return i;
            }
            return -1;
        }

        private static Object parseScalar(String s)
        {
            if (s.isEmpty() || s.equals("~") || s.equals("null") || s.equals("Null") || s.equals("NULL"))
                return null;
            if ((s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
                    || (s.startsWith("'") && s.endsWith("'") && s.length() >= 2))
                return unescape(s.substring(1, s.length() - 1), s.charAt(0) == '"');
            if (s.startsWith("!!binary "))
                return Base64.getMimeDecoder().decode(s.substring("!!binary ".length()).trim());
            if ("true".equals(s) || "True".equals(s) || "TRUE".equals(s))
                return Boolean.TRUE;
            if ("false".equals(s) || "False".equals(s) || "FALSE".equals(s))
                return Boolean.FALSE;
            if (s.startsWith("0x") || s.startsWith("0X"))
            {
                try
                {
                    return Long.parseLong(s.substring(2), 16);
                }
                catch (NumberFormatException ignored)
                {
                    return s;
                }
            }
            if (s.matches("-?\\d+"))
            {
                try
                {
                    return Long.parseLong(s);
                }
                catch (NumberFormatException ignored)
                {
                    return s;
                }
            }
            return s;
        }

        private static String unescape(String s, boolean doubleQuoted)
        {
            if (!doubleQuoted)
                return s.replace("''", "'");
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length())
                {
                    char n = s.charAt(++i);
                    switch (n)
                    {
                        case 'n':
                            out.append('\n');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case '\\':
                            out.append('\\');
                            break;
                        case '"':
                            out.append('"');
                            break;
                        case '0':
                            out.append('\0');
                            break;
                        case 'a':
                            out.append('\u0007');
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'v':
                            out.append('\u000b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'e':
                            out.append('\u001b');
                            break;
                        case 'N':
                            out.append('\u0085');
                            break;
                        case 'L':
                            out.append('\u2028');
                            break;
                        case 'P':
                            out.append('\u2029');
                            break;
                        case 'x':
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                            i += 2;
                            break;
                        case 'u':
                            if (i + 4 < s.length())
                            {
                                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            }
                            else
                            {
                                out.append(n);
                            }

                            break;
                        default:
                            out.append(n);
                            break;
                    }
                }
                else
                    out.append(c);
            }
            return out.toString();
        }

        private static String unquote(String s)
        {
            if ((s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
                    || (s.startsWith("'") && s.endsWith("'") && s.length() >= 2))
                return unescape(s.substring(1, s.length() - 1), s.charAt(0) == '"');
            return s;
        }
    }
}
