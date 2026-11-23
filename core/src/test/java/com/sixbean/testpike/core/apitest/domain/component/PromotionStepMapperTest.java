package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.NonUiActionContract;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Promotion mapper unit tests .
 * <p>The mapper is the single-source translation of WS2 assertion form rows
 * ({@link ApiAssertionFormContract} six types, schema rows refused here) into the
 * 04 contract 2.1.2
 * http.request expect shape — the promoted regression case must carry every
 * assertion the author wrote in the sandbox (red line 9: no silent row loss),
 * and its mapped output must pass the step-side save gate unchanged (double
 * judgement parity pinned here, no second spec).
 */
class PromotionStepMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode rows(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mapsStatusDurationBodyHeaderRowsIntoOneExpectObject() {
        JsonNode input = rows("""
                [{"type":"status","expect":200},
                 {"type":"duration","op":"range","expect":[0,3000]},
                 {"type":"body","path":"$.code","op":"exact","expect":"OK"},
                 {"type":"header","name":"Content-Type","op":"contains","expect":"json"}]
                """);
        PromotionStepMapping mapped = PromotionStepMapper.toStepExpect(input);
        JsonNode expect = mapped.expect();
        org.junit.jupiter.api.Assertions.assertEquals(200, expect.path("status").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("range",
                expect.path("durationMs").path("op").asText());
        org.junit.jupiter.api.Assertions.assertEquals(3000,
                expect.path("durationMs").path("expect").get(1).asInt());
        org.junit.jupiter.api.Assertions.assertEquals("$.code",
                expect.path("body").get(0).path("path").asText());
        org.junit.jupiter.api.Assertions.assertEquals("exact",
                expect.path("body").get(0).path("matchType").asText());
        org.junit.jupiter.api.Assertions.assertEquals("OK",
                expect.path("body").get(0).path("text").asText());
        org.junit.jupiter.api.Assertions.assertEquals("Content-Type",
                expect.path("headers").get(0).path("name").asText());
        org.junit.jupiter.api.Assertions.assertNull(mapped.saveAs());
    }

    @Test
    void mappedExpectAlwaysPassesTheStepSaveGate() {
        // Same-source proof: every legal form row set maps into an expect object
        // the NonUiActionContract gate accepts verbatim — the promotion chain adds
        // no second judgement and the case-side validator never rejects its own output.
        JsonNode input = rows("""
                [{"type":"status","expect":201},
                 {"type":"duration","op":"range","expect":[50,800],"bounds":"open"},
                 {"type":"body","path":"$.latencyMs","op":"range","expect":[50,800]},
                 {"type":"body","path":"$.code","op":"regex","expect":"^ok$"},
                 {"type":"header","name":"X-Retry","op":"range","expect":[1,3]},
                 {"type":"extract","path":"$.data.token","saveAs":"TOKEN"}]
                """);
        PromotionStepMapping mapped = PromotionStepMapper.toStepExpect(input);
        org.junit.jupiter.api.Assertions.assertNull(PromotionStepMapper.mappingViolation(input));
        org.junit.jupiter.api.Assertions.assertNull(
                NonUiActionContract.expectObjectViolation("http.request", mapped.expect()));
        org.junit.jupiter.api.Assertions.assertEquals("TOKEN", mapped.saveAs());
        // Regex body row carries the pattern field (B10 expect-match domain), not text.
        JsonNode regexRow = mapped.expect().path("body").get(1);
        org.junit.jupiter.api.Assertions.assertEquals("regex", regexRow.path("matchType").asText());
        org.junit.jupiter.api.Assertions.assertEquals("^ok$", regexRow.path("pattern").asText());
        // Range body row carries min/max endpoints (B10 shape), not the form pair.
        JsonNode rangeRow = mapped.expect().path("body").get(0);
        org.junit.jupiter.api.Assertions.assertEquals(50, rangeRow.path("min").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(800, rangeRow.path("max").asInt());
    }

    @Test
    void emptyAssertionRowsMapToEmptyExpectWithoutSaveAs() {
        PromotionStepMapping mapped = PromotionStepMapper.toStepExpect(rows("[]"));
        org.junit.jupiter.api.Assertions.assertTrue(mapped.expect().isEmpty());
        org.junit.jupiter.api.Assertions.assertNull(mapped.saveAs());
    }

    @Test
    void duplicateStatusRowsFailClosedWithPlainLanguage() {
        // Two status rows cannot share one expect.status slot — last-write-wins
        // would silently drop the first assertion, so the mapper refuses loudly.
        JsonNode input = rows("""
                [{"type":"status","expect":200},{"type":"status","expect":201}]
                """);
        String violation = PromotionStepMapper.mappingViolation(input);
        org.junit.jupiter.api.Assertions.assertNotNull(violation);
        org.junit.jupiter.api.Assertions.assertTrue(violation.contains("状态码"));
    }

    @Test
    void multipleExtractRowsFailClosedBecauseOneStepCarriesOneSaveAs() {
        JsonNode input = rows("""
                [{"type":"extract","path":"$.data.token","saveAs":"TOKEN"},
                 {"type":"extract","path":"$.data.id","saveAs":"ID"}]
                """);
        String violation = PromotionStepMapper.mappingViolation(input);
        org.junit.jupiter.api.Assertions.assertNotNull(violation);
        org.junit.jupiter.api.Assertions.assertTrue(violation.contains("变量提取"));
    }

    @Test
    void illegalFormRowIsRejectedByTheReusedFormContract() {
        // Red line 6: the mapper delegates per-row legality to ApiAssertionFormContract
        // (single source) instead of inventing its own row grammar.
        JsonNode input = rows("[{\"type\":\"expr\",\"expect\":\"1==1\"}]");
        String violation = PromotionStepMapper.mappingViolation(input);
        org.junit.jupiter.api.Assertions.assertNotNull(violation);
        org.junit.jupiter.api.Assertions.assertTrue(violation.contains("断言类型"));
    }

    @Test
    void schemaRowsRefusePromotionBecauseStepExpectHasNoSchemaSlot() {
        // the form vocabulary grew a sixth type, but the 04 contract
        // step expect (status/body/durationMs/headers) cannot carry a schema check
        // yet — promotion must fail closed with plain language instead of silently
        // dropping the assertion row (red line 9).
        JsonNode input = rows("""
                [{"type":"status","expect":200},
                 {"type":"schema","via":"contract"}]
                """);
        String violation = PromotionStepMapper.mappingViolation(input);
        org.junit.jupiter.api.Assertions.assertNotNull(violation);
        org.junit.jupiter.api.Assertions.assertTrue(violation.contains("结构校验断言"), violation);
        org.junit.jupiter.api.Assertions.assertTrue(violation.contains("升格"), violation);
    }

    @Test
    void wsRowsRefusePromotionBecauseStepVocabularyHasNoSocketSlot() {
        // ws.* assertions need the live socket the case-step runner
        // never opens — refuse loudly (named type) instead of dropping the row.
        for (String type : List.of("ws.receive", "ws.order", "ws.frequency")) {
            JsonNode input = rows("[{\"type\":\"" + type + "\",\"expect\":\"x\"}]");
            String violation = PromotionStepMapper.mappingViolation(input);
            org.junit.jupiter.api.Assertions.assertNotNull(violation, type + " 应拒升格");
            org.junit.jupiter.api.Assertions.assertTrue(violation.contains(type),
                    "文案应点名断言类型 " + type + "，实际=" + violation);
            org.junit.jupiter.api.Assertions.assertTrue(violation.contains("升格"), violation);
        }
    }

    @Test
    void nonArrayInputIsRejected() {
        String violation = PromotionStepMapper.mappingViolation(MAPPER.createObjectNode());
        org.junit.jupiter.api.Assertions.assertNotNull(violation);
        org.junit.jupiter.api.Assertions.assertTrue(violation.contains("数组"));
    }
}
