package com.sixbean.testpike.core.document.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;
import com.sixbean.testpike.core.document.api.dto.DocChangeItem;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.component.DocChangeAnalysisStatus;
import com.sixbean.testpike.core.document.domain.component.DocChunkDiff;
import com.sixbean.testpike.core.document.domain.component.ChapterDelta;
import com.sixbean.testpike.core.document.domain.component.ExtractionSourceId;
import com.sixbean.testpike.core.document.domain.service.DocumentChangeAnalysisService;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.domain.service.LinkageImpactConsumptionService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class DocumentChangeAnalysisServiceImpl implements DocumentChangeAnalysisService {

    
    static final String EVENT_DOC_CHANGE_ANALYZED = "DOC_CHANGE_ANALYZED";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DocumentChangeAnalysisServiceImpl.class);

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, old_document_id, new_document_id, changes::text AS changes_text,
                   chapter_locatable, status, created_by, created_at,
                   handled_by, handled_at, handle_note
            FROM tp_doc_change_analysis
            """;

    /** Requirement status 2 = changed (tp_requirement.status value domain). */
    private static final int REQUIREMENT_STATUS_CHANGED = 2;

    private final NamedParameterJdbcTemplate named;
    private final DocumentService documentService;
    private final DocChunkDiff docChunkDiff;
    private final ObjectMapper objectMapper;
    private final ProjectOwners projectOwners;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final RequirementService requirementService;
    private final LinkageImpactConsumptionService linkageConsumption;

    public DocumentChangeAnalysisServiceImpl(NamedParameterJdbcTemplate named,
                                             DocumentService documentService,
                                             DocChunkDiff docChunkDiff,
                                             ObjectMapper objectMapper,
                                             ProjectOwners projectOwners,
                                             NotificationService notificationService,
                                             AfterCommitExecutor afterCommitExecutor,
                                             RequirementService requirementService,
                                             LinkageImpactConsumptionService linkageConsumption) {
        this.named = named;
        this.documentService = documentService;
        this.docChunkDiff = docChunkDiff;
        this.objectMapper = objectMapper;
        this.projectOwners = projectOwners;
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
        this.requirementService = requirementService;
        this.linkageConsumption = linkageConsumption;
    }

    @Override
    @Transactional
    public DocChangeAnalysisInfo analyze(long operatorId, long newDocumentId) {
        DocumentInfo newDoc = documentService.get(newDocumentId);
        if (newDoc.status() != 1) {
            throw new ServiceException("文档尚未解析成功，无法做变更分析（当前状态需为解析成功），请在「文档列表」等待解析完成或重新上传文档后再发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        DocumentInfo oldDoc = findPreviousVersion(newDoc);

        // Replay guard: this (old,new) pair was already analyzed (typically the
        // lineage auto-pair fired at upload time). Return the recorded row
        // instead of computing a second pass — no duplicate review notification.
        List<Long> paired = named.queryForList(
                "SELECT id FROM tp_doc_change_analysis"
                        + " WHERE old_document_id = :oldId AND new_document_id = :newId"
                        + " ORDER BY id LIMIT 1",
                new MapSqlParameterSource()
                        .addValue("oldId", oldDoc.id())
                        .addValue("newId", newDoc.id()), Long.class);
        if (!paired.isEmpty()) {
            return getById(paired.get(0));
        }

        Map<String, String> oldChapters = aggregateChapters(oldDoc.id());
        Map<String, String> newChapters = aggregateChapters(newDoc.id());
        boolean locatable = isChapterLocatable(oldDoc.id()) && isChapterLocatable(newDoc.id());

        List<DocChangeItem> items = new ArrayList<>();
        for (ChapterDelta delta : docChunkDiff.diff(oldChapters, newChapters)) {
            List<Long> requirementIds = findAffectedRequirements(oldDoc.id(), delta.chapter());
            List<Long> caseIds = findAffectedCases(requirementIds);
            items.add(new DocChangeItem(blankToNull(delta.chapter()), delta.changeType(),
                    requirementIds, caseIds, delta.chapter() != null && !delta.chapter().isBlank()));
        }

        Long id = named.queryForObject("""
                INSERT INTO tp_doc_change_analysis (project_id, old_document_id, new_document_id,
                                                    changes, chapter_locatable, status, created_by)
                VALUES (:projectId, :oldId, :newId, :changes::jsonb, :locatable,
                        :status, :operatorId) RETURNING id
                """, new MapSqlParameterSource()
                .addValue("projectId", newDoc.projectId())
                .addValue("oldId", oldDoc.id())
                .addValue("newId", newDoc.id())
                .addValue("changes", writeJson(items))
                .addValue("locatable", locatable)
                .addValue("status", DocChangeAnalysisStatus.PENDING)
                .addValue("operatorId", operatorId), Long.class);
        DocChangeAnalysisInfo saved = getById(id);
        notifyReviewers(saved);
        return saved;
    }

    @Override
    public PageResult<DocChangeAnalysisInfo> list(long projectId, Integer status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size == 0 ? 20 : size));
        MapSqlParameterSource countParams = new MapSqlParameterSource("projectId", projectId);
        StringBuilder where = new StringBuilder(" WHERE project_id = :projectId");
        if (status != null) {
            where.append(" AND status = :status");
            countParams.addValue("status", status);
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_doc_change_analysis" + where,
                countParams, Long.class);
        List<DocChangeAnalysisInfo> list = named.query(SELECT_COLUMNS + where + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                countParams.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize),
                this::mapRow);
        return new PageResult<>(list, total == null ? 0L : total);
    }

    @Override
    @Transactional
    public DocChangeAnalysisInfo handle(long operatorId, long id, String note) {
        DocChangeAnalysisInfo current = getById(id);
        if (current.status() != DocChangeAnalysisStatus.PENDING) {
            throw new ServiceException("该变更分析已处置完毕，无需重复处置（处置人="
                    + current.handledBy() + "）；可在「变更分析」明细查看处置记录", BizErrorCode.VALIDATION_ERROR);
        }
        int flipped = named.update("UPDATE tp_doc_change_analysis SET status = :handled, handled_by = :operatorId,"
                        + " handled_at = now(), handle_note = :note WHERE id = :id AND status = :pending",
                new MapSqlParameterSource()
                        .addValue("handled", DocChangeAnalysisStatus.HANDLED)
                        .addValue("pending", DocChangeAnalysisStatus.PENDING)
                        .addValue("operatorId", operatorId)
                        .addValue("note", note == null || note.isBlank() ? null : note.trim())
                        .addValue("id", id));
        if (flipped != 1) {
            // Lost the compare-and-swap to a concurrent disposition: refuse instead
            // of running a second writeback pass over the same diff items.
            throw new ServiceException("该变更分析已被并发处置，本次处置未生效（处置记录已留痕），请刷新「变更分析」列表查看最新处置结果",
                    BizErrorCode.VALIDATION_ERROR);
        }
        writeBackRequirements(operatorId, current);
        // One verdict closes both accounts : the armed
        // ledger rows of this document save settle on the linkage ledger.
        linkageConsumption.settleImpactsForUpstream(
                ObjectChangedEventType.DOC.objectType(), current.newDocumentId());
        return getById(id);
    }

    /**
     * Disposition writeback (upgrade from tick-only acknowledgement): every
     * requirement referenced by the recorded diff items walks the manual
     * RequirementService.update chain with status 2 = changed, so the version
     * bump, the STATUS snapshot and the requirement.changed event all come from
     * the single writer - no second write path. Requirements soft-deleted after
     * the analysis was recorded are skipped (their impact fact stays on the row);
     * requirements already in the target state replay with zero writes.
     */
    private void writeBackRequirements(long operatorId, DocChangeAnalysisInfo analysis) {
        Set<Long> targets = new LinkedHashSet<>();
        for (DocChangeItem item : analysis.changes()) {
            targets.addAll(item.requirementIds());
        }
        if (targets.isEmpty()) {
            return;
        }
        List<Long> visible = named.queryForList(
                "SELECT id FROM tp_requirement WHERE id IN (:ids) AND deleted = 0 ORDER BY id",
                new MapSqlParameterSource("ids", targets), Long.class);
        for (Long requirementId : visible) {
            requirementService.update(operatorId, requirementId,
                    new RequirementUpdateInput(null, null, null, null, null,
                            REQUIREMENT_STATUS_CHANGED, null));
        }
    }

 
    private void notifyReviewers(DocChangeAnalysisInfo info) {
        afterCommitExecutor.execute(() -> {
            for (Long userId : projectOwners.resolveWithCreator(info.projectId())) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, info.projectId(),
                            EVENT_DOC_CHANGE_ANALYZED,
                            "文档重传产生变更，请复核受影响用例",
                            "文档新版本与上一版存在 " + info.changes().size() + " 处章节差异，关联需求点与用例可能需要复核更新。"
                                    + "请前往「AI 文档 → 变更分析」查看清单并处置。",
                            EVENT_DOC_CHANGE_ANALYZED + ":" + info.id() + ":" + userId));
                } catch (RuntimeException e) {
                    log.warn("DOC_CHANGE_ANALYZED 发送失败（analysisId={}, userId={}）: {}",
                            info.id(), userId, e.getMessage());
                }
            }
        });
    }

    @Override
    public DocChangeAnalysisInfo get(long id) {
        return getById(id);
    }

    /** New-version chapter body via the private aggregation single source (null key = whole-document block; absent = empty string). */
    @Override
    public String newChapterContent(long analysisId, String chapter) {
        DocChangeAnalysisInfo analysis = getById(analysisId);
        String body = aggregateChapters(analysis.newDocumentId())
                .get(chapter == null ? "" : chapter);
        return body == null ? "" : body;
    }

    private DocChangeAnalysisInfo getById(long id) {
        List<DocChangeAnalysisInfo> rows = named.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id), this::mapRow);
        if (rows.isEmpty()) {
            throw new ServiceException("变更分析记录不存在（id=" + id + "），请刷新「变更分析」列表确认记录后重新选择", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    private DocumentInfo findPreviousVersion(DocumentInfo current) {
        if (current.lineageGroupId() == null) {
            throw new ServiceException("该文档缺少血缘组信息（历史数据未回填），请在「变更分析」页人工选择对照版本后重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<DocumentInfo> rows = named.query("""
                SELECT id, project_id, name, file_type, file_hash, size_bytes, storage_key,
                       status, chunk_count, error_msg, doc_category,
                       use_for_extraction, use_in_case_prompt, use_in_grounding, revision,
                       lineage_group_id
                FROM tp_document
                WHERE lineage_group_id = :lineageGroupId
                  AND id < :currentId AND status = 1
                ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("lineageGroupId", current.lineageGroupId())
                .addValue("currentId", current.id()),
                (rs, rowNum) -> new DocumentInfo(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getInt(8), rs.getInt(9), rs.getString(10), rs.getString(11),
                        rs.getBoolean(12), rs.getBoolean(13), rs.getBoolean(14), rs.getInt(15),
                        rs.getObject(16, Long.class), null, null));
        if (rows.isEmpty()) {
            throw new ServiceException("该文档没有可对照的历史版本（重传分析需血缘组内存在已解析成功的旧版），请先在「文档列表」上传旧版本并完成解析，或改用首次全量提取",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    
    private Map<String, String> aggregateChapters(long documentId) {
        Map<String, StringBuilder> buffer = new LinkedHashMap<>();
        named.query("SELECT chapter, seq, content FROM tp_document_chunk"
                        + " WHERE document_id = :docId ORDER BY seq",
                new MapSqlParameterSource("docId", documentId),
                (RowCallbackHandler) rs -> {
                    String chapter = blankToEmpty(rs.getString("chapter"));
                    buffer.computeIfAbsent(chapter, k -> new StringBuilder())
                            .append(rs.getString("content")).append('\n');
                });
        Map<String, String> chapters = new LinkedHashMap<>();
        buffer.forEach((chapter, sb) -> chapters.put(chapter, sb.toString()));
        return chapters;
    }

    
    private boolean isChapterLocatable(long documentId) {
        Long blank = named.queryForObject("SELECT count(*) FROM tp_document_chunk"
                        + " WHERE document_id = :docId AND (chapter IS NULL OR trim(chapter) = '')",
                new MapSqlParameterSource("docId", documentId), Long.class);
        return blank == null || blank == 0L;
    }

    
    private List<Long> findAffectedRequirements(long oldDocumentId, String chapterKey) {
        MapSqlParameterSource params = new MapSqlParameterSource("sourceId",
                ExtractionSourceId.forDocument(oldDocumentId));
        String chapterCond;
        if (chapterKey == null || chapterKey.isBlank()) {
            chapterCond = "AND (chapter IS NULL OR trim(chapter) = '')";
        } else {
            chapterCond = "AND chapter = :chapter";
            params.addValue("chapter", chapterKey);
        }
        return named.queryForList("SELECT id FROM tp_requirement"
                + " WHERE deleted = 0 AND source = 2 AND source_id = :sourceId " + chapterCond + " ORDER BY id",
                params, Long.class);
    }

    
    private List<Long> findAffectedCases(List<Long> requirementIds) {
        if (requirementIds.isEmpty()) {
            return List.of();
        }
        return named.queryForList("SELECT DISTINCT cr.case_id FROM tp_case_requirement cr"
                        + " JOIN tp_test_case tc ON tc.id = cr.case_id AND tc.deleted = 0"
                        + " WHERE cr.requirement_id IN (:reqIds) ORDER BY 1",
                new MapSqlParameterSource("reqIds", requirementIds), Long.class);
    }

    private DocChangeAnalysisInfo mapRow(java.sql.ResultSet rs, int rowNum) throws SQLException {
        List<DocChangeItem> changes = readJson(rs.getString("changes_text"));
        return new DocChangeAnalysisInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getLong("old_document_id"), rs.getLong("new_document_id"), changes,
                rs.getBoolean("chapter_locatable"), rs.getInt("status"),
                DocChangeAnalysisStatus.label(rs.getInt("status")),
                readLong(rs, "created_by"), ViewFormats.formatTs(rs.getTimestamp("created_at")),
                readLong(rs, "handled_by"), ViewFormats.formatTs(rs.getTimestamp("handled_at")),
                rs.getString("handle_note"));
    }

    private String writeJson(List<DocChangeItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new ServiceException("变更明细保存失败（本次比对结果无法序列化）——"
                    + "请在「文档列表」对该文档重新点「变更分析」再跑一次比对；反复失败请联系管理员查看服务端日志",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private List<DocChangeItem> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<DocChangeItem>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("变更明细 JSON 反序列化失败（数据损坏？）", e);
        }
    }

    private static Long readLong(java.sql.ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
