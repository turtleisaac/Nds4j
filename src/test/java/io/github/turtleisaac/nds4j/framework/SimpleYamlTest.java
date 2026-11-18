/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 */

package io.github.turtleisaac.nds4j.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SimpleYaml (ds-rom extract subset)")
class SimpleYamlTest
{
    @Test
    void parsesNestedConfig()
    {
        Map<String, Object> map = SimpleYaml.asMap(SimpleYaml.parse(
                "header: header.yaml\n"
                        + "arm9_bin: arm9/arm9.bin\n"
                        + "itcm:\n"
                        + "  bin: arm9/itcm.bin\n"
                        + "  config: arm9/itcm.yaml\n"
                        + "arm9_overlays: arm9_overlays/overlays.yaml\n"
                        + "files_dir: files/\n"));
        assertThat(SimpleYaml.getString(map, "header", null)).isEqualTo("header.yaml");
        assertThat(SimpleYaml.getString(map, "arm9_bin", null)).isEqualTo("arm9/arm9.bin");
        Map<String, Object> itcm = SimpleYaml.asMap(map.get("itcm"));
        assertThat(SimpleYaml.getString(itcm, "bin", null)).isEqualTo("arm9/itcm.bin");
        assertThat(map.get("arm7_overlays")).isNull();
    }

    @Test
    void parsesOverlayList()
    {
        Map<String, Object> map = SimpleYaml.asMap(SimpleYaml.parse(
                "table_signed: false\n"
                        + "overlays:\n"
                        + "- id: 0\n"
                        + "  base_address: 0x021E5900\n"
                        + "  file_id: 0\n"
                        + "  compressed: true\n"
                        + "  file_name: ov000.bin\n"
                        + "- id: 1\n"
                        + "  file_id: 1\n"
                        + "  file_name: ov001.bin\n"));
        List<Object> overlays = SimpleYaml.asList(map.get("overlays"));
        assertThat(overlays).hasSize(2);
        Map<String, Object> ov0 = SimpleYaml.asMap(overlays.get(0));
        assertThat(SimpleYaml.getInt(ov0, "id", -1)).isEqualTo(0);
        assertThat(SimpleYaml.getInt(ov0, "base_address", 0)).isEqualTo(0x021E5900);
        assertThat(SimpleYaml.getBoolean(ov0, "compressed", false)).isTrue();
        assertThat(SimpleYaml.getString(ov0, "file_name", null)).isEqualTo("ov000.bin");
        assertThat(SimpleYaml.getBoolean(map, "table_signed", true)).isFalse();
    }

    @Test
    void quotedAndNullScalars()
    {
        Map<String, Object> map = SimpleYaml.asMap(SimpleYaml.parse(
                "title: \"POKEMON HG\"\n"
                        + "gamecode: IPKE\n"
                        + "arm7_overlays: null\n"
                        + "empty:\n"));
        assertThat(SimpleYaml.getString(map, "title", null)).isEqualTo("POKEMON HG");
        assertThat(SimpleYaml.getString(map, "gamecode", null)).isEqualTo("IPKE");
        assertThat(map.get("arm7_overlays")).isNull();
        assertThat(map.get("empty")).isNull();
    }

    @Test
    void dumpMatchesSerdeSaphyrLayout()
    {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("makercode", "01");
        root.put("title", "POKEMON PL");
        root.put("arm7_overlays", null);
        Map<String, Object> ov = new LinkedHashMap<String, Object>();
        ov.put("id", 0);
        ov.put("compressed", true);
        root.put("overlays", Arrays.<Object> asList(ov));
        root.put("bytes", Arrays.<Object> asList(1, 2));
        Map<String, Object> kind = new LinkedHashMap<String, Object>();
        kind.put("Unknown", 2);
        root.put("kind", kind);
        assertThat(SimpleYaml.dump(root))
                .isEqualTo("makercode: \"01\"\n"
                        + "title: POKEMON PL\n"
                        + "arm7_overlays: null\n"
                        + "overlays:\n"
                        + "  - id: 0\n"
                        + "    compressed: true\n"
                        + "bytes:\n"
                        + "  - 1\n"
                        + "  - 2\n"
                        + "kind:\n"
                        + "  Unknown: 2\n");
    }

