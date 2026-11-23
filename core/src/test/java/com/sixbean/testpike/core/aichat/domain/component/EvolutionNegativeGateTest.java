package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the four-class evolution negative list (AF-i, phase
 * 2b-ii): the gate is the single server-side refusal point the proposal
 * generators run every candidate through BEFORE assembling a draft, it is
 * fail-closed (no configuration can open it, no escape hatch), and its rule
 * table must stay in lockstep with the coverage-gate word list in
 * {@code scripts/tool_coverage_gate.py} — one list, two consumers, zero drift.
 */
class EvolutionNegativeGateTest {

    /** Ordinary proposal surface names — the loop's whole point is allowing these. */
    @Test
    void ordinaryEvolutionCandidatesPassThrough() {
        assertTrue(EvolutionNegativeGate.findViolation("prompt.template.propose").isEmpty());
        assertTrue(EvolutionNegativeGate.findViolation("case.write").isEmpty());
        assertTrue(EvolutionNegativeGate.findViolation("knowledge.grounding.revise").isEmpty());
        assertTrue(EvolutionNegativeGate.findViolation("tool.definition.draft").isEmpty());
        assertDoesNotThrow(() -> EvolutionNegativeGate.requireAllowed(
                List.of("prompt.template.propose", "knowledge.grounding.revise")));
    }

    /** Null/blank candidates carry no surface to violate — pass, never crash. */
    @Test
    void blankCandidatesAreNotViolations() {
        assertTrue(EvolutionNegativeGate.findViolation(null).isEmpty());
        assertTrue(EvolutionNegativeGate.findViolation("  ").isEmpty());
    }

    /** Every one of the four forbidden classes names itself when hit. */
    @Test
    void eachForbiddenClassIsNamedOnHit() {
        assertEquals("SELF_CODE_EDIT",
                EvolutionNegativeGate.findViolation("build.modify").orElseThrow());
        assertEquals("SELF_CODE_EDIT",
                EvolutionNegativeGate.findViolation("deploy.self").orElseThrow());
        assertEquals("GATE_OR_KEY_EDIT",
                EvolutionNegativeGate.findViolation("config.delete").orElseThrow());
        assertEquals("GATE_OR_KEY_EDIT",
                EvolutionNegativeGate.findViolation("k8.key.rotate").orElseThrow());
        assertEquals("CONFIRM_SIGN_ON_BEHALF",
                EvolutionNegativeGate.findViolation("confirm.bypass").orElseThrow());
        assertEquals("CONFIRM_SIGN_ON_BEHALF",
                EvolutionNegativeGate.findViolation("approval.self").orElseThrow());
        assertEquals("AUTO_PUBLISH",
                EvolutionNegativeGate.findViolation("publish.auto").orElseThrow());
        assertEquals("AUTO_PUBLISH",
                EvolutionNegativeGate.findViolation("release.direct").orElseThrow());
    }

    /** Case-insensitive matching: the generators never trust caller casing. */
    @Test
    void matchingIgnoresCasing() {
        assertEquals("SELF_CODE_EDIT",
                EvolutionNegativeGate.findViolation("Service.Rewrite").orElseThrow());
        assertEquals("AUTO_PUBLISH",
                EvolutionNegativeGate.findViolation("PUBLISH.AUTO").orElseThrow());
    }

    /** Batch refusal names the offender and the class so the human can act. */
    @Test
    void requireAllowedRefusesNamingTheCandidateAndTheClass() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> EvolutionNegativeGate.requireAllowed(
                        List.of("prompt.template.propose", "config.write")));
        assertTrue(refused.getMessage().contains("config.write"),
                "refusal must name the offending candidate, got: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("GATE_OR_KEY_EDIT"),
                "refusal must name the forbidden class, got: " + refused.getMessage());
    }

    /**
     * Drift guard: the Java rule table and the coverage gate's Python word
     * list are the same list (red line 6 — a second copy of the negative
     * list is exactly what this test makes impossible). Both are parsed from
     * their sources at test time; changing one side without the other is red.
     */
    @Test
    void ruleTableMatchesTheCoverageGateWordList() throws IOException {
        List<EvolutionNegativeRule> javaRules = EvolutionNegativeGate.rules();
        List<String[]> pythonRules = readPythonNegativePatterns();
        assertEquals(pythonRules.size(), javaRules.size(),
                "rule count drifted between Java gate and coverage gate word list");
        for (int i = 0; i < pythonRules.size(); i++) {
            assertEquals(pythonRules.get(i)[0], javaRules.get(i).label(),
                    "rule label drifted at index " + i);
            assertEquals(pythonRules.get(i)[1], javaRules.get(i).pattern(),
                    "rule pattern drifted at index " + i);
        }
    }

    /**
     * Extract the (LABEL, pattern) pairs from the NEGATIVE_PATTERNS block of
     * scripts/tool_coverage_gate.py. The Python labels are dash-cased wire
     * names; the Java labels are the same names upper-cased with underscores.
     */
    private static List<String[]> readPythonNegativePatterns() throws IOException {
        Path gate = Path.of("../scripts/tool_coverage_gate.py");
        if (!Files.exists(gate)) {
            gate = Path.of("scripts/tool_coverage_gate.py");
        }
        assertTrue(Files.exists(gate),
                "coverage gate script not found from surefire working directory — drift guard must not silently pass");
        String text = Files.readString(gate);
        int block = text.indexOf("NEGATIVE_PATTERNS = [");
        assertTrue(block >= 0, "NEGATIVE_PATTERNS block vanished from the coverage gate");
        // The list terminator is the ']' at column 0: patterns themselves
        // contain character classes like [a-z-], so the first ']' is not it.
        String body = text.substring(block, text.indexOf("\n]", block));
        Matcher m = Pattern
                .compile("\\(\"([^\"]+)\",\\s*re\\.compile\\(r\"([^\"]+)\"")
                .matcher(body);
        List<String[]> pairs = new java.util.ArrayList<>();
        while (m.find()) {
            pairs.add(new String[] {m.group(1).toUpperCase().replace('-', '_'), m.group(2)});
        }
        assertTrue(pairs.size() >= 4, "fewer than four forbidden classes parsed — the extractor broke");
        return pairs;
    }
}
