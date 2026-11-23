package com.sixbean.testpike.aiservice.doc.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * cross-run bucket-result reuse cache on the real PostgreSQL
 * face (data access is never mocked at this layer; CoreGateway is the mocked
 * HTTP downstream, same harness precedent as the group-summary cache test).
 * <p>The key covers EVERY input that can change the model answer: bucket
 * source blocks, model id, rendered system prompt, injected knowledge, the
 * dimension hint and the split/budget parameter fingerprint — a changed
 * input must never read back a stale bucket (anti-stale contract of the
 * adjudication).
 * <p>R57 pairing checked on this face: row cap with LRU eviction, TTL expiry
 * read as a miss, and the terminal delete of the rows one run pre-occupied.
 * Fixture rows use reserved sentinel document ids and clean up by id —
 * factory tables are never truncated.
 */
@SpringBootTest
class DocBucketResultCacheTest {

    private static final long DOC_ID = 993001L;
    private static final String TOKEN = "gen-bucket-cache-test";
    private static final String KEY_ENABLED = "ai.doc.bucket-cache.enabled";
    private static final String KEY_MAX_ROWS = "ai.doc.bucket-cache-max-rows";
    private static final String KEY_TTL_DAYS = "ai.doc.bucket-cache-ttl-days";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private DocBucketResultCache cache() {
        return new DocBucketResultCache(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbcTemplate),
                new ObjectMapper(),
                coreGateway);
    }

    /** Legal factory spelling unless a test overrides one key. */
    private void factoryDefaults() {
        when(coreGateway.getSysConfig(eq(KEY_ENABLED))).thenReturn("true");
        when(coreGateway.getSysConfig(eq(KEY_MAX_ROWS))).thenReturn("20000");
        when(coreGateway.getSysConfig(eq(KEY_TTL_DAYS))).thenReturn("30");
    }

    private void cleanup(String... keys) {
        for (String key : keys) {
            jdbcTemplate.update("DELETE FROM tp_doc_extract_bucket_cache WHERE cache_key = ?", key);
        }
        jdbcTemplate.update("DELETE FROM tp_doc_extract_bucket_cache WHERE document_id = ?", DOC_ID);
    }

    private static List<Map<String, Object>> blocks(String marker) {
        return List.of(Map.of("id", 1L, "chapter", "第一章", "content", marker + "-正文"));
    }

    private static List<Map<String, Object>> items(String marker) {
        return List.of(Map.of("title", marker + "-需求", "confidence", 0.9));
    }

    private String keyOf(DocBucketResultCache cache, String marker, String model,
                         String system, String knowledge, String hint, String params) {
        return cache.bucketKey(blocks(marker), model, system, knowledge, hint, params);
    }

    /** Insertion-order witnesses: LinkedHashMap built in two different key
     *  orders (including a nested map) carries identical content, yet a
     *  direct serialization hashes them differently — and Map.of/parse paths
     *  hand this method maps whose iteration order is JVM-salted, so the key
     *  rotated on every restart and no row written by an earlier process
     *  could ever be hit. The hashed face must therefore be key-canonical. */
    @Test
    void 同内容两种插入序必同键() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        List<Map<String, Object>> orderA = List.of(
                ordered("id", 1L, "chapter", "第一章", "meta", ordered("z", 1, "a", 2)));
        List<Map<String, Object>> orderB = List.of(
                ordered("meta", ordered("a", 2, "z", 1), "chapter", "第一章", "id", 1L));
        assertEquals(
                cache.bucketKey(orderA, "glm-4.7", "sys", "kn", null, "v1"),
                cache.bucketKey(orderB, "glm-4.7", "sys", "kn", null, "v1"),
                "identical block content in a different key-insertion order must not rotate the bucket key");
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** One source block in the exact shape core's chunk projection returns
     *  (evidence-anchor row id + chapter + seq + content). */
    private static List<Map<String, Object>> anchorBlocks(long rowId, int seq,
                                                          String chapter, String content) {
        Map<String, Object> block = ordered("id", rowId, "seq", seq,
                "chapter", chapter, "content", content);
        return List.of(block);
    }

    /** Face A (structural-miss witness): the evidence-anchor row id is exposed
     *  by the chunk projection for preview jump-to-source, but it is NEVER
     *  rendered into the bucket prompt (the prompt face consumes exactly
     *  seq/chapter/content). Hashing it rotated the key for identical content
     *  across documents/re-ingests, so an unchanged bucket could never be
     *  reused — hit_count stayed zero everywhere. Identical prompt-relevant
     *  content with a different row id must therefore share one key. */
    @Test
    void 同内容不同证据锚行id必同键() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String base = cache.bucketKey(
                anchorBlocks(101L, 3, "第一章", "同一正文"), "glm-4.7", "sys", "kn", null, "v1");
        String otherRowId = cache.bucketKey(
                anchorBlocks(202L, 3, "第一章", "同一正文"), "glm-4.7", "sys", "kn", null, "v1");
        assertEquals(base, otherRowId,
                "the evidence-anchor row id is not a model input and must not rotate the reuse key");
    }

    /** Face B (anti-stale witness): dropping the anchor id must NOT drop any
     *  field the prompt actually consumes — a change on seq / chapter /
     *  content each rotates the key. */
    @Test
    void 内容投影三字段任一变化必换键() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String base = cache.bucketKey(
                anchorBlocks(101L, 3, "第一章", "正文"), "glm-4.7", "sys", "kn", null, "v1");
        assertNotEquals(base, cache.bucketKey(
                anchorBlocks(101L, 4, "第一章", "正文"), "glm-4.7", "sys", "kn", null, "v1"), "seq");
        assertNotEquals(base, cache.bucketKey(
                anchorBlocks(101L, 3, "第二章", "正文"), "glm-4.7", "sys", "kn", null, "v1"), "chapter");
        assertNotEquals(base, cache.bucketKey(
                anchorBlocks(101L, 3, "第一章", "正文改"), "glm-4.7", "sys", "kn", null, "v1"), "content");
    }

    /** Any input face changing the bucket answer must rotate the key
     *  (model / prompt / knowledge / hint / blocks / parameter fingerprint). */
    @Test
    void 任一输入面变化键必不同() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String base = keyOf(cache, "a", "glm-4.7", "sys", "kn", null, "v1");
        assertNotEquals(base, keyOf(cache, "a", "glm-4.6", "sys", "kn", null, "v1"), "model");
        assertNotEquals(base, keyOf(cache, "a", "glm-4.7", "sys2", "kn", null, "v1"), "prompt");
        assertNotEquals(base, keyOf(cache, "a", "glm-4.7", "sys", "kn2", null, "v1"), "knowledge");
        assertNotEquals(base, keyOf(cache, "b", "glm-4.7", "sys", "kn", null, "v1"), "blocks");
        assertNotEquals(base, keyOf(cache, "a", "glm-4.7", "sys", "kn", "dim", "v1"), "hint");
        assertNotEquals(base, keyOf(cache, "a", "glm-4.7", "sys", "kn", null, "v2"), "params");
        assertEquals(base, keyOf(cache, "a", "glm-4.7", "sys", "kn", null, "v1"), "same inputs");
    }

    /** Stored items come back byte-identical and the row is marked as hit. */
    @Test
    void 命中回读条目字节等且计一次命中() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String key = keyOf(cache, "reuse", "glm-4.7", "sys", "kn", null, "v1");
        cleanup(key);
        assertNull(cache.load(key), "fresh key must miss");
        cache.store(key, DOC_ID, TOKEN, items("reuse"));
        List<Map<String, Object>> back = cache.load(key);
        assertNotNull(back);
        assertEquals(items("reuse"), back, "reuse face is the verbatim item list");
        Integer hits = jdbcTemplate.queryForObject(
                "SELECT hit_count FROM tp_doc_extract_bucket_cache WHERE cache_key = ?",
                Integer.class, key);
        assertEquals(1, hits);
        cleanup(key);
    }

    /** An empty item list is NOT cached — a bucket that produced nothing may
     *  legitimately be re-attempted instead of being pinned as empty. */
    @Test
    void 空条目不落盘下次仍重算() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String key = keyOf(cache, "empty", "glm-4.7", "sys", "kn", null, "v1");
        cleanup(key);
        cache.store(key, DOC_ID, TOKEN, List.of());
        assertNull(cache.load(key));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_extract_bucket_cache WHERE cache_key = ?",
                Integer.class, key);
        assertEquals(0, rows);
    }

    /** Switch OFF turns every face into a silent no-op (byte-identical
     *  legacy behaviour) while the OFF guidance line names the key. */
    @Test
    void 关闭态读写皆为直通不落地() {
        when(coreGateway.getSysConfig(eq(KEY_ENABLED))).thenReturn("false");
        when(coreGateway.getSysConfig(eq(KEY_MAX_ROWS))).thenReturn("20000");
        when(coreGateway.getSysConfig(eq(KEY_TTL_DAYS))).thenReturn("30");
        DocBucketResultCache cache = cache();
        String key = keyOf(cache, "off", "glm-4.7", "sys", "kn", null, "v1");
        cleanup(key);
        cache.store(key, DOC_ID, TOKEN, items("off"));
        assertNull(cache.load(key));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_extract_bucket_cache WHERE cache_key = ?",
                Integer.class, key);
        assertEquals(0, rows);
    }

    /** A row older than the TTL reads as a miss (and the next store sweeps it
     *  out — the periodic orphan-sweep face of the R57 pairing). */
    @Test
    void 超过保留天数的行按未命中处理() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String key = keyOf(cache, "ttl", "glm-4.7", "sys", "kn", null, "v1");
        cleanup(key);
        cache.store(key, DOC_ID, TOKEN, items("ttl"));
        jdbcTemplate.update(
                "UPDATE tp_doc_extract_bucket_cache SET created_at = now() - interval '60 days' "
                        + "WHERE cache_key = ?", key);
        assertNull(cache.load(key), "expired row must not be reused");
        cleanup(key);
    }

    /** Over the row cap the least-recently-used rows are evicted, never the
     *  freshly hit ones — the table stays bounded (R57 cap + eviction). */
    @Test
    void 超行数上限按最久未命中逐出() {
        when(coreGateway.getSysConfig(eq(KEY_ENABLED))).thenReturn("true");
        when(coreGateway.getSysConfig(eq(KEY_MAX_ROWS))).thenReturn("2");
        when(coreGateway.getSysConfig(eq(KEY_TTL_DAYS))).thenReturn("30");
        DocBucketResultCache cache = cache();
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            keys.add(keyOf(cache, "lru-" + i, "glm-4.7", "sys", "kn", null, "v1"));
        }
        cleanup(keys.toArray(String[]::new));
        cache.store(keys.get(0), DOC_ID, TOKEN, items("lru-1"));
        cache.store(keys.get(1), DOC_ID, TOKEN, items("lru-2"));
        // Pin the oldest usage face on key 2 so key 1 becomes the LRU victim.
        jdbcTemplate.update(
                "UPDATE tp_doc_extract_bucket_cache SET created_at = now() - interval '2 hours' "
                        + "WHERE cache_key = ?", keys.get(0));
        cache.store(keys.get(2), DOC_ID, TOKEN, items("lru-3"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_extract_bucket_cache WHERE cache_key IN (?, ?, ?)",
                Integer.class, keys.get(0), keys.get(1), keys.get(2));
        assertEquals(2, rows, "row cap must hold after the third store");
        assertNull(cache.load(keys.get(0)), "the least-recently-used row is the victim");
        assertNotNull(cache.load(keys.get(2)));
        cleanup(keys.toArray(String[]::new));
    }

    /** The terminal door of a failed run deletes exactly the rows that run
     *  pre-occupied, leaving other documents/generations untouched. */
    @Test
    void 终态删除只清本run预占行() {
        factoryDefaults();
        DocBucketResultCache cache = cache();
        String mine = keyOf(cache, "mine", "glm-4.7", "sys", "kn", null, "v1");
        String otherRun = keyOf(cache, "other-run", "glm-4.7", "sys", "kn", null, "v1");
        String otherDoc = keyOf(cache, "other-doc", "glm-4.7", "sys", "kn", null, "v1");
        cleanup(mine, otherRun, otherDoc);
        cache.store(mine, DOC_ID, TOKEN, items("mine"));
        cache.store(otherRun, DOC_ID, "gen-other-token", items("other-run"));
        cache.store(otherDoc, 993002L, TOKEN, items("other-doc"));
        int dropped = cache.dropRunRows(DOC_ID, TOKEN);
        assertEquals(1, dropped, "only this run's rows leave the table");
        assertNull(cache.load(mine));
        assertNotNull(cache.load(otherRun), "another generation keeps its rows");
        assertNotNull(cache.load(otherDoc), "another document keeps its rows");
        cleanup(otherRun, otherDoc);
    }
}
