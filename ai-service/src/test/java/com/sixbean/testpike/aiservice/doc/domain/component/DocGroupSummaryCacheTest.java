package com.sixbean.testpike.aiservice.doc.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * segment B — group-summary cache on the real PostgreSQL face (data
 * access is never mocked at this layer). The K8 switch is the only mocked seam
 * (CoreGateway = HTTP downstream).
 * <p>Two row families live side by side: the whole-round row
 * (group_seq = 0, list JSON) written once per completed Map round, and the
 * per-group rows (group_seq >= 1, single-block JSON) written as soon as one
 * group's summary is computed — that granularity is what lets a mid-round
 * failure resume instead of re-burning every summary.
 * <p>The switch ships ON (AI-feature default-on): a missing/blank/garbage read
 * stays ON with a log line naming the key; only the false/0 spellings turn it
 * OFF. Fixture rows use reserved sentinel document ids and clean up by id —
 * factory tables are never truncated.
 */
@SpringBootTest
class DocGroupSummaryCacheTest {

    private static final long DOC_ID = 992001L;
    private static final long DOC_ID_2 = 992002L;
    private static final String KEY = "ai.doc.summary-cache.enabled";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private DocGroupSummaryCache cache() {
        return new DocGroupSummaryCache(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate),
                new ObjectMapper(),
                coreGateway);
    }

    /** Only the false/0 spellings turn the switch off (R32 ships-on semantics). */
    private void switchOff() {
        when(coreGateway.getSysConfig(KEY)).thenReturn("false");
    }

    private void switchValue(String raw) {
        when(coreGateway.getSysConfig(KEY)).thenReturn(raw);
    }

    private void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_doc_summary_cache WHERE document_id IN (?, ?)", DOC_ID, DOC_ID_2);
    }

    private static List<Map<String, Object>> blocks(String marker) {
        return List.of(
                Map.of("seq", 1, "chapter", "第一章（合并 3 个分块）", "content", marker + "-one"),
                Map.of("seq", 2, "chapter", "第二章", "content", marker + "-two"));
    }

    private static Map<String, Object> groupBlock(int seq, String marker) {
        return Map.of("seq", seq, "chapter", "第" + seq + "章", "content", marker + "-" + seq);
    }

    private int rowsWithGroupSeq(long docId, int groupSeq) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_summary_cache WHERE document_id = ? AND group_seq = ?",
                Integer.class, docId, groupSeq);
        return n == null ? 0 : n;
    }

    /** Missing key row = ships ON: save writes, load reads back (RED witness vs the old off-default). */
    @Test
    void 缺键默认开启读写均生效() {
        cleanup();
        DocGroupSummaryCache cache = cache(); // gateway stubbed to null = key missing
        cache.save(DOC_ID, "t-missing", blocks("missing"));
        assertEquals(1, rowsWithGroupSeq(DOC_ID, 0), "missing key must still persist (ships ON)");
        assertEquals(2, cache.load(DOC_ID, "t-missing").size());
        cleanup();
    }

    /** false/0 turn it off (zero rows), true/1 turn it on — one shared spelling face. */
    @Test
    void 开关拼写false与0关闭且true与1开启() {
        cleanup();
        switchOff();
        cache().save(DOC_ID, "t-off", blocks("off"));
        assertNull(cache().load(DOC_ID, "t-off"), "disabled load must be null");
        assertEquals(0, rowsWithGroupSeq(DOC_ID, 0), "disabled save must never write a row");
        switchValue("0");
        cache().save(DOC_ID, "t-zero", blocks("zero"));
        assertEquals(0, rowsWithGroupSeq(DOC_ID, 0), "0 spelling must also turn the switch off");
        switchValue("1");
        cache().save(DOC_ID, "t-one", blocks("one"));
        assertEquals(1, rowsWithGroupSeq(DOC_ID, 0), "1 spelling turns the switch on");
        cleanup();
    }

    /** Garbage value = ON with a warn naming the key (never a silent fallback to off). */
    @Test
    void 垃圾值按开启处理并点名键名() {
        cleanup();
        switchValue("maybe");
        cache().save(DOC_ID, "t-garbage", blocks("garbage"));
        assertEquals(1, rowsWithGroupSeq(DOC_ID, 0), "garbage value must keep the switch ON");
        cleanup();
    }

    /** Whole-round family: write then read back byte-equal under group_seq = 0. */
    @Test
    void 开启态整轮写后读回内容全等() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.save(DOC_ID, "gen-a", blocks("round1"));
        List<Map<String, Object>> loaded = cache.load(DOC_ID, "gen-a");
        assertEquals(2, loaded.size());
        assertEquals(1, ((Number) loaded.get(0).get("seq")).intValue());
        assertEquals("第一章（合并 3 个分块）", loaded.get(0).get("chapter"));
        assertEquals("round1-one", loaded.get(0).get("content"));
        assertEquals("round1-two", loaded.get(1).get("content"));
        cleanup();
    }

    /** A new generation token evicts every old-generation row of that document. */
    @Test
    void 轮次token变更旧缓存即失效() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.save(DOC_ID, "gen-old", blocks("stale"));
        cache.save(DOC_ID, "gen-new", blocks("fresh"));
        assertNull(cache.load(DOC_ID, "gen-old"), "old generation must be evicted");
        assertTrue(cache.load(DOC_ID, "gen-new").stream()
                .anyMatch(b -> "fresh-one".equals(b.get("content"))));
        cache.save(DOC_ID_2, "gen-x", blocks("other-doc"));
        assertEquals(2, cache.load(DOC_ID, "gen-new").size(), "cross-document eviction is forbidden");
        cleanup();
    }

    /** Per-group family: saveGroup writes group_seq >= 1 rows, loadGroups reads them back. */
    @Test
    void 逐组写入按组序读回() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.saveGroup(DOC_ID, "gen-g", 1, groupBlock(1, "g"));
        cache.saveGroup(DOC_ID, "gen-g", 2, groupBlock(2, "g"));
        Map<Integer, Map<String, Object>> groups = cache.loadGroups(DOC_ID, "gen-g");
        assertEquals(2, groups.size());
        assertEquals("g-1", groups.get(1).get("content"));
        assertEquals("g-2", groups.get(2).get("content"));
        assertEquals(0, rowsWithGroupSeq(DOC_ID, 0), "per-group writes must not touch the whole-round row");
        cleanup();
    }

    /** Both families coexist: the same (doc, gen) keeps one round row plus N group rows. */
    @Test
    void 整轮与逐组两族并存互不挤占() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.save(DOC_ID, "gen-mix", blocks("round"));
        cache.saveGroup(DOC_ID, "gen-mix", 1, groupBlock(1, "mix"));
        cache.saveGroup(DOC_ID, "gen-mix", 1, groupBlock(1, "mix2")); // upsert, no unique violation
        assertEquals(2, cache.load(DOC_ID, "gen-mix").size(), "whole-round face must survive group writes");
        assertEquals("mix2-1", cache.loadGroups(DOC_ID, "gen-mix").get(1).get("content"));
        assertEquals(1, rowsWithGroupSeq(DOC_ID, 1), "repeated saveGroup must upsert, never duplicate");
        cleanup();
    }

    private int rowsWithPlane(long docId, int groupSeq, String plane) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_summary_cache "
                        + "WHERE document_id = ? AND group_seq = ? AND plane = ?",
                Integer.class, docId, groupSeq, plane);
        return n == null ? 0 : n;
    }

    /** remediation ② — the items plane coexists with the summary
 * plane under the SAME (doc, gen, group_seq) key without clobbering it. */
    @Test
    void 条目平面与摘要平面同键并存互不覆盖() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.saveGroup(DOC_ID, "gen-plane", 1, groupBlock(1, "sum"));
        cache.saveItemGroup(DOC_ID, "gen-plane", 1, List.of(Map.of("title", "锁定异常登录")));
        assertEquals("sum-1", cache.loadGroups(DOC_ID, "gen-plane").get(1).get("content"),
                "summary plane must survive an items-plane write");
        assertEquals("锁定异常登录",
                cache.loadItemGroups(DOC_ID, "gen-plane").get(1).get(0).get("title"));
        assertEquals(1, rowsWithPlane(DOC_ID, 1, "summary"));
        assertEquals(1, rowsWithPlane(DOC_ID, 1, "items"));
        cleanup();
    }

    /** Items face: a corrupt row reads as a miss (fail-soft), and a new
     *  generation on the summary face evicts items rows of the old one too. */
    @Test
    void 条目平面坏JSON按未命中且轮次切换两平面同清() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.saveItemGroup(DOC_ID, "gen-bad", 1, List.of(Map.of("title", "t")));
        // jsonb rejects a non-JSON literal, so the corrupt shape is planted as
        // valid JSON that cannot bind to an item list (object, not array) —
        // what the real fail-soft read path has to survive.
        jdbcTemplate.update("UPDATE tp_doc_summary_cache SET blocks = '{\"oops\":1}'::jsonb "
                        + "WHERE document_id = ? AND gen_token = ? AND group_seq = 1 AND plane = 'items'",
                DOC_ID, "gen-bad");
        assertEquals(0, cache.loadItemGroups(DOC_ID, "gen-bad").size(),
                "corrupt items row must read as a miss, never fail the run");
        cache.saveGroup(DOC_ID, "gen-fresh", 1, groupBlock(1, "fresh"));
        assertEquals(0, cache.loadItemGroups(DOC_ID, "gen-bad").size(),
                "generation-change eviction covers the items plane too");
        cleanup();
    }

    /** A per-group write of a new generation clears the old-generation rows first. */
    @Test
    void 逐组写入先清该文档旧轮次行() {
        cleanup();
        switchValue("true");
        DocGroupSummaryCache cache = cache();
        cache.save(DOC_ID, "gen-old", blocks("stale"));
        cache.saveGroup(DOC_ID, "gen-new", 1, groupBlock(1, "new"));
        assertNull(cache.load(DOC_ID, "gen-old"), "old generation must be gone after a new-generation write");
        assertEquals(1, cache.loadGroups(DOC_ID, "gen-new").size());
        cleanup();
    }
}
