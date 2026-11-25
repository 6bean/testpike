package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-source contract test for the 04 contract 2.1.2 expect object (fixture
 * shared/http_expect_cases.json). This is the save-gate authority for the
 * extended expect shape (status / body / durationMs / headers); the executor
 * judge mirror is executor/tests/test_t368_expect_duration_headers.py.
 * Unknown keys must fail closed — a mistyped assertion key that silently
 * no-ops would let a broken case pass (the promotion chain relies on every
 * declared assertion actually being enforced).
 */
class NonUiActionContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Same repair-material discipline as the locator.chain line: a model
    // revising a rejected script needs the full step skeleton in the error,
    // not only the missing field name (the field-name-only wording failed to
    // converge within the retry budget on the live truth-source window).
    @Test
    void netAssert缺match_错误自带步骤级修法示例() {
        var step = MAPPER.createObjectNode().put("action", "net.assert");
        String violation = NonUiActionContract.netAssertViolation(step);
        assertNotNull(violation);
        assertTrue(violation.contains("\"urlPattern\""), violation);
        assertTrue(violation.contains("\"match\""), violation);
    }

    private static JsonNode fixture() {
        Path dir = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("shared/http_expect_cases.json");
            if (Files.exists(candidate)) {
                try {
                    return MAPPER.readTree(candidate.toFile());
                } catch (Exception e) {
                    throw new IllegalStateException("two-source fixture unreadable: " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("shared/http_expect_cases.json not found (two-source fixture)");
    }

    @Test
    void expectPositives_passSaveGate_onBothActions() {
        for (JsonNode item : fixture().path("positives")) {
            JsonNode expect = item.path("expect");
            assertNull(NonUiActionContract.expectObjectViolation("http.request", expect),
                    "declared expect must be accepted: " + expect);
            assertNull(NonUiActionContract.expectObjectViolation("net.assert", expect),
                    "net.assert shares the same expect shape (single source): " + expect);
        }
    }

    @Test
    void expectNegatives_failClosed_withPlainWords() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("negatives")) {
            JsonNode expect = item.path("expect");
            String violation = NonUiActionContract.expectObjectViolation("http.request", expect);
            if (violation == null) {
                failures.add("must reject [" + item.path("why").asText() + "]: " + expect);
                continue;
            }
            for (JsonNode anchor : item.path("errorIncludes")) {
                if (!violation.contains(anchor.asText())) {
                    failures.add("message must name [" + anchor.asText() + "] for ["
                            + item.path("why").asText() + "], got: " + violation);
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void wrapper_plumbsDurationAndHeadersThroughHttpAndNetSteps() {
        // Duration/headers violations must surface through the step-level entry
        // points too (core save chain calls httpRequestViolation/netAssertViolation,
        // never the expect-object method directly).
        JsonNode badStep = MAPPER.createObjectNode()
                .put("method", "GET").put("url", "http://x/y");
        ((com.fasterxml.jackson.databind.node.ObjectNode) badStep)
                .set("expect", fixture().path("negatives").get(0).path("expect"));
        assertNotNull(NonUiActionContract.httpRequestViolation(badStep));
        JsonNode netStep = MAPPER.createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) netStep)
                .set("match", MAPPER.createObjectNode().put("urlPattern", "*/x*"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) netStep)
                .set("expect", fixture().path("positives").get(2).path("expect"));
        assertNull(NonUiActionContract.netAssertViolation(netStep),
                "net.assert accepts a declared durationMs range via the wrapper");
    }

    @Test
    void legacyStatusBodyExpect_stillValid_compatibleBaseline() {
        // v2 compatibility: pre-extension cases only used status/body — zero new
        // rejections for old shapes (red line: semantics add-only, never break).
        JsonNode legacy = fixture().path("positives").get(0).path("expect");
        assertNull(NonUiActionContract.expectObjectViolation("http.request", legacy));
        assertFalse(legacy.has("durationMs"));
    }
}
