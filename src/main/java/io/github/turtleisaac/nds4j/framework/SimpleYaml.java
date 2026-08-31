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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small YAML 1.1 subset reader for ds-rom ({@code dsrom}) extract configs: maps, lists of maps,
 * scalars (strings, decimals, {@code 0x} hex, booleans, {@code null}). No anchors, tags, or
 * flow-style nested collections — matching the files {@code serde} writes for
 * <a href="https://github.com/AetiasHax/ds-rom">ds-rom</a>.
 */
public final class SimpleYaml
{
    private SimpleYaml() {}

    public static Object parse(String text)
    {
        if (text == null) return null;
        Parser p = new Parser(text);
        Object value = p.parseBlock(-1);
        return value == null ? new LinkedHashMap<String, Object>() : value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value)
    {
        if (value instanceof Map) return (Map<String, Object>) value;
        throw new IllegalArgumentException("expected a YAML mapping, got " + typeName(value));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value)
    {
        if (value instanceof List) return (List<Object>) value;
        throw new IllegalArgumentException("expected a YAML sequence, got " + typeName(value));
    }

    public static String asString(Object value)
    {
        if (value == null) return null;
        return String.valueOf(value);
    }

    public static int asInt(Object value)
    {
        if (value instanceof Number) return ((Number) value).intValue();
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
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String)
        {
            String s = ((String) value).trim().toLowerCase();
            if ("true".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
            if ("false".equals(s) || "no".equals(s) || "off".equals(s)) return false;
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
            if (i >= lines.length) return null;
            int indent = indentOf(lines[i]);
            if (indent <= parentIndent) return null;
            if (isListItem(lines[i], indent)) return parseList(indent);
            return parseMap(indent);
        }

        private Map<String, Object> parseMap(int indent)
        {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            while (i < lines.length)
            {
                skipEmpty();
                if (i >= lines.length) break;
                int here = indentOf(lines[i]);
                if (here < indent) break;
                if (here > indent)
                    throw new IllegalArgumentException("unexpected indent at line " + (i + 1) + ": " + lines[i]);
                if (isListItem(lines[i], here)) break;

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
                if (i >= lines.length) break;
                int here = indentOf(lines[i]);
                if (here < indent) break;
                if (!isListItem(lines[i], here)) break;

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
            while (n < line.length() && line.charAt(n) == ' ') n++;
            return n;
        }

        private static String stripComment(String s)
        {
            boolean inSingle = false, inDouble = false;
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\'' && !inDouble) inSingle = !inSingle;
                else if (c == '"' && !inSingle) inDouble = !inDouble;
                else if (c == '#' && !inSingle && !inDouble) return s.substring(0, i).trim();
            }
            return s.trim();
        }

        private static int indexOfKeyColon(String s)
        {
            boolean inSingle = false, inDouble = false;
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\'' && !inDouble) inSingle = !inSingle;
                else if (c == '"' && !inSingle) inDouble = !inDouble;
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
            if ("true".equals(s) || "True".equals(s) || "TRUE".equals(s)) return Boolean.TRUE;
            if ("false".equals(s) || "False".equals(s) || "FALSE".equals(s)) return Boolean.FALSE;
            if (s.startsWith("0x") || s.startsWith("0X"))
            {
                try { return Long.parseLong(s.substring(2), 16); }
                catch (NumberFormatException ignored) { return s; }
            }
            if (s.matches("-?\\d+"))
            {
                try { return Long.parseLong(s); }
                catch (NumberFormatException ignored) { return s; }
            }
            return s;
        }

        private static String unescape(String s, boolean doubleQuoted)
        {
            if (!doubleQuoted) return s.replace("''", "'");
            StringBuilder out = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++)
            {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length())
                {
                    char n = s.charAt(++i);
                    switch (n)
                    {
                        case 'n': out.append('\n'); break;
                        case 't': out.append('\t'); break;
                        case 'r': out.append('\r'); break;
                        case '\\': out.append('\\'); break;
                        case '"': out.append('"'); break;
                        default: out.append(n); break;
                    }
                }
                else out.append(c);
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