    @Test
    void dumpPicksLiteralBlockOnlyWhenTheJoinedLinesArePlainSafe()
    {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("plain", "Pokémon Platinum\nNintendo");
        root.put("colon", "Animal Crossing: Wild World\nNintendo");
        String yaml = SimpleYaml.dump(root);
        assertThat(yaml)
                .isEqualTo("plain: |-\n"
                        + "  Pokémon Platinum\n"
                        + "  Nintendo\n"
                        + "colon: \"Animal Crossing: Wild World\\nNintendo\"\n");
        Map<String, Object> back = SimpleYaml.asMap(SimpleYaml.parse(yaml));
        assertThat(back.get("plain"))
                .isEqualTo("Pokémon Platinum\nNintendo");
        assertThat(back.get("colon"))
                .isEqualTo("Animal Crossing: Wild World\nNintendo");
    }

    @Test
    void binaryRoundTrips()
    {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("rsa_signature", new byte[] { 1, 2, (byte) 0xFF });
        String yaml = SimpleYaml.dump(root);
        assertThat(yaml)
                .isEqualTo("rsa_signature: !!binary AQL/\n");
        assertThat((byte[]) SimpleYaml.asMap(SimpleYaml.parse(yaml)).get("rsa_signature"))
                .containsExactly(1, 2, 0xFF);
    }

    @Test
    void parsesEmptyAndMissingDocuments()
    {
        assertThat(SimpleYaml.parse(null))
                .isNull();
        assertThat(SimpleYaml.asMap(SimpleYaml.parse("")))
                .isEmpty();
        assertThat(SimpleYaml.asMap(SimpleYaml.parse("# only a comment\n---\n...\n")))
                .isEmpty();
    }

    @Test
    void parsesCarriageReturnLineEndings()
    {
        Map<String, Object> map = parseMap("a: 1\r\nb: two\rc: 3");
        assertThat(map)
                .containsExactly(entry("a", 1L), entry("b", "two"), entry("c", 3L));
    }

    @Test
    void parsesNullBooleanAndNumberScalars()
    {
        Map<String, Object> map = parseMap("tilde: ~\n"
                + "upper: NULL\n"
                + "title: Null\n"
                + "t: True\n"
                + "f: FALSE\n"
                + "lower: false\n"
                + "word: yes\n"
                + "hex: 0x1F\n"
                + "upperHex: 0X10\n"
                + "badHex: 0xZZ\n"
                + "neg: -12\n"
                + "big: 99999999999999999999\n"
                + "decimal: 1.5\n");
        assertThat(map.get("tilde"))
                .isNull();
        assertThat(map.get("upper"))
                .isNull();
        assertThat(map.get("title"))
                .isNull();
        assertThat(map.get("t"))
                .isEqualTo(Boolean.TRUE);
        assertThat(map.get("f"))
                .isEqualTo(Boolean.FALSE);
        assertThat(map.get("lower"))
                .isEqualTo(Boolean.FALSE);
        assertThat(map.get("word"))
                .isEqualTo("yes");
        assertThat(map.get("hex"))
                .isEqualTo(31L);
        assertThat(map.get("upperHex"))
                .isEqualTo(16L);
        assertThat(map.get("badHex"))
                .isEqualTo("0xZZ");
        assertThat(map.get("neg"))
                .isEqualTo(-12L);
        assertThat(map.get("big"))
                .isEqualTo("99999999999999999999");
        assertThat(map.get("decimal"))
                .isEqualTo("1.5");
    }

    @Test
    void parsesNamedHexAndUnicodeEscapes()
    {
        Map<String, Object> map = parseMap("named: \"\\0\\a\\b\\t\\n\\v\\f\\r\\e\\N\\L\\P\\\\\\\"\"\n"
                + "hex: \"\\x41\\x7e\"\n"
                + "unicode: \"\\u00e9\\u2603\"\n"
                + "unknown: \"\\q\\/\"\n"
                + "shortUnicode: \"\\u12\"\n"
                + "trailingBackslash: \"ab\\\"\n"
                + "single: 'it''s \\n raw'\n");
        assertThat(map.get("named"))
                .isEqualTo("\0\u0007\b\t\n\u000b\f\r\u001b\u0085\u2028\u2029\\\"");
        assertThat(map.get("hex"))
                .isEqualTo("A~");
        assertThat(map.get("unicode"))
                .isEqualTo("\u00e9\u2603");
        assertThat(map.get("unknown"))
                .isEqualTo("q/");
        assertThat(map.get("shortUnicode"))
                .isEqualTo("u12");
        assertThat(map.get("trailingBackslash"))
                .isEqualTo("ab\\");
        assertThat(map.get("single"))
                .isEqualTo("it's \\n raw");
    }

