package com.sixbean.testpike.aiservice.doc.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.common.util.CanonicalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * cross-run reuse cache for final-extract bucket results.
 *
 * <p>Every re-run of the same document used to burn ALL buckets again (the
 * gold-sample windows paid ~2h of provider calls even when most buckets were
 * byte-identical to the previous run). This cache keys each bucket's item
 * list by SHA-256 over EVERY input that determines the model answer:
 * bucket source blocks, model id, rendered system prompt, injected knowledge,
 * the dimension hint and the split/budget parameter fingerprint. An
 * unchanged bucket is therefore reused verbatim with zero provider calls,
 * while a change on ANY input face rotates the key — a re-tuned pipeline can
 * never read back a stale answer (the anti-stale contract).
 *
 * <p>R57 pairing declared at the creation point:
 * <ul>
 * <li>row cap {@code ai.doc.bucket-cache-max-rows} — LRU eviction ordered by
 * {@code COALESCE(last_hit_at, created_at)} runs after every store;</li>
 * <li>TTL {@code ai.doc.bucket-cache-ttl-days} — expired rows read as misses
 * and are swept out on the next store (orphan sweep face);</li>
 * <li>terminal delete — a failed or cancelled run drops the rows it
 * pre-occupied via {@link #dropRunRows} (wired at the DocExtract
 * terminal door);</li>
 * <li>observability — hits/misses counters plus the live row count surface
 * through {@link #describeTally} into the bucket-plane log line.</li>
 * </ul>
 *
 * <p>The switch ships ON (R32 AI-feature default-on): a missing, blank or
 * garbage read stays ON with a warn line naming the key; only false/0 turn it
 * off, and the transition to OFF logs the operator guidance (which key, where
 * to change it). While off, load never hits and store never writes — the
 * behaviour stays byte-identical to the un-cached face. Read/parse failures
 * degrade to a miss (warn line, the bucket burns normally); the cache is an
 * accelerator, never a failure source. Empty item lists are NOT cached — a
 * bucket that produced nothing may legitimately be re-attempted rather than
 * pinned as a permanent empty hit.
 */
@Component
public class DocBucketResultCache {

    private static final Logger log = LoggerFactory.getLogger(DocBucketResultCache.class);

    /** K8 switch names — single-sourced here (seeded by V536). */
    public static final String KEY_BUCKET_CACHE_ENABLED = "ai.doc.bucket-cache.enabled";
    public static final String KEY_BUCKET_CACHE_MAX_ROWS = "ai.doc.bucket-cache-max-rows";
    public static final String KEY_BUCKET_CACHE_TTL_DAYS = "ai.doc.bucket-cache-ttl-days";

    /** Factory defaults + accepted intervals (reader clamp mirrors the seed). */
    static final int DEFAULT_MAX_ROWS = 20000;
    static final int MIN_MAX_ROWS = 1;
    static final int MAX_MAX_ROWS = 200000;
    static final int DEFAULT_TTL_DAYS = 30;
    static final int MIN_TTL_DAYS = 1;
    static final int MAX_TTL_DAYS = 365;

    /** Control separator between hashed segments — never occurs in the texts. */
    private static final char SEGMENT_SEPARATOR = '\u001f';

    /**
     * The block fields that DETERMINE the model answer — exactly what the
     * bucket prompt renders (the header reads seq/chapter, the body reader
     * reads content). Everything else on a source block is projection
     * plumbing: notably the evidence-anchor row id exposed for preview
     * jump-to-source is never a model input, and hashing it rotated the key
     * on every re-ingest/other document, so unchanged buckets could never be
     * reused (witnessed by the zero hit_count ledger across all runs). If the
     * prompt ever consumes a new block field, it MUST be registered here —
     * that is the anti-stale contract of the adjudication.
     */
    private static final List<String> PROMPT_FACES = List.of("seq", "chapter", "content");

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;
    private final CoreGateway coreGateway;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    /** Edge detector for the one-shot OFF guidance line (no per-bucket spam). */
    private volatile boolean lastVerdictOn = true;

    public DocBucketResultCache(NamedParameterJdbcTemplate named, ObjectMapper objectMapper,
                                CoreGateway coreGateway) {
        this.named = named;
        this.objectMapper = objectMapper;
        this.coreGateway = coreGateway;
    }

    /**
     * Hot read of the switch on every cache touch (no cached verdict — an
     * operator flip takes effect on the next bucket). Ships ON: only
     * false/0 (case-insensitive) turn it off; anything else stays ON, with a
     * warn naming the key when the stored value is not a legal spelling.
     * The transition into OFF names the key and where to change it (R32
     * guidance face), logged once per edge instead of once per bucket.
     */
    public boolean enabled() {
        String raw = coreGateway.getSysConfig(KEY_BUCKET_CACHE_ENABLED);
        if (raw != null && !raw.isBlank() && !SysConfigBooleans.isLegal(raw)) {
            log.warn("K8 {}={} 非法（允许 true/false），按出厂开启处理", KEY_BUCKET_CACHE_ENABLED, raw);
        }
        boolean on = SysConfigBooleans.parse(raw, true);
        if (!on && lastVerdictOn) {
            log.warn("桶结果复用已关闭（key={}）：后续重跑将重新调用模型生成每个桶；"
                    + "恢复方法=系统设置-AI参数分组-本键改为 true，保存即生效",
                    KEY_BUCKET_CACHE_ENABLED);
        }
        lastVerdictOn = on;
        return on;
    }

    /**
     * Content key of one bucket: SHA-256 hex over every input that determines
     * the model answer. A serialization failure returns null — the caller
     * then runs the uncached face (fail-soft, the cache never breaks a run).
     */
    public String bucketKey(List<Map<String, Object>> blocks, String model,
                            String systemPrompt, String knowledge,
                            String dimensionHint, String paramVersion) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        try {
            String joined = CanonicalJson.write(objectMapper, promptProjection(blocks))
                    + SEGMENT_SEPARATOR + nz(model)
                    + SEGMENT_SEPARATOR + nz(systemPrompt)
                    + SEGMENT_SEPARATOR + nz(knowledge)
                    + SEGMENT_SEPARATOR + nz(dimensionHint)
                    + SEGMENT_SEPARATOR + nz(paramVersion);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("桶缓存键序列化失败，本桶按未启用缓存直通: {}", e.getMessage());
            return null;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 missing on this JVM", impossible);
        }
    }

    /**
     * Content projection of the bucket blocks: for each block keep exactly
     * {@link #PROMPT_FACES} in a fixed field order (the canonical writer
     * sorts keys anyway; the fixed pick makes the intent explicit). The list
     * ORDER itself stays hashed — a re-ordered bucket is a different prompt.
     */
    private static List<Map<String, Object>> promptProjection(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> projected = new ArrayList<>(blocks.size());
        for (Map<String, Object> block : blocks) {
            Map<String, Object> face = new LinkedHashMap<>();
            for (String field : PROMPT_FACES) {
                if (block != null && block.containsKey(field)) {
                    face.put(field, block.get(field));
                }
            }
            projected.add(face);
        }
        return projected;
    }

    /**
     * Reuse face: the cached item list of one key, or null when the switch is
     * off, no fresh row exists, or the payload cannot be parsed (fail-soft →
     * the bucket burns normally). A hit marks the row (hit_count + last_hit_at)
     * so the LRU order follows real usage.
     */
    public List<Map<String, Object>> load(String cacheKey) {
        if (cacheKey == null || !enabled()) {
            return null;
        }
        List<String> raw = named.query(
                "SELECT items FROM tp_doc_extract_bucket_cache "
                        + "WHERE cache_key = :key AND created_at > now() "
                        + "- make_interval(days => :ttl)",
                Map.of("key", cacheKey, "ttl", readInt(KEY_BUCKET_CACHE_TTL_DAYS,
                        DEFAULT_TTL_DAYS, MIN_TTL_DAYS, MAX_TTL_DAYS)),
                (rs, rowNum) -> rs.getString("items"));
        if (raw.isEmpty()) {
            misses.incrementAndGet();
            return null;
        }
        try {
            List<Map<String, Object>> items = objectMapper.readValue(raw.get(0),
                    new TypeReference<List<Map<String, Object>>>() { });
            if (items == null || items.isEmpty()) {
                misses.incrementAndGet();
                return null;
            }
            named.update("UPDATE tp_doc_extract_bucket_cache "
                            + "SET hit_count = hit_count + 1, last_hit_at = now() "
                            + "WHERE cache_key = :key",
                    Map.of("key", cacheKey));
            hits.incrementAndGet();
            return items;
        } catch (Exception e) {
            log.warn("F2 桶结果缓存行不可解析，按未命中处理 key={}: {}", cacheKey, e.getMessage());
            misses.incrementAndGet();
            return null;
        }
    }

    /**
     * Persist one freshly computed bucket. Empty lists are not cached; the
     * upsert key keeps a re-run of the same content cheap; the expired-row
     * sweep and the capped LRU eviction run right here so the table stays
     * bounded between runs (R57). No-op while the switch is off.
     */
    public void store(String cacheKey, long documentId, String genToken,
                      List<Map<String, Object>> items) {
        if (cacheKey == null || genToken == null || items == null || items.isEmpty()
                || !enabled()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            log.warn("F2 桶结果缓存序列化失败，跳过落盘 key={}: {}", cacheKey, e.getMessage());
            return;
        }
        int ttlDays = readInt(KEY_BUCKET_CACHE_TTL_DAYS, DEFAULT_TTL_DAYS,
                MIN_TTL_DAYS, MAX_TTL_DAYS);
        named.update("DELETE FROM tp_doc_extract_bucket_cache "
                        + "WHERE created_at <= now() - make_interval(days => :ttl)",
                Map.of("ttl", ttlDays));
        named.update("INSERT INTO tp_doc_extract_bucket_cache "
                        + "(cache_key, document_id, gen_token, items) "
                        + "VALUES (:key, :doc, :tok, CAST(:items AS jsonb)) "
                        + "ON CONFLICT (cache_key) DO UPDATE SET items = EXCLUDED.items, "
                        + "document_id = EXCLUDED.document_id, gen_token = EXCLUDED.gen_token, "
                        + "created_at = now(), last_hit_at = NULL",
                Map.of("key", cacheKey, "doc", documentId, "tok", genToken, "items", json));
        int maxRows = readInt(KEY_BUCKET_CACHE_MAX_ROWS, DEFAULT_MAX_ROWS,
                MIN_MAX_ROWS, MAX_MAX_ROWS);
        named.update("DELETE FROM tp_doc_extract_bucket_cache WHERE id IN ("
                        + "SELECT id FROM tp_doc_extract_bucket_cache "
                        + "ORDER BY COALESCE(last_hit_at, created_at) ASC "
                        + "LIMIT GREATEST(0, (SELECT count(*) FROM tp_doc_extract_bucket_cache) "
                        + "- :max))",
                Map.of("max", maxRows));
    }

    /**
     * Terminal pairing of a failed/cancelled run: delete exactly the rows the
     * run pre-occupied (other documents/generations keep theirs). Returns the
     * dropped row count for the log line.
     */
    public int dropRunRows(long documentId, String genToken) {
        if (genToken == null) {
            return 0;
        }
        return named.update("DELETE FROM tp_doc_extract_bucket_cache "
                        + "WHERE document_id = :doc AND gen_token = :tok",
                Map.of("doc", documentId, "tok", genToken));
    }

    /** Reuse ledger for the bucket-plane line and the close-out report. */
    public String describeTally() {
        Integer rows = named.queryForObject(
                "SELECT count(*) FROM tp_doc_extract_bucket_cache", Map.of(), Integer.class);
        return "命中=" + hits.get() + " 重算=" + misses.get()
                + " 行数=" + (rows == null ? 0 : rows);
    }

    /** Reader clamp shared by both int faces: garbage/out-of-interval falls
     *  back to the factory default with a warn naming the key. */
    private int readInt(String key, int factory, int low, int high) {
        String raw = coreGateway.getSysConfig(key);
        if (raw == null || raw.isBlank()) {
            return factory;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= low && value <= high) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the clamp warn below
        }
        log.warn("K8 {}={} 非法或超区间[{},{}]，按出厂 {} 处理", key, raw, low, high, factory);
        return factory;
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
