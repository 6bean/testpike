package com.sixbean.testpike.aiservice.doc.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * residual segment (package C) — R57 resource-plane spectra of the bucket
 * result cache, run against the real PostgreSQL face of this chain's slot
 * (data access is never mocked here; CoreGateway stays the mocked HTTP
 * downstream, same harness precedent as DocBucketResultCacheTest).
 *
 * <p>Spectra covered, deliberately leaving out what the tests already
 * pin (key rotation, hit read-back, empty-list skip, OFF passthrough, expired
 * read):
 * <ol>
 * <li>row-cap eviction: the cap comes from the config key (never a literal
 * in the assertions), a freshly HIT row must survive while the least
 * recently used row is the victim (COALESCE(last_hit_at, created_at)
 * ordering), and illegal/out-of-interval cap values fall back to the
 * factory default instead of emptying the table;</li>
 * <li>TTL orphan sweep: an expired row is not only read as a miss but is
 * physically swept out on the NEXT store (the declared sweep face);</li>
 * <li>terminal delete: a failed/cancelled run drops exactly its own
 * pre-occupied rows (multi-row run), a null generation token is a
 * no-op guard, and replaying the terminal door is idempotent;</li>
 * <li>hit observability: the tally names the zero-hit case explicitly
 * load/store traffic, and a corrupt payload degrades to a named miss.</li>
 * </ol>
 *
 * <p>Fixture rows use reserved sentinel document ids and are cleaned by id in
 * every finally block — factory tables are never truncated (R37).
 */
@SpringBootTest
class DocBucketCacheResourceSpectrumTest {

    private static final long DOC_A = 993101L;
    private static final long DOC_B = 993102L;
    private static final String TOKEN_A = "gen-spectrum-run-a";
    private static final String TOKEN_B = "gen-spectrum-run-b";
    private static final String KEY_ENABLED = "ai.doc.bucket-cache.enabled";
    private static final String KEY_MAX_ROWS = "ai.doc.bucket-cache-max-rows";
    private static final String KEY_TTL_DAYS = "ai.doc.bucket-cache-ttl-days";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final CoreGateway coreGateway = mock(CoreGateway.class);

    private DocBucketResultCache cache() {
        return new DocBucketResultCache(
                new NamedParameterJdbcTemplate(jdbcTemplate),
                new ObjectMapper(),
                coreGateway);
    }

    private void config(String maxRows, String ttlDays) {
        when(coreGateway.getSysConfig(eq(KEY_ENABLED))).thenReturn("true");
        when(coreGateway.getSysConfig(eq(KEY_MAX_ROWS))).thenReturn(maxRows);
        when(coreGateway.getSysConfig(eq(KEY_TTL_DAYS))).thenReturn(ttlDays);
    }

    private void cleanupByDocs(long... docIds) {
        for (long doc : docIds) {
            jdbcTemplate.update(
                    "DELETE FROM tp_doc_extract_bucket_cache WHERE document_id = ?", doc);
        }
    }

    private void cleanupKeys(List<String> keys) {
        for (String key : keys) {
            jdbcTemplate.update(
                    "DELETE FROM tp_doc_extract_bucket_cache WHERE cache_key = ?", key);
        }
    }

    private static List<Map<String, Object>> blocks(String marker) {
        return List.of(Map.of("id", 1L, "chapter", "spectrum", "content", marker + "-body"));
    }

    private static List<Map<String, Object>> items(String marker) {
        return List.of(Map.of("title", marker + "-item", "confidence", 0.9));
    }

    private static String keyOf(DocBucketResultCache cache, String marker) {
        return cache.bucketKey(blocks(marker), "glm-4.7", "sys", "kn", null, "v1");
    }

