package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D3 engine-caliber disclosure single source (W-d disclosure face).
 * <p>Gap this test anchors: JMeter approximates a declared RPS through VU
 * (thread-group) driver pressure with per-second timer smoothing, so the
 * approximation caliber must be disclosed verbatim on both consumer faces
 * (wizard annotation supply, report footnote) from ONE wording source. A
 * second paraphrased copy would drift the moment the timer behaviour is
 * re-documented, so the caveat for JMETER is asserted to embed
 * {@link JmxTemplate#APPROXIMATION_NOTE} verbatim rather than "similar text".
 * <p>Red anchors: JMETER caveat embeds the single-source note plus the VU
 * driver caliber; exact-schedule engines (K6/GATLING) ship no fabricated
 * caveat; the projected catalogue carries every declared engine, exposes
 * engine+caveat fields only, and leaks no AI-family configuration keys.
 */
class PerfEngineDisclosureTest {

    @Test
    void jmeterCaveatCarriesTheSingleSourceWording() {
        String caveat = PerfEngineDisclosure.caveatFor("JMETER");
        assertTrue(caveat != null && caveat.contains(JmxTemplate.APPROXIMATION_NOTE),
                "JMETER 披露必须逐字复用 JmxTemplate 单源口径: " + caveat);
        assertTrue(caveat != null && caveat.contains("VU"),
                "披露必须点名 VU（线程组）驱动近似口径: " + caveat);
    }

    @Test
    void exactScheduleEnginesShipNoFabricatedCaveat() {
        assertNull(PerfEngineDisclosure.caveatFor("K6"),
                "k6 属精确到达调度，禁伪造近似口径披露");
        assertNull(PerfEngineDisclosure.caveatFor("GATLING"),
                "Gatling 属精确到达调度，禁伪造近似口径披露");
    }

    @Test
    void unknownOrMissingEngineYieldsHonestAbsence() {
        assertNull(PerfEngineDisclosure.caveatFor(null));
        assertNull(PerfEngineDisclosure.caveatFor("NOPE"));
    }

    @Test
    void catalogueProjectsEveryDeclaredEngineWithoutAiFamilyKeys() {
        List<Map<String, Object>> rows = PerfEngineDisclosure.catalogue();
        assertEquals(PerfRunDispatchSupport.ENGINE_VALUES.size(), rows.size(),
                "目录必须投影引擎值域全集（单源值域，禁二套清单）");
        for (Map<String, Object> row : rows) {
            assertEquals(Set.of("engine", "caveat"), row.keySet(),
                    "目录行只允许 engine+caveat 两字段: " + row.keySet());
            assertFalse(serialized(row).contains("ai."),
                    "披露面禁 AI 族配置键出域: " + serialized(row));
        }
        Map<String, Object> jmeter = rows.stream()
                .filter(r -> "JMETER".equals(r.get("engine")))
                .findFirst().orElseThrow();
        assertEquals(PerfEngineDisclosure.caveatFor("JMETER"), jmeter.get("caveat"));
    }

    private static String serialized(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        row.forEach((k, v) -> sb.append(k.toLowerCase())
                .append(String.valueOf(v).toLowerCase()));
        return sb.toString();
    }
}
