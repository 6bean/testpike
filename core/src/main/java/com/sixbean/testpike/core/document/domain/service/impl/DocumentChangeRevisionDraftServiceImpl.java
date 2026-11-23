package com.sixbean.testpike.core.document.domain.service.impl;

import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatRunService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;
import com.sixbean.testpike.core.document.api.dto.DocChangeItem;
import com.sixbean.testpike.core.document.api.dto.RevisionDraftPregen;
import com.sixbean.testpike.core.document.domain.component.DocChangeAnalysisStatus;
import com.sixbean.testpike.core.document.domain.service.DocumentChangeAnalysisService;
import com.sixbean.testpike.core.document.domain.service.DocumentChangeRevisionDraftService;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Lazy pre-generation behind the pending change-analysis disposition view
 * .
 * <p>Opening the view dispatches exactly one anchored dialog run per live,
 * same-project requirement that still has new chapter content — through
 * {@link ChatRunService#openRun}, the single run write source the human dialog
 * uses (landDraft/confirm door untouched: red line 10c keeps the human
 * one-click confirmation, and a requirement is never written here).
 * <p>Idempotency is a double anchor on the deep-linked session
 * (user, project, source 3, context analysisId): a pending REQUIREMENT_WRITE
 * draft row already covering the requirement, or a sealed user echo carrying
 * its {@code (id=<requirementId>)} anchor, both block a second run. A chapter
 * whose new body is empty (deleted chapter) honestly lands zero cards — the
 * AI is never asked to draft against a blank.
 * <p>No outer transaction: each door (session create, run open) manages its
 * own segment; one requirement's dispatch failure must not unseal the echoes
 * already written for the others.
 */
@Service
public class DocumentChangeRevisionDraftServiceImpl implements DocumentChangeRevisionDraftService {

    
    private static final int SESSION_SOURCE_DEEP_LINK = 3;
    /** tp_ai_chat_draft status 1 = pending behind the confirmation door. */
    private static final int DRAFT_STATUS_PENDING = 1;
    private static final String DRAFT_OBJECT_REQUIREMENT_WRITE = "REQUIREMENT_WRITE";

    private final DocumentChangeAnalysisService analysisService;
    private final ChatSessionService sessionService;
    private final ChatRunService runService;
    private final NamedParameterJdbcTemplate named;

    public DocumentChangeRevisionDraftServiceImpl(DocumentChangeAnalysisService analysisService,
                                                  ChatSessionService sessionService,
                                                  ChatRunService runService,
                                                  NamedParameterJdbcTemplate named) {
        this.analysisService = analysisService;
        this.sessionService = sessionService;
        this.runService = runService;
        this.named = named;
    }

    @Override
    public RevisionDraftPregen pregenDrafts(long operatorId, long analysisId) {
        DocChangeAnalysisInfo analysis = analysisService.get(analysisId);
        if (analysis.status() != DocChangeAnalysisStatus.PENDING) {
            // Handled analyses answer all-zero and never touch the ledger.
            return new RevisionDraftPregen(analysisId, null, 0, 0, 0, 0, 0);
        }
        LinkedHashSet<Long> snapshotIds = new LinkedHashSet<>();
        for (DocChangeItem item : analysis.changes()) {
            snapshotIds.addAll(item.requirementIds());
        }
        if (snapshotIds.isEmpty()) {
            // No requirement impact: honest zero receipt, never an empty session shell.
            return new RevisionDraftPregen(analysisId, null, 0, 0, 0, 0, 0);
        }
        Map<Long, PregenRequirementRow> liveRows = loadLiveRows(snapshotIds);

        Long sessionId = null;
        int dispatched = 0;
        int skippedExisting = 0;
        int skippedForeign = 0;
        int skippedMissing = 0;
        int skippedNoContent = 0;
        Map<String, String> bodyByChapter = new LinkedHashMap<>();

        for (DocChangeItem item : analysis.changes()) {
            String chapterKey = item.chapter() == null ? "" : item.chapter();
            String body = bodyByChapter.computeIfAbsent(chapterKey,
                    key -> analysisService.newChapterContent(analysisId, key));
            for (Long requirementId : item.requirementIds()) {
                PregenRequirementRow row = liveRows.get(requirementId);
                if (row == null) {
                    // Soft-deleted after the snapshot was taken — named, never silent.
                    skippedMissing++;
                    continue;
                }
                if (row.projectId() != analysis.projectId()) {
                    // Attribution guard: foreign-project rows are refused before any run.
                    skippedForeign++;
                    continue;
                }
                if (body.isBlank()) {
                    // Deleted chapter (empty new body): honestly zero cards.
                    skippedNoContent++;
                    continue;
                }
                if (sessionId == null) {
                    sessionId = findAnchoredSession(operatorId, analysis.projectId(), analysisId);
                    if (sessionId == null) {
                        // Blank title rides the first-question auto-naming.
                        ChatSessionInfo created = sessionService.create(
                                analysis.projectId(), operatorId, "", SESSION_SOURCE_DEEP_LINK,
                                "{\"analysisId\":" + analysisId + "}");
                        sessionId = created.id();
                    }
                }
                if (alreadyCovered(sessionId, requirementId)) {
                    skippedExisting++;
                    continue;
                }
                runService.openRun(sessionId, operatorId,
                        anchoredPrompt(row, chapterKey, body), List.of(), List.of());
                dispatched++;
            }
        }
        return new RevisionDraftPregen(analysisId, sessionId,
                dispatched, skippedExisting, skippedForeign, skippedMissing, skippedNoContent);
    }

    /** Live (deleted = 0) rows keyed by id; snapshot ids with no live row count as missing. */
    private Map<Long, PregenRequirementRow> loadLiveRows(LinkedHashSet<Long> snapshotIds) {
        Map<Long, PregenRequirementRow> rows = new LinkedHashMap<>();
        named.query("SELECT id, project_id, title, version FROM tp_requirement"
                        + " WHERE id IN (:ids) AND deleted = 0",
                new MapSqlParameterSource("ids", snapshotIds),
                (RowCallbackHandler) rs -> rows.put(rs.getLong("id"), new PregenRequirementRow(
                        rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("title"), rs.getInt("version"))));
        return rows;
    }

    /** The one deep-linked session for this analysis, or null when none exists yet. */
    private Long findAnchoredSession(long operatorId, long projectId, long analysisId) {
        List<Long> found = named.queryForList(
                "SELECT id FROM tp_ai_chat_session WHERE user_id = :operatorId AND project_id = :projectId"
                        + " AND source = :source AND context_json->>'analysisId' = :analysisId"
                        + " ORDER BY id LIMIT 1",
                new MapSqlParameterSource()
                        .addValue("operatorId", operatorId)
                        .addValue("projectId", projectId)
                        .addValue("source", SESSION_SOURCE_DEEP_LINK)
                        .addValue("analysisId", String.valueOf(analysisId)),
                Long.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Double anchor: a pending REQUIREMENT_WRITE draft row or a sealed anchored user echo. */
    private boolean alreadyCovered(long sessionId, long requirementId) {
        Integer drafts = named.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_draft WHERE session_id = :sessionId"
                        + " AND object_type = :objectType AND target_id = :requirementId AND status = :pending",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("objectType", DRAFT_OBJECT_REQUIREMENT_WRITE)
                        .addValue("requirementId", requirementId)
                        .addValue("pending", DRAFT_STATUS_PENDING),
                Integer.class);
        if (drafts != null && drafts > 0) {
            return true;
        }
        Integer echoes = named.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_message WHERE session_id = :sessionId"
                        + " AND role = 'user' AND content LIKE :anchor",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("anchor", "%(id=" + requirementId + ")%"),
                Integer.class);
        return echoes != null && echoes > 0;
    }

    /**
     * The anchored prompt: the {@code (id=<requirementId>)} anchor lets the
     * draft strategy and this door's replay guard name the exact requirement,
     * while the chapter body, the title and the current version give the AI
     * what it needs to propose — it still only ever drafts, never applies.
     */
    private String anchoredPrompt(PregenRequirementRow row, String chapterKey, String body) {
        // Prompt reachability contract : the catalog is
        // larger than the pre-bound window, so a merely descriptive ask left the
        // model answering in plain text with zero cards. The prompt now names the
        // real tool, hands over its anchor parameter values and orders the tool
        // load — the human confirmation door is unchanged (red line 10c).
        return "The requirement (id=" + row.id() + ") \"" + row.title()
                + "\" is at version " + row.version() + " and sits in chapter \"" + chapterKey
                + "\", which a document re-upload just changed. Compare the requirement against"
                + " the new chapter body below. Then load the write tool with search_tools"
                + " (query \"requirement.update\") and call requirement.update with"
                + " requirementId=" + row.id() + ", baseVersion=" + row.version()
                + " and the field-level changes the new chapter demands (title,"
                + " acceptanceCriteria, chapter). Only when the anchored requirement no longer"
                + " applies at all, load and call requirement.create instead for a new companion"
                + " requirement. Propose, never apply: every call lands a draft card behind the"
                + " human confirmation door.\n\n--- NEW CHAPTER BODY (" + chapterKey + ") ---\n"
                + body;
    }
}