    @Test
    void parsesQuotedKeys()
    {
        Map<String, Object> map = parseMap("\"a:b\": 1\n"
                + "'c d': two\n"
                + "\"\": empty\n"
                + "\"esc\\tkey\": 3\n");
        assertThat(map)
                .containsExactly(entry("a:b", 1L), entry("c d", "two"), entry("", "empty"), entry("esc\tkey", 3L));
    }

    @Test
    void stripsCommentsOutsideQuotes()
    {
        Map<String, Object> map = parseMap("# leading comment\n"
                + "---\n"
                + "a: value # trailing\n"
                + "b: \"x # not a comment\" # real\n"
                + "c: 'it''s # here'\n"
                + "d: 'say \"hi\" # still quoted' # gone\n"
                + "e: \"it's # quoted\" # gone\n"
                + "\n"
                + "   # indented comment\n"
                + "f: 'a: b'\n");
        assertThat(map.get("a"))
                .isEqualTo("value");
        assertThat(map.get("b"))
                .isEqualTo("x # not a comment");
        assertThat(map.get("c"))
                .isEqualTo("it's # here");
        assertThat(map.get("d"))
                .isEqualTo("say \"hi\" # still quoted");
        assertThat(map.get("e"))
                .isEqualTo("it's # quoted");
        assertThat(map.get("f"))
                .isEqualTo("a: b");
    }

    @Test
    void parsesBlockScalars()
    {
        Map<String, Object> map = parseMap("keep: |\n"
                + "  line one\n"
                + "\n"
                + "  line three\n"
                + "\n"
                + "\n"
                + "strip: |-\n"
                + "  a\n"
                + "    indented\n"
                + "  b\n"
                + "plus: |+\n"
                + "  x\n"
                + "empty: |\n"
                + "nested:\n"
                + "  text: |-\n"
                + "    inner\n"
                + "  after: 1\n"
                + "last: |\n"
                + "  end");
        assertThat(map.get("keep"))
                .isEqualTo("line one\n\nline three\n");
        assertThat(map.get("strip"))
                .isEqualTo("a\n  indented\nb");
        assertThat(map.get("plus"))
                .isEqualTo("x\n");
        assertThat(map.get("empty"))
                .isEqualTo("");
        assertThat(SimpleYaml.asMap(map.get("nested")))
                .containsExactly(entry("text", "inner"), entry("after", 1L));
        assertThat(map.get("last"))
                .isEqualTo("end\n");
    }

