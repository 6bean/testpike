package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single-source reader for the per-document usage switches on tp_document
 * . Every routing decision in
 * ai-service asks this one component — neither the case-generation prompt
 * assembly nor the dialog grounding assembly may re-read the columns itself
 * (one decision per consumption point, no second retrieval chain).
 * <p>Id domain: retrieval results carry the KNOWLEDGE LEDGER id
 * (tp_knowledge_doc.id), not the tp_document primary key. This reader accepts
 * ledger ids, resolves them to their backing tp_document row through
 * tp_knowledge_doc.source_ref (only for the document-upload source types
 * TEST_SPEC / DDL_DICT — other ledger rows such as MCP_SYNC or TEST_CASE keep
 * source_ref in a different domain and must never be resolved here, even when
 * the number happens to collide with an existing tp_document id), reads the
 * switch and returns ids in the SAME ledger domain so every caller can
 * subtract directly from its own hit list. Live-chain evidence for the
 * mapping: tp_knowledge_hit rows and citation frames all key on ledger ids.
 * <p>Failure posture: an unreachable ledger means the opt-out cannot be
 * proven, so no document is excluded (fail-soft, matches the historical
 * all-on behaviour and the zero-drift guarantee for existing TEST_SPEC rows);
 * documents with no tp_document row at all (chunks born from cases, packs,
 * styles) are never excluded.
 * <p>The column argument is restricted to the whitelist below because it is
 * composed into the SQL — anything else is rejected before touching the
 * database.
 */
public final class DocumentUsageSwitchReader {

    private static final Logger log = LoggerFactory.getLogger(DocumentUsageSwitchReader.class);

    /** Usage-switch columns readable through this single source. */
    public static final String COLUMN_USE_IN_CASE_PROMPT = "use_in_case_prompt";
    public static final String COLUMN_USE_IN_GROUNDING = "use_in_grounding";

    private static final Set<String> KNOWN_COLUMNS =
            Set.of(COLUMN_USE_IN_CASE_PROMPT, COLUMN_USE_IN_GROUNDING);

    private final NamedParameterJdbcTemplate jdbc;

    public DocumentUsageSwitchReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ledger source types whose source_ref lives in the tp_document domain
     *  (document uploads; everything else keeps a foreign id domain). */
    private static final String DOC_BACKED_SOURCE_TYPES = "'TEST_SPEC','DDL_DICT'";

    /**
     * Ids of documents explicitly switched off for this usage. Input and
     * output share the knowledge-ledger id domain (retrieval results key on
     * tp_knowledge_doc.id; the ledger row is resolved to its backing
     * tp_document switch through source_ref). Empty when the ledger is
     * unreachable or none of the ids carries a switch-off row.
     */
    public Set<Long> optedOut(Collection<Long> docIds, String column) {
        if (docIds == null || docIds.isEmpty()) {
            return Set.of();
        }
        if (!KNOWN_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("未知用途开关列 " + column + "（只读白名单列）");
        }
        Set<Long> ids = new HashSet<>(docIds);
        String sql = "SELECT k.id AS id, d." + column + " AS opted"
                + " FROM tp_knowledge_doc k"
                + " JOIN tp_document d ON d.id = k.source_ref"
                + " WHERE k.id IN (:ids)"
                + " AND k.source_type IN (" + DOC_BACKED_SOURCE_TYPES + ")";
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql,
                    new MapSqlParameterSource("ids", ids));
            Set<Long> off = new HashSet<>();
            for (Map<String, Object> row : rows) {
                Object id = row.get("id");
                if (id instanceof Number number && Boolean.FALSE.equals(row.get("opted"))) {
                    off.add(number.longValue());
                }
            }
            return off;
        } catch (RuntimeException e) {
            log.warn("用途开关读取不可达，本次不排除任何文档 column={}: {}", column, e.getMessage());
            return Set.of();
        }
    }
}
