package com.sixbean.testpike.common.genkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the deterministic gap filler that closes the non-UI
 * action family's mechanical gaps (conn / match / target / placeholder
 * lexicon) before any LLM re-issue is attempted. Each branch is asserted
 * both ways: the fix lands where it should, and nothing else is touched.
 */
class ScriptMechanicalGapFillerTest {

    private final ScriptMechanicalGapFiller filler = new ScriptMechanicalGapFiller();
    private final ObjectMapper mapper = new ObjectMapper();

    private String script(String steps) {
        return "{\"version\":\"v2\",\"caseId\":\"c1\",\"env\":{\"name\":\"sit\"},\"steps\":"
                + steps + "}";
    }

    private JsonNode steps(String filledScript) throws Exception {
        return mapper.readTree(filledScript).path("steps");
    }

    /** One project-scoped connection: the filler stamps its name into conn. */
    @Test
    void sqlStepWithoutConn_getsTheOnlyCandidateName() throws Exception {
        String draft = script("[{\"action\":\"sql.assert\",\"description\":\"落库校验\","
                + "\"sql\":\"SELECT brand_code FROM t_brand LIMIT 1\","
                + "\"expect\":{\"rowValueEquals\":\"BR001\"}}]");
        var result = filler.fill(draft, List.of("DB_SIT"));
        JsonNode step = steps(result.script()).get(0);
        assertEquals("DB_SIT", step.path("conn").asText());
        assertTrue(result.notes().isEmpty(), "single candidate needs no human note: "
                + result.notes());
    }

    /** Several connections: never guess — annotate and list the candidates. */
    @Test
    void sqlStepWithoutConn_multipleCandidates_annotatesWithoutGuessing() throws Exception {
        String draft = script("[{\"action\":\"sql.query\",\"description\":\"查品牌\","
                + "\"sql\":\"SELECT 1\",\"expect\":{\"rowCountAtLeast\":1}}]");
        var result = filler.fill(draft, List.of("DB_SIT", "DB_UAT"));
        assertFalse(steps(result.script()).get(0).has("conn"), "must not pick one arbitrarily");
        assertEquals(1, result.notes().size());
        String note = result.notes().get(0);
        assertTrue(note.contains("DB_SIT") && note.contains("DB_UAT"), "candidates listed: " + note);
        assertTrue(note.contains("需人工精化"), "human-refinement wording: " + note);
    }

    /** No usable connection: honestly drop the step, keep the rest byte-equal. */
    @Test
    void sqlStepWithoutConn_noCandidate_dropsStepAndKeepsOthersIntact() throws Exception {
        String other = "{\"action\":\"goto\",\"target\":{\"css\":\"body\",\"chain\":[\"css\"]},"
                + "\"url\":\"{{ENV:BASE_URL}}/login\",\"description\":\"打开登录页\"}";
        String draft = script("[" + "{\"action\":\"sql.assert\",\"conn\":\"\","
                + "\"sql\":\"SELECT 1\",\"expect\":{\"rowCountAtLeast\":1},\"description\":\"落库校验\"},"
                + other + "]");
        var result = filler.fill(draft, List.of());
        JsonNode steps = steps(result.script());
        assertEquals(1, steps.size(), "sql step dropped");
        assertEquals("goto", steps.get(0).path("action").asText());
        assertEquals(1, result.notes().size());
        assertTrue(result.notes().get(0).contains("本步数据库校验已跳过"), result.notes().get(0));
    }

