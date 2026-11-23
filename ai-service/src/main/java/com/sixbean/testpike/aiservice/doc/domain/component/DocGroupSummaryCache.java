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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disk cache for the over-budget extraction round, keyed by
 * (document_id, gen_token, group_seq, plane). segment B introduced the
 * summary plane (F5 Map round, re-keyed and re-grained); remediation
 * ② adds the items plane (grouped raw extraction face of .
 * The switch is shared and lives with its only consumer:
 * {@code ai.doc.summary-cache.enabled} (the old ai.ctx.* spelling was a key
 * family misplacement — the extraction chain is the sole reader).
 * <p>Row families under one key:
 * <ul>
 * <li>summary plane — the whole-round row (group_seq = 0, list JSON) written
 * once per completed Map round, plus per-group rows (group_seq &gt;= 1,
 * single-block JSON) written as soon as one group's summary lands;</li>
 * <li>items plane — per-group rows (group_seq &gt;= 1, item-list JSON)
 * written as soon as one raw group is extracted.</li>
 * </ul>
 * That per-group grain is what lets a mid-round failure resume from where it
 * stopped instead of re-burning every model call of the round (the document
 * 8491 / 17-group pattern).
 * <p>The switch ships ON (R32 AI-feature default-on): a missing, blank or
 * garbage read stays ON with a warn line naming the key; only the false/0
 * spellings turn it off. While off, every method is a silent no-op and the
 * behaviour stays byte-identical to the un-cached face. Read/parse failures
 * degrade to a miss (warn line, the round runs normally) rather than failing
 * the extraction.
 */
@Component
public class DocGroupSummaryCache {

    private static final Logger log = LoggerFactory.getLogger(DocGroupSummaryCache.class);

    /** K8 switch name — single-sourced here (write side: ConfigServiceImpl E8 case). */
    public static final String KEY_SUMMARY_CACHE_ENABLED = "ai.doc.summary-cache.enabled";

    /** Whole-round rows carry this group sequence; per-group rows use >= 1. */
    private static final int ROUND_SEQ = 0;

    /** Row family of the F5 Map round (summaries) — the historical plane. */
    private static final String PLANE_SUMMARY = "summary";

    /** Row family of the grouped raw plane (extracted requirement items). */
    private static final String PLANE_ITEMS = "items";

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;
    private final CoreGateway coreGateway;

    public DocGroupSummaryCache(NamedParameterJdbcTemplate named, ObjectMapper objectMapper,
                                CoreGateway coreGateway) {
        this.named = named;
        this.objectMapper = objectMapper;
        this.coreGateway = coreGateway;
    }

    /**
     * Hot read of the switch on every cache touch (no cached verdict — an
     * operator flip takes effect on the next group). Ships ON: only
     * false/0 (case-insensitive) turn it off; anything else stays ON, with a
     * warn naming the key when the stored value is not a legal spelling.
     */
    public boolean enabled() {
        String raw = coreGateway.getSysConfig(KEY_SUMMARY_CACHE_ENABLED);
        if (raw != null && !raw.isBlank() && !SysConfigBooleans.isLegal(raw)) {
            log.warn("K8 {}={} 非法（允许 true/false），按出厂开启处理", KEY_SUMMARY_CACHE_ENABLED, raw);
        }
        return SysConfigBooleans.parse(raw, true);
    }

    /**
     * Cached whole-round summary blocks (group_seq = 0 row) for this
     * generation, or null when the switch is off, no row exists, or the
     * payload cannot be parsed (fail-soft → Map round runs).
     */
    public List<Map<String, Object>> load(long documentId, String genToken) {
        if (genToken == null || !enabled()) {
            return null;
        }
        List<String> raw = named.query(
                "SELECT blocks FROM tp_doc_summary_cache "
                        + "WHERE document_id = :doc AND gen_token = :tok AND group_seq = " + ROUND_SEQ
                        + " AND plane = :plane",
                Map.of("doc", documentId, "tok", genToken, "plane", PLANE_SUMMARY),
                (rs, rowNum) -> rs.getString("blocks"));
        if (raw.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> blocks = objectMapper.readValue(raw.get(0),
                    new TypeReference<List<Map<String, Object>>>() { });
            return blocks.isEmpty() ? null : blocks;
        } catch (Exception e) {
            log.warn("F5 分组摘要缓存行不可解析，按未命中处理 documentId={} gen={}: {}",
                    documentId, genToken, e.getMessage());
            return null;
        }
    }

    /**
     * Persist the completed round as the group_seq = 0 row. Rows of previous
     * generations of the same document are deleted first — that delete IS the
     * generation-change invalidation. No-op while the switch is off.
     */
    public void save(long documentId, String genToken, List<Map<String, Object>> blocks) {
        if (genToken == null || blocks == null || blocks.isEmpty() || !enabled()) {
            return;
        }
        String json;
        try {
            json = CanonicalJson.write(objectMapper, blocks);
        } catch (Exception e) {
            log.warn("F5 分组摘要缓存序列化失败，跳过落盘 documentId={}: {}", documentId, e.getMessage());
            return;
        }
        evictOldGenerations(documentId, genToken);
        upsertPlane(documentId, genToken, ROUND_SEQ, PLANE_SUMMARY, json);
    }

    /**
     * Per-group resume face (summary plane): cached summary blocks for this
     * generation keyed by group sequence (empty map when off or nothing is
     * cached). A row that cannot be parsed is skipped (fail-soft → that group
     * re-summarises).
     */
    public Map<Integer, Map<String, Object>> loadGroups(long documentId, String genToken) {
        Map<Integer, Map<String, Object>> groups = new LinkedHashMap<>();
        if (genToken == null || !enabled()) {
            return groups;
        }
        List<Object[]> rows = named.query(
                "SELECT group_seq, blocks FROM tp_doc_summary_cache "
                        + "WHERE document_id = :doc AND gen_token = :tok AND group_seq >= 1 "
                        + "AND plane = :plane ORDER BY group_seq",
                Map.of("doc", documentId, "tok", genToken, "plane", PLANE_SUMMARY),
                (rs, rowNum) -> new Object[]{rs.getInt("group_seq"), rs.getString("blocks")});
        for (Object[] row : rows) {
            try {
                groups.put((Integer) row[0], objectMapper.readValue((String) row[1],
                        new TypeReference<Map<String, Object>>() { }));
            } catch (Exception e) {
                log.warn("F5 分组摘要缓存行不可解析，按未命中处理 documentId={} gen={} 组序={}: {}",
                        documentId, genToken, row[0], e.getMessage());
            }
        }
        return groups;
    }

    /**
     * Persist ONE computed summary group immediately (group_seq >= 1 row,
     * single-block JSON) so a later failure in the same round resumes from
     * this group instead of re-burning it. Old generations are evicted first,
     * same as the whole-round face; the upsert key keeps repeats of the same
     * group cheap.
     */
    public void saveGroup(long documentId, String genToken, int groupSeq, Map<String, Object> block) {
        if (genToken == null || block == null || groupSeq < 1 || !enabled()) {
            return;
        }
        String json;
        try {
            json = CanonicalJson.write(objectMapper, block);
        } catch (Exception e) {
            log.warn("F5 分组摘要逐组缓存序列化失败，跳过落盘 documentId={} 组序={}: {}",
                    documentId, groupSeq, e.getMessage());
            return;
        }
        evictOldGenerations(documentId, genToken);
        upsertPlane(documentId, genToken, groupSeq, PLANE_SUMMARY, json);
    }

    /**
 * remediation ② — per-group resume face of the GROUPED RAW plane:
 * extracted requirement items for this generation keyed by group sequence
 * (empty map when off or nothing is cached). A row that cannot be parsed
 * is skipped (fail-soft → that group re-extracts). The items a hit hands
 * back re-enter the pipeline exactly where freshly computed items leave
 * it (anchor re-attribution downstream is idempotent by design).
 */
    public Map<Integer, List<Map<String, Object>>> loadItemGroups(long documentId, String genToken) {
        Map<Integer, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        if (genToken == null || !enabled()) {
            return groups;
        }
        List<Object[]> rows = named.query(
                "SELECT group_seq, blocks FROM tp_doc_summary_cache "
                        + "WHERE document_id = :doc AND gen_token = :tok AND group_seq >= 1 "
                        + "AND plane = :plane ORDER BY group_seq",
                Map.of("doc", documentId, "tok", genToken, "plane", PLANE_ITEMS),
                (rs, rowNum) -> new Object[]{rs.getInt("group_seq"), rs.getString("blocks")});
        for (Object[] row : rows) {
            try {
                List<Map<String, Object>> items = objectMapper.readValue((String) row[1],
                        new TypeReference<List<Map<String, Object>>>() { });
                groups.put((Integer) row[0], items);
            } catch (Exception e) {
                log.warn("F2 直提分组条目缓存行不可解析，按未命中处理 documentId={} gen={} 组序={}: {}",
                        documentId, genToken, row[0], e.getMessage());
            }
        }
        return groups;
    }

    /**
     * Persist ONE finished raw group's items immediately (group_seq >= 1 row
     * on the items plane) so a later failure in the same generation resumes
     * from the next group instead of re-burning this one. An empty item list
     * is NOT cached — a group that produced nothing may legitimately be
     * re-attempted rather than pinned as a permanent empty hit. Old
     * generations are evicted first (shared door with the summary face).
     */
    public void saveItemGroup(long documentId, String genToken, int groupSeq,
                              List<Map<String, Object>> items) {
        if (genToken == null || items == null || items.isEmpty() || groupSeq < 1 || !enabled()) {
            return;
        }
        String json;
        try {
            json = CanonicalJson.write(objectMapper, items);
        } catch (Exception e) {
            log.warn("F2 直提分组条目缓存序列化失败，跳过落盘 documentId={} 组序={}: {}",
                    documentId, groupSeq, e.getMessage());
            return;
        }
        evictOldGenerations(documentId, genToken);
        upsertPlane(documentId, genToken, groupSeq, PLANE_ITEMS, json);
    }

    /** Upsert one row of one plane — the single write seam for both faces (R40). */
    private void upsertPlane(long documentId, String genToken, int groupSeq,
                             String plane, String json) {
        named.update("INSERT INTO tp_doc_summary_cache "
                        + "(document_id, gen_token, group_seq, plane, blocks) "
                        + "VALUES (:doc, :tok, :seq, :plane, CAST(:blocks AS jsonb)) "
                        + "ON CONFLICT (document_id, gen_token, group_seq, plane) DO UPDATE "
                        + "SET blocks = EXCLUDED.blocks, updated_at = now()",
                Map.of("doc", documentId, "tok", genToken, "seq", groupSeq,
                        "plane", plane, "blocks", json));
    }

    /** Generation-change invalidation shared by every write face (R40 single source). */
    private void evictOldGenerations(long documentId, String genToken) {
        named.update("DELETE FROM tp_doc_summary_cache WHERE document_id = :doc AND gen_token <> :tok",
                Map.of("doc", documentId, "tok", genToken));
    }
}
