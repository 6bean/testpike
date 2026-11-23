package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class JsonOutputExtractorTest {

    
    private final ObjectMapper strictMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void pureJsonUnchanged() {
        String json = "{\"caseId\":1,\"actions\":[{\"action\":\"goto\"}]}";
        assertEquals(json, JsonOutputExtractor.extractJson(json));
    }

    @Test
    void stripsLeadingFence() {
        String content = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", JsonOutputExtractor.extractJson(content));
    }

    @Test
    void proseBeforeFenceAndTrailingNoiseWithBracesYieldsCleanJson() {
        String content = "以下是生成的脚本：\n```json\n{\"env\":{\"ENV\":\"TEST\"},\"actions\":[]}\n```\n"
                + "其中 {{ENV:BASE_URL}} 为环境占位符，请以 `actionJson` 字段查看。";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"env\":{\"ENV\":\"TEST\"},\"actions\":[]}", out);
        assertFalse(out.contains("`"), "提取结果不得含反引号: " + out);
    }

    @Test
    void trailingTextAfterCompleteObjectWithoutFenceDropped() {
        String content = "{\"a\":{\"b\":1}}\n注意：占位符 {{ENV:X}} 需替换。";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"a\":{\"b\":1}}", out);
        assertStrictlyParses(out);
    }

    @Test
    void bracesInsideStringLiteralsDoNotBreakBalance() {
        String content = "{\"css\":\"#a }{ b\",\"chain\":[\"css\"]} tail }";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"css\":\"#a }{ b\",\"chain\":[\"css\"]}", out);
        assertStrictlyParses(out);
    }

    @Test
    void escapedQuoteHandling() {
        String content = "{\"msg\":\"he said \\\"}\\\" ok\"}";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals(content, out);
        assertStrictlyParses(out);
    }

    @Test
    void noJsonReturnsOriginalText() {
        String content = "抱歉，我无法生成。";
        assertEquals(content, JsonOutputExtractor.extractJson(content));
        assertEquals("", JsonOutputExtractor.extractJson(null));
    }

    @Test
    void leadingEnvTemplatePoisonSkippedToRealJson() {
        String content = "请将 {{ENV:BASE_URL}} 替换后使用：\n```json\n"
                + "{\"caseId\":1,\"actions\":[{\"action\":\"goto\"}]}\n```";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"caseId\":1,\"actions\":[{\"action\":\"goto\"}]}", out);
        assertStrictlyParses(out);
    }

    @Test
    void inlineBalancedButInvalidCandidatesAllSkipped() {
        String content = "{{ENV:X}} 和 {ENV:Y} 两个占位，正文 {\"a\":1}";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"a\":1}", out);
        assertStrictlyParses(out);
    }

    @Test
    void poisonOnlyStillFallsBackWithoutSwallowingValidation() {
        String out = JsonOutputExtractor.extractJson("{{ENV:X}}");
        assertEquals("{{ENV:X}}", out);
    }

    @Test
    void unbalancedOpenBraceFallsBackToLegacySlice() {
        String content = "prose {\"a\":1 tail }";
        String out = JsonOutputExtractor.extractJson(content);
        assertTrue(out.startsWith("{\"a\":1"), "回退语义应仍截出候选段: " + out);
    }

    @Test
    void markerKeyEnvelopeObjectUnwrapped() {
        String content = "{\"STAGE1_CASE_JSON\": {\"title\":\"t\",\"steps\":[]}}";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"title\":\"t\",\"steps\":[]}", out);
        assertStrictlyParses(out);
    }

    @Test
    void fenceWrappedMarkerEnvelopeUnwrapped() {
        String content = "```json\n{\"STAGE2_ACTION_JSON\": {\"version\":\"v2\",\"caseId\":\"1\"}}\n```";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"version\":\"v2\",\"caseId\":\"1\"}", out);
        assertStrictlyParses(out);
    }

    @Test
    void singleElementArrayEnvelopeUnwrapped() {
        String content = "{\"STAGE1_CASE_JSON\": [{\"title\":\"t\"}]}";
        String out = JsonOutputExtractor.extractJson(content);
        assertEquals("{\"title\":\"t\"}", out);
        assertStrictlyParses(out);
    }

    @Test
    void multiElementArrayEnvelopeNotUnwrapped() {
        String content = "{\"STAGE1_CASE_JSON\": [{\"a\":1},{\"b\":2}]}";
        assertEquals(content, JsonOutputExtractor.extractJson(content));
    }

    @Test
    void nonMarkerSingleKeyObjectNotTouched() {
        String content = "{\"a\":1}";
        assertEquals(content, JsonOutputExtractor.extractJson(content));
    }

    @Test
    void markerKeyWithScalarValueNotUnwrapped() {
        String content = "{\"STAGE1_CASE_JSON\": \"见附件\"}";
        assertEquals(content, JsonOutputExtractor.extractJson(content));
    }

    @Test
    void nestedValidObjectsReportAllSpansWithOuterFirstOrder() {
        String text = "prefix {\"a\":{\"b\":1}} suffix {\"c\":2}";
        List<int[]> spans = JsonOutputExtractor.balancedObjectSpans(text);
        assertEquals(3, spans.size());
        List<String> slices = spans.stream().map(s -> text.substring(s[0], s[1] + 1)).toList();
        assertEquals(List.of("{\"a\":{\"b\":1}}", "{\"b\":1}", "{\"c\":2}"), slices);
    }

    @Test
    void templateTextBalancedButNotJsonStillYieldsSpans() {
        List<int[]> spans = JsonOutputExtractor.balancedObjectSpans("note {{ENV:KEY}} tail");
        assertEquals(2, spans.size());
        assertArrayEquals(new int[] {5, 15}, spans.get(0));
        assertArrayEquals(new int[] {6, 14}, spans.get(1));
        assertEquals("{{ENV:KEY}}", "note {{ENV:KEY}} tail".substring(spans.get(0)[0], spans.get(0)[1] + 1));
    }

    @Test
    void bracesInsideStringLiteralsAreNotPaired() {
        String text = "{\"s\":\"a{ b }\",\"t\":{\"u\":1}}";
        List<int[]> spans = JsonOutputExtractor.balancedObjectSpans(text);
        assertEquals(2, spans.size());
        assertArrayEquals(new int[] {0, text.length() - 1}, spans.get(0));
        String outer = text.substring(spans.get(0)[0], spans.get(0)[1] + 1);
        assertEquals(text, outer);
    }

    @Test
    void escapedQuoteKeepsStringStateAndUnbalancedTailIgnored() {
        String text = "{\"a\":\"{\\\" inner\",\"b\":{\"c\":1}} tail {";
        List<int[]> spans = JsonOutputExtractor.balancedObjectSpans(text);
        assertEquals(2, spans.size());
        assertArrayEquals(new int[] {0, 28}, spans.get(0));
        assertArrayEquals(new int[] {21, 27}, spans.get(1));
    }

    @Test
    void unbalancedTextYieldsEmptySpans() {
        assertEquals(List.of(), JsonOutputExtractor.balancedObjectSpans("{\"unclosed"));
        assertEquals(List.of(), JsonOutputExtractor.balancedObjectSpans(null));
        assertEquals(List.of(), JsonOutputExtractor.balancedObjectSpans("no braces here"));
    }

    private void assertStrictlyParses(String out) {
        try {
            strictMapper.readTree(out);
        } catch (Exception e) {
            throw new AssertionError("提取结果非严格合法 JSON: " + out, e);
        }
    }
}
