package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;


class ApiAssertionFormContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture() {
        Path dir = Path.of(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("shared/assertion_form_cases.json");
            if (Files.exists(candidate)) {
                try {
                    return MAPPER.readTree(candidate.toFile());
                } catch (Exception e) {
                    throw new IllegalStateException("两端同源夹具不可解析: " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("shared/assertion_form_cases.json 未找到（两端同源夹具）");
    }

    @Test
    void 正向断言行全部放行() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("positives")) {
            JsonNode row = item.path("row");
            String violation = ApiAssertionFormContract.rowViolation(
                    row, item.path("protocol").asText("http"));
            if (violation != null) {
                failures.add(row + " 应放行却被拒：" + violation);
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void 负向断言行逐条人话点名含锚点() {
        List<String> failures = new ArrayList<>();
        for (JsonNode item : fixture().path("negatives")) {
            JsonNode row = item.path("row");
            String violation = ApiAssertionFormContract.rowViolation(
                    row, item.path("protocol").asText("http"));
            if (violation == null) {
                failures.add(row + "（" + item.path("why").asText() + "）应被拒却放行");
                continue;
            }
            for (JsonNode anchor : item.path("errorIncludes")) {
                if (!violation.contains(anchor.asText())) {
                    failures.add(row + " 违例文案应含「" + anchor.asText()
                            + "」，实际=" + violation);
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
