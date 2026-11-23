package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.perf.api.dto.PerfTemplateInfo;
import com.sixbean.testpike.core.perf.domain.service.impl.PerfScenarioServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shipped scenario-template catalogue reads (CF-4, .
 * <p>The catalogue is the single read path behind the wizard template picker:
 * prefix filter inside the shared system-config group, honest drop of broken
 * rows (one bad seed
 * must not hide the four shipped templates), and — the load-bearing contract —
 * every served template must already pass the very save gate a hand-written
 * scenario faces. A template the wizard pre-fills can never produce a scenario
 * the user then cannot save. Judgements are consumed from their single sources
 * ({@link PerfStepActionGate} step domain, {@link PerfScenarioServiceImpl}
 * pressure-model / required-key / SLA-key sets) — the catalogue copies no rule.
 */
class PerfScenarioTemplateCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal but fully legal template payload (CONSTANT_RPS, one http step). */
    private static final String GOOD_VALUE = """
            {"label":"查询检索基线","scenario":{
              "pressureModel":"CONSTANT_RPS",
              "pressureConfig":{"targetRps":200,"durationSeconds":180},
              "steps":[{"action":"http.request","method":"GET","url":"{{ENV:BASE}}/api/search"}],
              "slaJson":{"p95Ms":500,"errorRate":0.01}}}""";

    private static ConfigInfo row(String key, String value) {
        return new ConfigInfo(1L, key, value,
                PerfScenarioTemplateCatalog.TEMPLATE_GROUP, "json", 0, "test row");
    }

    private static PerfScenarioTemplateCatalog catalog(ConfigInfo... rows) {
        return new PerfScenarioTemplateCatalog(
                new PerfTemplateConfigServiceStub(List.of(rows)), MAPPER,
                new PerfStepActionGate());
    }

    @Test
    void templatesUnderPrefixParseIntoWizardShapes() {
        List<PerfTemplateInfo> out = catalog(
                row("perf.template.query-baseline", GOOD_VALUE)).templates();
        assertEquals(1, out.size());
        PerfTemplateInfo t = out.get(0);
        assertEquals("query-baseline", t.templateKey(),
                "identifier = key suffix below the prefix");
        assertEquals("查询检索基线", t.label());
        Map<String, Object> scenario = t.scenario();
        assertEquals("CONSTANT_RPS", scenario.get("pressureModel"));
        assertNotNull(scenario.get("steps"), "wizard needs the pre-filled steps");
        // Scenario payload passes through verbatim — the catalogue evaluates no
        // placeholder and compiles nothing (single consumer stays the save gate
        // plus the wizard form).
        assertTrue(MAPPER.valueToTree(scenario).path("steps").get(0)
                .path("url").asText().contains("{{ENV:BASE}}"));
    }

    @Test
    void keysOutsideThePrefixStayOutOfTheCatalogue() {
        List<PerfTemplateInfo> out = catalog(
                row("perf.template.query-baseline", GOOD_VALUE),
                row("perf.scenario.max-steps", "500"),
                row("perf.template.", GOOD_VALUE)).templates();
        assertEquals(1, out.size(), "only prefix rows are served");
    }

    @Test
    void brokenRowDropsHonestlyOthersSurvive() {
        List<PerfTemplateInfo> out = catalog(
                row("perf.template.notjson", "{not json"),
                row("perf.template.emptysteps",
                        "{\"label\":\"空步骤\",\"scenario\":{\"pressureModel\":\"CONSTANT_RPS\","
                                + "\"pressureConfig\":{\"targetRps\":1,\"durationSeconds\":1},\"steps\":[]}}"),
                row("perf.template.query-baseline", GOOD_VALUE)).templates();
        assertEquals(1, out.size(),
                "a non-JSON row and an empty-steps row must drop; the valid one stays visible");
        assertEquals("query-baseline", out.get(0).templateKey());
    }

    @Test
    void templateFailingTheSaveGateDomainDrops() {
        // Each defect below is exactly what the scenario save gate would reject
        // later — the catalogue refuses it up front so the picker never offers
        // an unusable template.
        String badModel = GOOD_VALUE.replace("CONSTANT_RPS", "SLOW_DRIFT");
        String missingKey = GOOD_VALUE.replace("\"durationSeconds\":180", "\"nonsense\":180");
        String badStepAction = GOOD_VALUE.replace("\"action\":\"http.request\"",
                "\"action\":\"browser.click\"");
        String badSlaKey = GOOD_VALUE.replace("\"p95Ms\"", "\"p99Ms\"");
        String blankUrl = GOOD_VALUE.replace("{{ENV:BASE}}/api/search", " ");
        List<PerfTemplateInfo> out = catalog(
                row("perf.template.badModel", badModel),
                row("perf.template.missingKey", missingKey),
                row("perf.template.badStepAction", badStepAction),
                row("perf.template.badSlaKey", badSlaKey),
                row("perf.template.blankUrl", blankUrl),
                row("perf.template.query-baseline", GOOD_VALUE)).templates();
        assertEquals(1, out.size(),
                "unusable templates must not reach the wizard picker");
        assertEquals("query-baseline", out.get(0).templateKey());
    }

    @Test
    void shippedSeedRowsAreCanonicalAndPassTheSaveGateDomain() throws Exception {
        // The seed is the user's first experience of the picker: every shipped
        // template must carry a legal pressure shape against the single-source
        // domains (no second enumeration in this test either — it reads the
        // same constants the save gate uses).
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        while (repoRoot != null && !Files.isDirectory(repoRoot.resolve("db/migration"))) {
            repoRoot = repoRoot.getParent();
        }
        assertNotNull(repoRoot, "repository root with db/migration must be reachable");
        String sql = Files.readString(
                repoRoot.resolve("db/migration/V288__perf_scenario_template_seed.sql"),
                StandardCharsets.UTF_8);
        List<String> payloads = extractJsonLiterals(sql);
        assertEquals(4, payloads.size(), "four template rows expected in the seed");
        PerfScenarioTemplateCatalog catalogue = catalog();
        for (String json : payloads) {
            JsonNode value = MAPPER.readTree(json);
            String verdict = catalogue.structuralVerdict(value);
            assertNull(verdict, "seed row fails the catalogue domain: " + verdict);
        }
    }

    /**
     * Pull the JSON object literal of every seed row: rows are written as one
     * quoted literal per VALUES line, so the boundary is the line-leading
     * {@code ('perf.template.} key plus the JSON between the first {@code '{}
     * and the {@code '}'} that precedes the group name.
     */
    private static List<String> extractJsonLiterals(String sql) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("('perf.template.")) {
                continue;
            }
            int start = trimmed.indexOf("'{");
            int end = trimmed.indexOf("}', '系统'");
            assertTrue(start >= 0 && end > start, "malformed seed line: " + trimmed);
            out.add(trimmed.substring(start + 1, end + 1));
        }
        return out;
    }
}