    /** console.assert carries no locator: dropping the stray target changes nothing else. */
    @Test
    void consoleAssert_strayTargetRemoved_siblingsUnchanged() throws Exception {
        String draft = script("[{\"action\":\"console.assert\",\"mode\":\"fail\","
                + "\"level\":[\"error\"],\"target\":{\"css\":\".x\",\"chain\":[\"css\"]},"
                + "\"description\":\"无报错\"},"
                + "{\"action\":\"expect\",\"target\":{\"css\":\"#ok\",\"chain\":[\"css\"]},"
                + "\"expect\":{\"text\":\"成功\"},\"description\":\"看到成功\"}]");
        var result = filler.fill(draft, List.of("DB_SIT"));
        JsonNode first = steps(result.script()).get(0);
        assertFalse(first.has("target"), "stray locator removed");
        assertEquals("fail", first.path("mode").asText(), "every other key kept");
        assertTrue(steps(result.script()).get(1).path("target").has("css"),
                "expect step keeps its locator");
    }

    /** net.assert without match but with a url: derive the urlPattern mechanically. */
    @Test
    void netAssert_urlPresent_derivesMatchPattern() throws Exception {
        String draft = script("[{\"action\":\"net.assert\",\"window\":\"current\","
                + "\"url\":\"https://shop.example.com/api/v1/orders\","
                + "\"expect\":{\"status\":200},\"description\":\"订单接口返回 200\"}]");
        var result = filler.fill(draft, List.of());
        JsonNode step = steps(result.script()).get(0);
        String pattern = step.path("match").path("urlPattern").asText();
        assertTrue(pattern.contains("api/v1/orders"), "pattern derived from url path: " + pattern);
    }

    /** net.assert with no anchor at all: honest drop, annotated. */
    @Test
    void netAssert_urlAbsent_stepDroppedWithNote() throws Exception {
        String draft = script("[{\"action\":\"net.assert\",\"expect\":{\"status\":200},"
                + "\"description\":\"接口返回 200\"}]");
        var result = filler.fill(draft, List.of());
        assertEquals(0, steps(result.script()).size());
        assertEquals(1, result.notes().size());
        assertTrue(result.notes().get(0).contains("本步网络断言已跳过"), result.notes().get(0));
    }

    /** Dotted DATA placeholder normalises to the underscore identifier form. */
    @Test
    void dottedDataPlaceholder_normalisedToLegalLexicon() throws Exception {
        String draft = script("[{\"action\":\"input\",\"target\":{\"css\":\"#brand\","
                + "\"chain\":[\"css\"]},\"value\":\"{{DATA:brand.code}}\","
                + "\"description\":\"录入品牌编码\"}]");
        var result = filler.fill(draft, List.of());
        JsonNode step = steps(result.script()).get(0);
        assertEquals("{{DATA:brand_code}}", step.path("value").asText());
    }

    /** Legal lexicons are never touched — no false positives on normal scripts. */
    @Test
    void legalPlaceholders_andUnrepairableShapes_leftAsIs() throws Exception {
        String draft = script("[{\"action\":\"goto\",\"url\":\"{{ENV:BASE_URL}}/login\","
                + "\"target\":{\"css\":\"body\",\"chain\":[\"css\"]},"
                + "\"description\":\"示例 {{DATA:a.b}} 字样不改动说明文本\"},"
                + "{\"action\":\"input\",\"target\":{\"css\":\"#x\",\"chain\":[\"css\"]},"
                + "\"value\":\"{{DATA:.bad}}\",\"description\":\"坏形保留由校验器判\"}]");
        var result = filler.fill(draft, List.of());
        JsonNode steps = steps(result.script());
        assertEquals("{{ENV:BASE_URL}}/login", steps.get(0).path("url").asText());
        assertTrue(steps.get(0).path("description").asText().contains("{{DATA:a.b}}"),
                "description text never rewritten");
        assertEquals("{{DATA:.bad}}", steps.get(1).path("value").asText(),
                "unrepairable shape kept for the validator to reject");
    }

    /** A draft that does not even parse is handed back untouched (validator keeps its own verdict). */
    @Test
    void unparsableDraft_returnedUnchanged() {
        String draft = "not a json {{{";
        var result = filler.fill(draft, List.of("DB_SIT"));
        assertEquals(draft, result.script());
        assertEquals(List.of(), result.notes());
    }
}