    private int fixtureRows(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(keys.size(), "?"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_doc_extract_bucket_cache WHERE cache_key IN ("
                        + placeholders + ")",
                Integer.class, keys.toArray());
        return rows == null ? 0 : rows;
    }

    /**
     * Spectrum 1a — over the configured cap the eviction victim is the least
     * recently USED row (COALESCE(last_hit_at, created_at)), never a row that
     * was just hit. The expected survivor count is derived from the configured
     * cap value, not from a literal baked into the assertion.
     */
    @Test
    void rowCapEvictsLeastRecentlyUsedAndKeepsFreshlyHitRows() {
        int cap = 8;
        config(String.valueOf(cap), "30");
        DocBucketResultCache cache = cache();
        List<String> keys = new ArrayList<>();
        try {
            for (int i = 0; i < cap; i++) {
                String key = keyOf(cache, "cap-" + i);
                keys.add(key);
                cache.store(key, DOC_A, TOKEN_A, items("cap-" + i));
            }
            // Pin the FIRST (oldest) row via a real reuse hit so the LRU face
            // is exercised through last_hit_at, not just creation order.
            assertNotNull(cache.load(keys.get(0)), "fresh row must hit before pinning");

            for (int i = cap; i < cap + 4; i++) {
                String key = keyOf(cache, "cap-" + i);
                keys.add(key);
                cache.store(key, DOC_A, TOKEN_A, items("cap-" + i));
            }

            Integer total = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_doc_extract_bucket_cache", Integer.class);
            assertNotNull(total);
            assertTrue(total <= cap,
                    "table must stay bounded by the configured cap, saw " + total);
            assertNotNull(cache.load(keys.get(0)),
                    "the just-hit row is never the eviction victim regardless of age");
            int survivors = fixtureRows(keys);
            assertTrue(survivors > 0 && survivors <= cap,
                    "fixture survivors must respect the cap, saw " + survivors);
            for (int i = cap + 1; i < cap + 4; i++) {
                assertNotNull(cache.load(keys.get(i)), "freshly stored row #" + i + " must live");
            }
        } finally {
            cleanupKeys(keys);
            cleanupByDocs(DOC_A);
        }
    }

    /**
     * Spectrum 1b — an illegal or out-of-interval cap value falls back to the
     * factory default (clamp with a warn naming the key); it must never be
     * read as "evict everything".
     */
    @Test
    void illegalCapValueClampsToFactoryDefaultAndEvictsNothing() {
        config("0", "30");
        DocBucketResultCache cache = cache();
        String key = keyOf(cache, "clamp-zero");
        try {
            cache.store(key, DOC_A, TOKEN_A, items("clamp-zero"));
            Integer before = fixtureRows(List.of(key));
            assertEquals(1, before, "row must be stored");
            String otherKey = keyOf(cache, "clamp-second");
            cache.store(otherKey, DOC_A, TOKEN_A, items("clamp-second"));
            // cap=0 is below the accepted interval -> factory default applies,
            // so both rows survive instead of the table being emptied.
            assertEquals(2, fixtureRows(List.of(key, otherKey)),
                    "clamp face must keep rows (factory default cap), not empty the table");
            cleanupKeys(List.of(otherKey));
        } finally {
            cleanupKeys(List.of(key));
            cleanupByDocs(DOC_A);
        }
    }

    /**
 * Spectrum 2 — TTL orphan sweep face: an expired row reads as a miss AND
 * is physically deleted by the next store only proved the read).
 */
    @Test
    void expiredRowIsSweptOutByTheNextStore() {
        config("20000", "30");
        DocBucketResultCache cache = cache();
        String stale = keyOf(cache, "ttl-sweep");
        String fresh = keyOf(cache, "ttl-writer");
        try {
            cache.store(stale, DOC_A, TOKEN_A, items("ttl-sweep"));
            jdbcTemplate.update(
                    "UPDATE tp_doc_extract_bucket_cache SET created_at = now() - interval '60 days' "
                            + "WHERE cache_key = ?", stale);
            assertNull(cache.load(stale), "expired row reads as a miss");
            cache.store(fresh, DOC_A, TOKEN_A, items("ttl-writer"));
            Integer swept = fixtureRows(List.of(stale));
            assertEquals(0, swept,
                    "the next store must sweep expired orphans out of the table (R57 sweep face)");
        } finally {
            cleanupKeys(List.of(stale, fresh));
            cleanupByDocs(DOC_A);
        }
    }

    /**
     * Spectrum 3 — terminal delete face: a failed/cancelled run drops exactly
     * the rows IT pre-occupied (multi-row run), a null generation token is a
     * no-op guard, and replaying the terminal door stays idempotent at zero.
     */
    @Test
    void terminalDeleteDropsOnlyThisRunRowsAndReplaysIdempotent() {
        config("20000", "30");
        DocBucketResultCache cache = cache();
        List<String> mine = new ArrayList<>();
        String otherGen = keyOf(cache, "terminal-other-gen");
        String otherDoc = keyOf(cache, "terminal-other-doc");
        try {
            for (int i = 0; i < 3; i++) {
                String key = keyOf(cache, "terminal-mine-" + i);
                mine.add(key);
                cache.store(key, DOC_A, TOKEN_A, items("terminal-mine-" + i));
            }
            cache.store(otherGen, DOC_A, TOKEN_B, items("terminal-other-gen"));
            cache.store(otherDoc, DOC_B, TOKEN_A, items("terminal-other-doc"));

            // Cancel branch without a token must never wipe anything.
            assertEquals(0, cache.dropRunRows(DOC_A, null),
                    "null generation token is the no-op guard face");
            assertEquals(3, fixtureRows(mine), "guard must leave all rows in place");

            int dropped = cache.dropRunRows(DOC_A, TOKEN_A);
            assertEquals(3, dropped, "exactly this run's three rows leave");
            assertEquals(0, fixtureRows(mine));
            assertNotNull(cache.load(otherGen), "another generation keeps its rows");
            assertNotNull(cache.load(otherDoc), "another document keeps its rows");

            assertEquals(0, cache.dropRunRows(DOC_A, TOKEN_A),
                    "terminal door replay is idempotent (never double-counts a drop)");
        } finally {
            cleanupKeys(mine);
            cleanupKeys(List.of(otherGen, otherDoc));
            cleanupByDocs(DOC_A, DOC_B);
        }
    }

    /**
 * Spectrum 4 — hit observability face: the tally names the zero-hit case
 * the operator), counters follow real traffic, and a corrupt payload
 * degrades to a named miss rather than breaking the run.
 */
    @Test
    void tallyNamesZeroHitsAndFollowsRealTraffic() {
        config("20000", "30");
        DocBucketResultCache cache = cache();
        String key = keyOf(cache, "tally");
        try {
            String cold = cache.describeTally();
            assertTrue(cold.contains("命中=0"),
                    "zero-hit face must be printed explicitly, got: " + cold);
            assertTrue(cold.contains("行数="),
                    "the live row count is part of the same face, got: " + cold);

            assertNull(cache.load(key), "unknown key misses");
            assertTrue(cache.describeTally().contains("重算=1"), "miss counted");

            cache.store(key, DOC_A, TOKEN_A, items("tally"));
            assertNotNull(cache.load(key), "stored key hits");
            String hot = cache.describeTally();
            assertTrue(hot.contains("命中=1"), "hit counted, got: " + hot);

            // Corrupt payload (valid jsonb, wrong shape) must read as a miss.
            jdbcTemplate.update(
                    "UPDATE tp_doc_extract_bucket_cache SET items = '\"corrupt-scalar\"'::jsonb "
                            + "WHERE cache_key = ?", key);
            assertNull(cache.load(key), "unreadable payload degrades to a miss, never an error");
            assertTrue(cache.describeTally().contains("重算=2"),
                    "the fail-soft miss is counted on the observability face");
        } finally {
            cleanupKeys(List.of(key));
            cleanupByDocs(DOC_A);
        }
    }
}
