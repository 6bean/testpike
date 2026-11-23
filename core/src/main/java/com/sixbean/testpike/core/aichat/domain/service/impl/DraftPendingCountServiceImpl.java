package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.DraftPendingCountService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Counts undecided drafts per object scope . The two
 * counted states reference the SAME package-visible constants the
 * confirmation door transitions with — a status literal copied here would
 * drift the day the state machine grows (R40). Read-only: this service never
 * opens the door, it only tells the panel how many cards wait behind it.
 */
@Service
public class DraftPendingCountServiceImpl implements DraftPendingCountService {

    /**
     * One statement covers both project-anchor branches: dialog drafts keep
     * project_id NULL and inherit it from tp_ai_chat_session (NOT NULL there
     * since V295), system-origin drafts carry the anchor directly.
     */
    private static final String SELECT_PENDING_COUNT = """
            SELECT count(*)
            FROM tp_ai_chat_draft d
            LEFT JOIN tp_ai_chat_session s ON s.id = d.session_id
            WHERE COALESCE(d.project_id, s.project_id) = :projectId
              AND d.object_type = :objectType
              AND d.target_id = :targetId
              AND d.status IN (:pending, :stale)
            """;

    /**
 * Chat reference kinds (the six-value wire triple the pinned-object
 * panel consumes, translated to the draft ledger's
 * vocabulary. The panel sends the upper-cased kind, the ledger stores
 * wireNames; the translation lives at this single read door so no
 * client copies the table. A kind with no draft family (tool) falls
 * through to the raw value and honestly counts 0; exact wireNames pass
 * through untouched so existing callers keep their contract.
 */
    private static String toLedgerVocabulary(String rawObjectType) {
        if (rawObjectType == null) {
            return null;
        }
        return switch (rawObjectType.trim()) {
            case "CASE" -> DraftObjectType.CASE_WRITE.wireName;
            case "DEFECT" -> DraftObjectType.DEFECT_WRITE.wireName;
            case "BATCH" -> DraftObjectType.BATCH_WRITE.wireName;
            case "DOC" -> DraftObjectType.PROMPT_TPL_WRITE.wireName;
            case "SKILL" -> DraftObjectType.SKILL_WRITE.wireName;
            default -> rawObjectType.trim();
        };
    }

    private final NamedParameterJdbcTemplate named;

    public DraftPendingCountServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public int countPending(long projectId, String objectType, long targetId) {
        Integer count = named.queryForObject(SELECT_PENDING_COUNT,
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("objectType", toLedgerVocabulary(objectType))
                        .addValue("targetId", targetId)
                        .addValue("pending", DraftConfirmationServiceImpl.STATUS_PENDING)
                        .addValue("stale", DraftConfirmationServiceImpl.STATUS_STALE),
                Integer.class);
        return count == null ? 0 : count;
    }
}
