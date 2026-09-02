/*
 * Copyright (c) 2023 Turtleisaac.
 *
 * This file is part of Nds4j.
 */

package io.github.turtleisaac.nds4j.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