    @Test
    void blockScalarEndsAtLineShallowerThanItsFirstLine()
    {
        assertThatThrownBy(() -> SimpleYaml.parse("outer:\n  inner: |\n      deep\n    shallow\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unexpected indent at line 4:     shallow");
    }

    @Test
    void parsesNestedListsAndMaps()
    {
        Map<String, Object> map = parseMap("root:\n"
                + "  - plain\n"
                + "  - \"quoted\"\n"
                + "  -\n"
                + "    k: v\n"
                + "  -\n"
                + "  - name: a # comment\n"
                + "    extra:\n"
                + "      deep: 1\n"
                + "  - only:\n"
                + "  - nested: x\n"
                + "    list:\n"
                + "    - 1\n"
                + "    - 2\n"
                + "  - 'k:q': 0x10\n"
                + "tail: done\n");
        List<Object> root = SimpleYaml.asList(map.get("root"));
        assertThat(root)
                .hasSize(8);
        assertThat(root.subList(0, 2))
                .containsExactly("plain", "quoted");
        assertThat(SimpleYaml.asMap(root.get(2)))
                .containsExactly(entry("k", "v"));
        assertThat(root.get(3))
                .isNull();
        Map<String, Object> named = SimpleYaml.asMap(root.get(4));
        assertThat(named.get("name"))
                .isEqualTo("a");
        assertThat(SimpleYaml.asMap(named.get("extra")))
                .containsExactly(entry("deep", 1L));
        assertThat(SimpleYaml.asMap(root.get(5)))
                .containsExactly(entry("only", null));
        Map<String, Object> nested = SimpleYaml.asMap(root.get(6));
        assertThat(nested.get("nested"))
                .isEqualTo("x");
        assertThat(SimpleYaml.asList(nested.get("list")))
                .containsExactly(1L, 2L);
        assertThat(SimpleYaml.asMap(root.get(7)))
                .containsExactly(entry("k:q", 16L));
        assertThat(map.get("tail"))
                .isEqualTo("done");
    }

    @Test
    void parsesRootSequence()
    {
        List<Object> list = SimpleYaml.asList(SimpleYaml.parse("- 1\n- two\n-\n- three # note\n"));
        assertThat(list)
                .containsExactly(1L, "two", null, "three");
    }

    @Test
    void rejectsMalformedMappings()
    {
        assertThatThrownBy(() -> SimpleYaml.parse("a: 1\n  b: 2\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unexpected indent at line 2:   b: 2");
        assertThatThrownBy(() -> SimpleYaml.parse("a: 1\njust text\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected 'key:' at line 2: just text");
        assertThatThrownBy(() -> SimpleYaml.parse("a: \"\\xZ\"\n"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void asIntAcceptsNumbersAndDecimalOrHexText()
    {
        assertThat(SimpleYaml.asInt(7))
                .isEqualTo(7);
        assertThat(SimpleYaml.asInt(2.9))
                .isEqualTo(2);
        assertThat(SimpleYaml.asInt(" 0x1F "))
                .isEqualTo(31);
        assertThat(SimpleYaml.asInt("0X10"))
                .isEqualTo(16);
        assertThat(SimpleYaml.asInt("-3"))
                .isEqualTo(-3);
        assertThat(SimpleYaml.asInt("0xFFFFFFFF"))
                .isEqualTo(-1);
        assertThatThrownBy(() -> SimpleYaml.asInt(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected an integer, got Boolean");
        assertThatThrownBy(() -> SimpleYaml.asInt(null))
                .hasMessage("expected an integer, got null");
        assertThatThrownBy(() -> SimpleYaml.asInt("abc"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void asBooleanAcceptsYamlWordsInAnyCase()
    {
        assertThat(SimpleYaml.asBoolean(Boolean.TRUE))
                .isTrue();
        assertThat(SimpleYaml.asBoolean(" Yes "))
                .isTrue();
        assertThat(SimpleYaml.asBoolean("ON"))
                .isTrue();
        assertThat(SimpleYaml.asBoolean("true"))
                .isTrue();
        assertThat(SimpleYaml.asBoolean("off"))
                .isFalse();
        assertThat(SimpleYaml.asBoolean("No"))
                .isFalse();
        assertThat(SimpleYaml.asBoolean("FALSE"))
                .isFalse();
        assertThatThrownBy(() -> SimpleYaml.asBoolean("maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected a boolean, got String");
        assertThatThrownBy(() -> SimpleYaml.asBoolean(1))
                .hasMessage("expected a boolean, got Integer");
        assertThatThrownBy(() -> SimpleYaml.asBoolean(null))
                .hasMessage("expected a boolean, got null");
    }

    @Test
    void accessorsFallBackAndRejectWrongTypes()
    {
        Map<String, Object> map = parseMap("s: 5\n");
        assertThat(SimpleYaml.asString(null))
                .isNull();
        assertThat(SimpleYaml.getString(map, "s", null))
                .isEqualTo("5");
        assertThat(SimpleYaml.getString(map, "missing", "fallback"))
                .isEqualTo("fallback");
        assertThat(SimpleYaml.getInt(null, "s", 9))
                .isEqualTo(9);
        assertThat(SimpleYaml.getBoolean(map, "missing", true))
                .isTrue();
        assertThat(SimpleYaml.get(null, "s"))
                .isNull();
        assertThatThrownBy(() -> SimpleYaml.asMap("x"))
                .hasMessage("expected a YAML mapping, got String");
        assertThatThrownBy(() -> SimpleYaml.asList(map))
                .hasMessage("expected a YAML sequence, got LinkedHashMap");
    }

    @Test
    void dumpWritesRootSequenceAndRejectsOtherRoots()
    {
        assertThat(SimpleYaml.dump(Arrays.<Object> asList("a", 1, null, 2.5, false)))
                .isEqualTo("- a\n- 1\n- null\n- 2.5\n- false\n");
        assertThatThrownBy(() -> SimpleYaml.dump("text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected a mapping or sequence at the document root, got String");
        assertThatThrownBy(() -> SimpleYaml.dump(null))
                .hasMessage("expected a mapping or sequence at the document root, got null");
        List<Object> nested = Arrays.<Object> asList(Arrays.<Object> asList(1));
        assertThatThrownBy(() -> SimpleYaml.dump(nested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nested sequences are not supported");
    }

    @Test
    void dumpWritesEmptyCollectionsInFlowStyle()
    {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("m", new LinkedHashMap<String, Object>());
        root.put("l", new ArrayList<Object>());
        root.put("items", Arrays.<Object> asList(Collections.emptyMap(), Collections.emptyList()));
        assertThat(SimpleYaml.dump(root))
                .isEqualTo("m: {}\nl: []\nitems:\n  - {}\n  - []\n");
    }

    @Test
    void dumpNestsMapsAndListsInsideSequenceItems()
    {
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("c", 2L);
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("a", 1L);
        item.put("b", inner);
        item.put("d", Arrays.<Object> asList(3L));
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("items", Arrays.<Object> asList(item));

        String yaml = SimpleYaml.dump(root);
        assertThat(yaml)
                .isEqualTo("items:\n"
                        + "  - a: 1\n"
                        + "    b:\n"
                        + "      c: 2\n"
                        + "    d:\n"
                        + "      - 3\n");
        assertThat(SimpleYaml.parse(yaml))
                .isEqualTo(root);
    }

    @Test
    void dumpQuotesStringsThatWouldNotReadBackAsThemselves()
    {
        String[] quoted = { "", "yes", "On", "~", "0x10", "1e5", "-1.5", ".inf", "-", "- a", "?", "? x", " lead", "\tx", "a: b", "ends:", "ends: ", "a#b", "[x", "{x", "'x", "\"x", "!x", "&x", "*x", "|x", ">x", "%x", "@x", "`x", ",x", ":x" };
        for (String s : quoted)
        {
            assertThat(dumpScalar(s))
                    .as(s)
                    .startsWith("\"");
            assertThat(parseMap(SimpleYaml.dump(Collections.<String, Object> singletonMap("k", s))).get("k"))
                    .as(s)
                    .isEqualTo(s);
        }

        String[] plain = { "hello world", "-a", "?x", "a:b", "x-", "\u00e9t\u00e9", "POKEMON PL", "1.2.3" };
        for (String s : plain)
        {
            assertThat(dumpScalar(s))
                    .isEqualTo(s);
        }
    }

    @Test
    void dumpEscapesControlCharactersByName()
    {
        assertThat(dumpScalar("\0\u0007\b\t\u000b\f\r\u001b\u0085\u2028\u2029\\\""))
                .isEqualTo("\"\\0\\a\\b\\t\\v\\f\\r\\e\\N\\L\\P\\\\\\\"\"");
        assertThat(dumpScalar("a\u0001\u007f\u009f\ufeffb"))
                .isEqualTo("\"a\\x01\\x7F\\x9F\\uFEFFb\"");
        assertThat(dumpScalar("tab\there"))
                .isEqualTo("\"tab\\there\"");
    }

    @Test
    void dumpPicksLiteralChompingFromTrailingNewlines()
    {
        assertThat(dumpScalar("a\nb\n"))
                .isEqualTo("|\n  a\n  b");
        assertThat(dumpScalar("a\nb\n\n"))
                .isEqualTo("|+\n  a\n  b\n  ");
        assertThat(dumpScalar("x\ty\nz"))
                .isEqualTo("\"x\\ty\\nz\"");
        assertThat(SimpleYaml.parse(SimpleYaml.dump(Collections.<String, Object> singletonMap("k", "a\nb\n"))))
                .isEqualTo(Collections.singletonMap("k", "a\nb\n"));
    }

    @Test
    void dumpUsesLiteralForLongMultilineTextEvenWhenUnsafe()
    {
        String longLine = new String(new char[80]).replace('\0', 'z');
        assertThat(dumpScalar("key: value\n" + longLine))
                .isEqualTo("|-\n  key: value\n  " + longLine);
        assertThat(dumpScalar(" a\n" + longLine))
                .isEqualTo("|2-\n   a\n  " + longLine);
        assertThat(dumpScalar(" a\nb"))
                .isEqualTo("\" a\\nb\"");
    }

    private static Map<String, Object> parseMap(String yaml)
    {
        return SimpleYaml.asMap(SimpleYaml.parse(yaml));
    }

    /** Dumps {@code value} under key {@code k} and returns what follows {@code "k: "}, without the final newline. */
    private static String dumpScalar(Object value)
    {
        String yaml = SimpleYaml.dump(Collections.<String, Object> singletonMap("k", value));

        return yaml.substring("k: ".length(), yaml.length() - 1);
    }

    private static Map.Entry<String, Object> entry(String key, Object value)
    {
        return new AbstractMap.SimpleEntry<String, Object>(key, value);
    }
}
