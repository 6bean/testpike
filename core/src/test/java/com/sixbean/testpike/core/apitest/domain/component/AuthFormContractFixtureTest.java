package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both-ends fixture regression for the auth form (W1c auth editor;
 * shared/auth_form_cases.json). The save gate here is the authority side;
 * the frontend utils/authForm.ts mirror consumes the same case set, so a
 * verdict change on one end turns this test red instead of drifting silently
 * (assertion_form_cases.json precedent). Pure logic — no database, no Spring.
 */
class AuthFormContractFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture() {
        Path dir = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("shared/auth_form_cases.json");
            if (Files.exists(candidate)) {
                try {
                    return MAPPER.readTree(candidate.toFile());
                } catch (Exception e) {
                    throw new IllegalStateException("两端同源夹具不可解析: " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("shared/auth_form_cases.json 未找到（两端同源夹具）");
    }

    @Test
    void positiveAuthRowsAllPassTheSaveGate() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("positives")) {
            String violation = AuthFormContract.violation(item.path("auth"));
            if (violation != null) {
                failures.add(item.path("auth") + " 应放行却被拒：" + violation);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void negativeAuthRowsFailWithThePinnedPlainLanguageAnchors() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("negatives")) {
            String violation = AuthFormContract.violation(item.path("auth"));
            if (violation == null) {
                failures.add(item.path("auth") + "（" + item.path("why").asText() + "）应被拒却放行");
                continue;
            }
            for (JsonNode anchor : item.path("errorIncludes")) {
                if (!violation.contains(anchor.asText())) {
                    failures.add("锚点「" + anchor.asText() + "」缺失于「" + violation + "」");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
