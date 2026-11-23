package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AP-4(3) server-side attribution of the chunk a piece of evidence
 * was excerpted from. The LLM never echoes internal chunk ids, so the matcher
 * resolves {@code chunkId} by locating the evidence inside the injected chunk
 * contents (whitespace-normalised containment) — deterministic and immune to model
 * discipline. Items whose excerpt matches no chunk (or when the input is a
 * summarised Map-Reduce block that carries no chunk id) keep a null anchor instead
 * of guessing.
 */
class EvidenceChunkMatcherTest {

    private final EvidenceChunkMatcher matcher = new EvidenceChunkMatcher();

    private static Map<String, Object> chunk(long id, int seq, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("seq", seq);
        map.put("content", content);
        return map;
    }

    private static Map<String, Object> summaryBlock(int seq, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", seq);
        map.put("content", content);
        return map;
    }

    @Test
    void attachesChunkIdOfTheContainingChunk() {
        List<Map<String, Object>> chunks = List.of(
                chunk(101L, 1, "用户提交订单时库存不足应提示下单失败。"),
                chunk(102L, 2, "支付超时订单自动取消并释放库存。"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", "库存校验");
        item.put("evidence", "库存不足应提示下单失败");
        matcher.attachChunkIds(chunks, List.of(item));
        assertEquals(102L, chunks.get(1).get("id"), "sanity of fixture ids");
        assertEquals(101L, ((Number) item.get("chunkId")).longValue(),
                "evidence contained in chunk 101 must anchor to it");
    }

    @Test
    void noMatchKeepsNullAnchor() {
        List<Map<String, Object>> chunks = List.of(chunk(201L, 1, "本块与摘录无关。"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidence", "模型自己编的引文");
        matcher.attachChunkIds(chunks, List.of(item));
        assertNull(item.get("chunkId"), "a fabricated excerpt must not fake an anchor");
    }

    @Test
    void summarisedBlocksWithoutIdYieldNullAnchor() {
        List<Map<String, Object>> blocks = List.of(
                summaryBlock(1, "分组摘要：订单流程要点归并。"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidence", "订单流程要点");
        matcher.attachChunkIds(blocks, List.of(item));
        assertNull(item.get("chunkId"),
                "Map-Reduce summaries carry no chunk id — honest null, no misattribution");
    }

    @Test
    void whitespaceAroundExcerptStillMatches() {
        List<Map<String, Object>> chunks = List.of(
                chunk(301L, 1, "支付超时\n订单自动取消 并释放库存。"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("evidence", "支付超时 订单自动取消\n并释放库存");
        matcher.attachChunkIds(chunks, List.of(item));
        assertEquals(301L, ((Number) item.get("chunkId")).longValue(),
                "line/space noise between excerpt and source must not break containment");
    }

    @Test
    void missingOrNullEvidenceIsLeftUntouched() {
        List<Map<String, Object>> chunks = List.of(chunk(401L, 1, "正文内容。"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", "无摘录条目");
        matcher.attachChunkIds(chunks, List.of(item));
        assertNull(item.get("chunkId"), "no evidence → no anchor attempt");
    }
}
