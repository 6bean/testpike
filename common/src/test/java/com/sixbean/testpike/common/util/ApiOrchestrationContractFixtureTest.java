package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration form contract both-ends fixture test .
 * <p>Authority = this Java class (core save gate); the executor fail-closed
 * guard and the front-end utils/orchestrationForm.ts UX mirror consume the
 * same fixture (shared/orchestration_form_cases.json) so no side can drift
 * into a different verdict than the other two (red line: one vocabulary).
 * <p>Pure static, no database, no Spring — the persistence path is pinned
 * separately by ApiRequestServiceTest.
 */
class ApiOrchestrationContractFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture() {
        Path dir = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("shared/orchestration_form_cases.json");
            if (Files.exists(candidate)) {
                try {
                    return MAPPER.readTree(candidate.toFile());
                } catch (Exception e) {
                    throw new IllegalStateException("shared fixture unreadable: " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("shared/orchestration_form_cases.json not found (both-ends fixture)");
    }

    @Test
    void legalPreconditionRowsAreAccepted() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("precondition").path("positives")) {
            String violation = ApiOrchestrationContract.preconditionViolation(item.path("row"));
            if (violation != null) {
                failures.add(item.path("row") + " should pass but was refused: " + violation);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void illegalPreconditionRowsGetNameInPlainText() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("precondition").path("negatives")) {
            JsonNode row = item.path("row");
            String violation = ApiOrchestrationContract.preconditionViolation(row);
            if (violation == null) {
                failures.add(row + " (" + item.path("why").asText() + ") should be refused but passed");
                continue;
            }
            for (JsonNode anchor : item.path("errorIncludes")) {
                if (!violation.contains(anchor.asText())) {
                    failures.add(row + " verdict \"" + violation + "\" misses anchor \"" + anchor.asText() + "\"");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void legalLoopRowsAreAccepted() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("loop").path("positives")) {
            String violation = ApiOrchestrationContract.loopViolation(item.path("row"));
            if (violation != null) {
                failures.add(item.path("row") + " should pass but was refused: " + violation);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void illegalLoopRowsGetNameInPlainText() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("loop").path("negatives")) {
            JsonNode row = item.path("row");
            String violation = ApiOrchestrationContract.loopViolation(row);
            if (violation == null) {
                failures.add(row + " (" + item.path("why").asText() + ") should be refused but passed");
                continue;
            }
            for (JsonNode anchor : item.path("errorIncludes")) {
                if (!violation.contains(anchor.asText())) {
                    failures.add(row + " verdict \"" + violation + "\" misses anchor \"" + anchor.asText() + "\"");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
