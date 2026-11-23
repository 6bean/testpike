package com.sixbean.testpike.core.document.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.core.document.api.dto.ConfirmExtractionResult;
import com.sixbean.testpike.core.document.api.dto.ExtractionDisposition;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.ExtractionEventRow;
import com.sixbean.testpike.core.document.api.dto.ExtractionGapRow;
import com.sixbean.testpike.core.document.api.dto.ExtractionFidelityRow;
import com.sixbean.testpike.core.document.api.dto.ExtractionItem;
import com.sixbean.testpike.core.document.api.dto.ExtractionPreview;
import com.sixbean.testpike.core.document.api.dto.ExtractionStageRow;
import com.sixbean.testpike.core.document.domain.component.DocumentExtractState;
import com.sixbean.testpike.core.document.domain.component.ExtractionDispatch;
import com.sixbean.testpike.core.document.domain.component.ExtractionSourceId;
import com.sixbean.testpike.core.document.domain.service.DocumentExtractionService;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.document.domain.service.ExtractionUpdateProposalService;
import com.sixbean.testpike.core.events.domain.service.AiGenPublisher;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


@Service
public class DocumentExtractionServiceImpl implements DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionServiceImpl.class);

    
    private static final int STATUS_PARSE_OK = 1;
    
    private static final int STATUS_EXTRACTING = 3;
    
    private static final int STATUS_EXTRACTED = 4;

    private static final int MAX_PREVIEW_SIZE = 200;

    /** ③: reason stamped on the row when the user cancels explicitly. */
    static final String CANCEL_ERROR_TEXT = "已手动取消本轮提取任务，可随时重新发起提取";

    /** Governance key: dimension suggestions on the extraction preview; factory
     *  default on, hot-reloaded, see {@code dimensionSuggestEnabled}. */
    public static final String KEY_DIMENSION_SUGGEST = "ai.doc.dimension-suggest";

    /** Governance key : evidence anchor required at landing. The same
 * live key the extraction plane reads before writing the preview — the
 * confirm doors consume it here so an anchorless preview row can never
 * become a requirement row (100% non-empty chunkIds contract). */
    public static final String KEY_ANCHOR_REQUIRED = "ai.doc.anchor-required.enabled";

    /** Coverage-ledger step titles are written by the extraction door as a fixed
     * CJK block marker followed by the chunk row id; this parses the id back out. */
    private static final java.util.regex.Pattern GAP_STEP_TITLE =
            java.util.regex.Pattern.compile("^块#(\\d+)$");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RequirementService requirementService;
    private final AiGenPublisher aiGenPublisher;
    private final DocumentService documentService;
    private final com.sixbean.testpike.core.common.component.dedup.DedupGate dedupGate;
    private final ExtractionUpdateProposalService updateProposalService;
    private final com.sixbean.testpike.core.config.domain.service.ConfigService configService;
    private final com.sixbean.testpike.core.config.domain.component.SystemConfigReader systemConfigReader;
    private final com.sixbean.testpike.core.document.domain.component.DocDimensionSuggester dimensionSuggester;
    private final com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake outcomeIntake;
    private final com.sixbean.testpike.core.document.domain.component.DocumentExtractConsumeLockProbe lockProbe;
    /** Shared cursor-window door of the process event ledger ( S4 segment
 * B, R40 single source; the doc face only resolves the run token). */
    private final com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader
            taskEventWindowReader;

    public DocumentExtractionServiceImpl(JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper,
                                         RequirementService requirementService,
                                         AiGenPublisher aiGenPublisher,
                                         @Lazy DocumentService documentService,
                                         com.sixbean.testpike.core.common.component.dedup.DedupGate dedupGate,
                                         ExtractionUpdateProposalService updateProposalService,
                                         com.sixbean.testpike.core.config.domain.service.ConfigService configService,
                                         com.sixbean.testpike.core.config.domain.component.SystemConfigReader systemConfigReader,
                                         com.sixbean.testpike.core.document.domain.component.DocDimensionSuggester dimensionSuggester,
                                         com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake outcomeIntake,
                                         com.sixbean.testpike.core.document.domain.component.DocumentExtractConsumeLockProbe lockProbe,
                                         com.sixbean.testpike.core.events.domain.component.TaskEventWindowReader taskEventWindowReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.requirementService = requirementService;
        this.aiGenPublisher = aiGenPublisher;
        this.documentService = documentService;
        this.dedupGate = dedupGate;
        this.updateProposalService = updateProposalService;
        this.configService = configService;
        this.systemConfigReader = systemConfigReader;
        this.dimensionSuggester = dimensionSuggester;
        this.outcomeIntake = outcomeIntake;
        this.lockProbe = lockProbe;
        this.taskEventWindowReader = taskEventWindowReader;
    }


    @Override
    public DocumentInfo extract(long operatorId, long documentId, String modelHint) {
        return extract(operatorId, documentId, modelHint, null, false);
    }

    @Override
    public DocumentInfo extract(long operatorId, long documentId, String modelHint,
                                String dimensionHint) {
        return extract(operatorId, documentId, modelHint, dimensionHint, false);
    }

    @Override
    public DocumentInfo extract(long operatorId, long documentId, String modelHint,
                                String dimensionHint, boolean preempt) {
        return extract(operatorId, documentId, modelHint, dimensionHint, preempt, null);
    }

    @Override
    public DocumentInfo extract(long operatorId, long documentId, String modelHint,
                                String dimensionHint, boolean preempt,
                                java.util.List<Long> chunkIds) {
        // the hint arrives from the preview dimension chip and
        // must be one of the suggester's closed vocabulary names — a crafted
        // body value may never smuggle arbitrary text into the LLM prompt.
        // Rejected before markExtracting so the row never drifts into status 3.
        String hint = dimensionHint == null || dimensionHint.isBlank()
                ? null : dimensionHint.trim();
        if (hint != null && !dimensionSuggester.isKnown(hint)) {
            throw new ServiceException("补充提取维度不合法（仅支持："
                            + String.join("、", dimensionSuggester.knownDimensions()) + "）——"
                            + "请在「提取预览」的维度建议中点选其一后重新发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // segment B: the directed-supplement whitelist is closed here —
        // every id must be a chunk row OF this document, and the check runs
        // before markExtracting so a rejected click leaves zero status drift
        // (the preview keeps showing the previous run, not a stuck 3).
        java.util.List<Long> whitelist = chunkIds == null || chunkIds.isEmpty()
                ? null : java.util.List.copyOf(chunkIds);
        if (whitelist != null) {
            assertChunksBelongToDocument(documentId, whitelist);
        }
        ExtractionDispatch dispatch = markExtracting(documentId, preempt);
        try {
            aiGenPublisher.publishExtract(new AiDocExtractMessage(
                    documentId, dispatch.projectId(), operatorId, modelHint,
                    dispatch.extractToken(), hint, whitelist));
        } catch (RuntimeException e) {
            log.warn("F2 提取任务派发失败，回退状态待重试: docId={} err={}",
                    documentId, e.toString());
            jdbcTemplate.update("UPDATE tp_document SET status=?, updated_at=now() "
                    + "WHERE id=? AND status=?", STATUS_PARSE_OK, documentId, STATUS_EXTRACTING);
            throw new ServiceException("提取任务提交失败（消息服务异常），请稍后在「提取预览」重新发起提取",
                    BizErrorCode.STATE_MACHINE_REJECT, e);
        }
        return documentService.get(documentId);
    }

    /**
 * Chunk-ownership door of the directed supplement : the
 * message alone (error-guidance posture, R63).
 */
    private void assertChunksBelongToDocument(long documentId, java.util.List<Long> chunkIds) {
        java.util.Set<Long> owned = new java.util.HashSet<>(jdbcTemplate.queryForList(
                "SELECT id FROM tp_document_chunk WHERE document_id = ?", Long.class, documentId));
        java.util.List<Long> foreign = chunkIds.stream()
                .filter(id -> id == null || !owned.contains(id))
                .toList();
        if (!foreign.isEmpty()) {
            throw new ServiceException("分块 " + foreign + " 不属于该文档（可能已被重新解析），"
                            + "无法定向补提；请在「提取预览」重新打开最新分块后再发起补提",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private ExtractionDispatch markExtracting(long documentId, boolean preempt) {
        int status = lockStatus(documentId);
        if (status == STATUS_EXTRACTING) {
            // ②: the heartbeat probe separates "previous generation still
            // running" from "dead dispatch residue". Live + no preempt flag = a
            // human rejection naming the preemption consequence; with the flag =
            // admit and write the fresh token (the old run self-aborts at its
            // ai-service generation seams, and its writebacks stay 409-guarded
            // as double insurance).
            // ①: no live heartbeat = the previous consumer is gone
            // (crashed/restarted before stamping, or its lease expired) — the
            // click is admitted ON THE SPOT with a fresh token instead of
            // making the user wait for the stall sweeper. The old generation,
            // if anything is still in flight, self-aborts at the seams,
            // so admission here never burns two generations.
            if (lockProbe.liveGenerationRunning(documentId) && !preempt) {
                throw new ServiceException("上一轮需求点提取仍在执行，本次继续将抢占并自动中止上一轮；"
                                + "确认抢占请携带抢占标志重新提交，否则请等待本轮完成",
                        BizErrorCode.STATE_MACHINE_REJECT);
            }
        }
        // /: status 3 reaches the UPDATE only as a preempt of a live
        // generation or as fast admission of dead residue — every other
        // non-parsed state keeps the honest rejection below.
        if (status != STATUS_PARSE_OK && status != STATUS_EXTRACTED
                && status != STATUS_EXTRACTING) {
            throw new ServiceException("文档尚未解析成功，无法提取需求点（请先完成解析）",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        // Usage-switch routing: a document switched off for requirement
        // extraction is rejected here — before the status UPDATE, so the row
        // never drifts into the extracting state.
        java.util.Map<String, Object> routing = jdbcTemplate.queryForMap(
                "SELECT project_id, use_for_extraction FROM tp_document WHERE id=?", documentId);
        if (Boolean.FALSE.equals(routing.get("use_for_extraction"))) {
            throw new ServiceException("该文档的「参与需求提取」开关已关闭，无法发起需求点提取；"
                    + "请在文档列表的用途开关中打开后重试",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        Long projectId = routing.get("project_id") instanceof Number number
                ? number.longValue() : null;
        String extractToken = UUID.randomUUID().toString();
        // /: status IN adds 3 — a preempted live generation and a
        // fast-admitted dead residue both re-tokenize here. The row is
        // FOR UPDATE-locked, so no concurrent state drift.
        int updated = jdbcTemplate.update("UPDATE tp_document SET status=?, extract_error=NULL, "
                        + "extract_token=?, updated_at=now() WHERE id=? AND status IN (?,?,?)",
                STATUS_EXTRACTING, extractToken, documentId,
                STATUS_PARSE_OK, STATUS_EXTRACTED, STATUS_EXTRACTING);
        if (updated == 0) {
            throw new ServiceException("该文档的需求点提取正在进行中，请在「文档列表」查看该行状态并在完成后重新发起；"
                    + "若完成后横幅提示有分块未产生需求点，点分块号用「补提此块」定向补提",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        return new ExtractionDispatch(projectId, extractToken);
    }

    /**
 * ④: current extraction-generation token of the document (consumed by
 * the ai-service generation self-check endpoint). Single SQL path = {@code
 * loadState} — no second query; a missing document throws the loadState human
 * message.
 */
    @Override
    public String currentExtractToken(long documentId) {
        return loadState(documentId).extractToken();
    }

    /**
 * ③: explicit cancel. Rotates the generation token FIRST-class in
 * the same conditional UPDATE that settles the row (status=3 re-asserted
 * in WHERE — a writeback landing between the FOR UPDATE read and this
 * statement means the run already settled, so cancel honestly rejects
 * instead of clobbering). After rotation the in-flight ai-service run
 * self-aborts at its generation seams and any late result/failed push is
 * 409-guarded, which is exactly what {@code cancelledGenerationWriteback-
 * IsRejectedByGuard} pins.
 */
    @Override
    public DocumentInfo cancelExtraction(long operatorId, long documentId) {
        int status = lockStatus(documentId);
        if (status != STATUS_EXTRACTING) {
            throw new ServiceException("该文档当前没有进行中的提取任务，无需取消提取",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tp_document SET status=?, extract_error=?, extract_token=?, "
                        + "updated_at=now() WHERE id=? AND status=?",
                STATUS_PARSE_OK, CANCEL_ERROR_TEXT, UUID.randomUUID().toString(),
                documentId, STATUS_EXTRACTING);
        if (updated == 0) {
            throw new ServiceException("该提取任务刚刚已完成或状态已变化，本次取消未生效，请刷新查看结果",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        log.info("F2 提取任务被用户显式取消: docId={} operatorId={}", documentId, operatorId);
        return documentService.get(documentId);
    }


    @Override
    public ExtractionPreview extraction(long documentId, int page, int size) {
        DocumentExtractState state = loadState(documentId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PREVIEW_SIZE);
        List<ExtractionItem> items = readExtractionJson(state.extractionJson());
        int from = Math.min((safePage - 1) * safeSize, items.size());
        int to = Math.min(from + safeSize, items.size());
        // segment A: the ledger's own per-chunk reasons travel with
        // the ids — one parse, ids derived from the rows (no second list).
        List<ExtractionGapRow> gapRows = readPendingGapRows(documentId, state.extractToken());
        List<Long> gapIds = gapRows.stream().map(ExtractionGapRow::chunkId).sorted().toList();
        // stage face of the live run (empty + null activity
        // when nothing has landed — legacy consumers see the old shape).
        List<ExtractionStageRow> stages = readStageLedger(documentId, state.extractToken());
        // fidelity ledger echo of the CURRENT generation
        // (null before the run lands its row — never a fabricated number).
        ExtractionFidelityRow fidelity = readFidelityLedger(documentId, state.extractToken());
        return new ExtractionPreview(documentId, state.status(), state.extractError(),
                safePage, safeSize, items.size(), items.subList(from, to),
                reviewFlagThreshold(), gapRows.size(), gapIds, dimensionSuggestions(items),
                stages, lastActivityAt(stages, documentId, state.extractToken()),
                fidelity, gapRows);
    }

    /**
 * Fidelity ledger row of the current generation . The row
 * is written by ai-service through its own door at run completion; this
 * face is a read-only echo keyed by the live generation token (a rotated
 * run can never replay the previous generation's account) — an absent row
 * honestly answers null and an unreachable door never fails the preview
 * call, same fail-soft posture as the stage/coverage echoes.
 */
    private ExtractionFidelityRow readFidelityLedger(long documentId, String extractToken) {
        if (!hasText(extractToken)) {
            return null;
        }
        try {
            List<ExtractionFidelityRow> rows = jdbcTemplate.query(
                    "SELECT chunk_count, node_count, injected_tokens, source_tokens,"
                            + " retention_percent, distinct_chapters, item_count,"
                            + " anchored_item_count, anchor_rate_percent, estimate_tokens,"
                            + " usage_prompt_tokens, est_drift_percent,"
                            + " plain_json_tokens_per_item, billed_tokens_per_item,"
                            + " summary_calls, reconcile_calls"
                            + " FROM tp_doc_extract_fidelity"
                            + " WHERE ref_id=? AND run_id=?",
                    (rs, i) -> new ExtractionFidelityRow(
                            rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            rs.getDouble(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                            rs.getDouble(9), rs.getInt(10), rs.getInt(11), rs.getDouble(12),
                            rs.getInt(13), rs.getInt(14), rs.getInt(15), rs.getInt(16)),
                    documentId, extractToken);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException doorDown) {
            log.warn("提取忠实账读取不可达，预览按无账继续: docId={} reason={}",
                    documentId, doorDown.getMessage());
            return null;
        }
    }

    /**
 * Stage ledger rows of the current generation in landing order (
 * . Read-only echo through the same door posture as the coverage
 * ledger: an unreachable door honestly answers "no stages", never fails
 * the preview call.
 */
    private List<ExtractionStageRow> readStageLedger(long documentId, String extractToken) {
        if (!hasText(extractToken)) {
            return List.of();
        }
        try {
            return jdbcTemplate.query("SELECT stage_code, stage_label,"
                            + " to_char(updated_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS landed_at"
                            + " FROM tp_doc_extract_stage"
                            + " WHERE document_id=? AND extract_token=? ORDER BY id",
                    (rs, i) -> new ExtractionStageRow(rs.getString(1), rs.getString(2),
                            rs.getString(3)),
                    documentId, extractToken);
        } catch (RuntimeException doorDown) {
            log.warn("提取阶段台账读取不可达，预览按无阶段继续: docId={} reason={}",
                    documentId, doorDown.getMessage());
            return List.of();
        }
    }

    /**
 * Process event tail window of the current generation .
 * The ledger itself is written by ai-service through its own door; this
 * face is a read-only echo keyed by the live generation token (a rotated
 * run can never replay the previous generation's steps). Since S4
 * segment B the cursor window is served by the shared window door — the
 * script-generation tail reads the same ledger through the same contract
 * (R40 single source), so the SQL and the window clamp live there once.
 */
    @Override
    public List<ExtractionEventRow> extractionEvents(long documentId, long cursor, Integer limit) {
        return extractionEvents(documentId, cursor, limit, null);
    }

    @Override
    public List<ExtractionEventRow> extractionEvents(long documentId, long cursor,
                                                     Integer limit, String runToken) {
        // loadState keeps the unknown/deleted-document refusal on every
        // posture. An explicit run token replays that historical run; an
        // absent one resolves to the current generation exactly as before.
        String liveToken = loadState(documentId).extractToken();
        String extractToken = hasText(runToken) ? runToken : liveToken;
        if (!hasText(extractToken)) {
            return List.of();
        }
        return taskEventWindowReader.since(EVENT_JOB_KIND_DOC_EXTRACT,
                        String.valueOf(documentId), extractToken, cursor, limit)
                .stream()
                .map(row -> new ExtractionEventRow(row.cursor(), row.level(),
                        row.summary(), row.detail(), row.landedAt()))
                .toList();
    }

    @Override
    public List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow> extractionRuns(
            long documentId, Integer limit) {
        // Unknown or deleted documents refuse before the event ledger is
        // read (same door posture as the batch run-history reference).
        loadState(documentId);
        return taskEventWindowReader.runs(EVENT_JOB_KIND_DOC_EXTRACT,
                String.valueOf(documentId), limit);
    }

    /** Kind token of the doc-extract pipeline inside tp_ai_task_event
     *  (mirror of the ai-service writer door). */
    private static final String EVENT_JOB_KIND_DOC_EXTRACT = "doc-extract";

    /** Kind token of the channel-B spec extraction inside tp_ai_task_event
 * . */
    private static final String EVENT_JOB_KIND_SPEC_EXTRACT = "spec-extract";

    /**
 * segment C1: tail window of the newest channel-B spec-extraction
 * attempt for a document. The run token lives only inside the ledger
 * (channel B keeps no task-state row), so the shared door resolves the
 * latest run and slices its window; every other face (row shape, clamp,
 * fail-soft) rides the same single source as {@link #extractionEvents}.
 */
    @Override
    public List<ExtractionEventRow> specExtractionEvents(long documentId, long cursor,
                                                         Integer limit) {
        return specExtractionEvents(documentId, cursor, limit, null);
    }

    @Override
    public List<ExtractionEventRow> specExtractionEvents(long documentId, long cursor,
                                                         Integer limit, String runToken) {
        // Channel B keeps no task-state row, so there is nothing to refuse
        // on here beyond the ledger's own fail-soft contract: an explicit
        // token replays that historical attempt, an absent one resolves the
        // newest attempt from the ledger exactly as before (zero drift).
        var window = hasText(runToken)
                ? taskEventWindowReader.since(EVENT_JOB_KIND_SPEC_EXTRACT,
                        String.valueOf(documentId), runToken, cursor, limit)
                : taskEventWindowReader.sinceLatestRun(EVENT_JOB_KIND_SPEC_EXTRACT,
                        String.valueOf(documentId), cursor, limit);
        return window.stream()
                .map(row -> new ExtractionEventRow(row.cursor(), row.level(),
                        row.summary(), row.detail(), row.landedAt()))
                .toList();
    }

    @Override
    public List<com.sixbean.testpike.core.events.api.dto.TaskEventRunRow> specExtractionRuns(
            long documentId, Integer limit) {
        // Shared run-history reader (R40 single source): same aggregation,
        // clamp and fail-soft posture as every other revisit door.
        return taskEventWindowReader.runs(EVENT_JOB_KIND_SPEC_EXTRACT,
                String.valueOf(documentId), limit);
    }

    /**
     * Last time anything in this generation's bookkeeping moved: the newest
     * stage landing or a coverage-ledger revision (ISO local strings compare
     * lexicographically); null when neither face has a row — the preview says
     * "no activity recorded" instead of inventing a clock.
     */
    private String lastActivityAt(List<ExtractionStageRow> stages, long documentId,
                                  String extractToken) {
        String latest = null;
        for (ExtractionStageRow stage : stages) {
            if (stage.at() != null && (latest == null || stage.at().compareTo(latest) > 0)) {
                latest = stage.at();
            }
        }
        if (!hasText(extractToken)) {
            return latest;
        }
        try {
            List<String> coverage = jdbcTemplate.queryForList(
                    "SELECT to_char(updated_at, 'YYYY-MM-DD\"T\"HH24:MI:SS') FROM tp_doc_extract_ledger"
                            + " WHERE document_id=? AND extract_token=?",
                    String.class, documentId, extractToken);
            for (String at : coverage) {
                if (at != null && (latest == null || at.compareTo(latest) > 0)) {
                    latest = at;
                }
            }
        } catch (RuntimeException doorDown) {
            log.warn("覆盖账本活动时间读取不可达，按阶段台账回答: docId={} reason={}",
                    documentId, doorDown.getMessage());
        }
        return latest;
    }

    /**
     * Dimension suggestions (missing review angles of the extracted list). The
     * switch ai.doc.dimension-suggest ships on (an unreadable or missing value
     * keeps suggestions on — the honest factory posture); only an explicit
     * false/0 closes it. The calculation runs over ALL items, never just the
     * current page, so paging never reshuffles the advice. Suggestions are
     * advice only: the confirm/review human gates are untouched by this field.
     */
    private List<String> dimensionSuggestions(List<ExtractionItem> items) {
        if (!dimensionSuggestEnabled()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>(items.size());
        for (ExtractionItem item : items) {
            texts.add(item.title() + " " + (item.acceptanceCriteria() == null
                    ? "" : item.acceptanceCriteria()));
        }
        return dimensionSuggester.suggest(texts);
    }

    /** Factory-on boolean read: anything but an explicit false/0 stays on. */
    private boolean dimensionSuggestEnabled() {
        String raw = configService.getValue(KEY_DIMENSION_SUGGEST, "true");
        String v = raw == null ? "true" : raw.trim().toLowerCase();
        return !("false".equals(v) || "0".equals(v));
    }

    /**
 * Review-flag threshold echo : the single value source is the
 * hot-updatable config key; a malformed live value degrades to the shipped
 * default instead of poisoning the chip logic in every client.
 */
    private double reviewFlagThreshold() {
        String raw = configService.getValue("ai.doc.review-flag-threshold", "0.7");
        try {
            double parsed = Double.parseDouble(raw == null ? "" : raw.trim());
            return parsed > 0 && parsed <= 1 ? parsed : 0.7;
        } catch (NumberFormatException bad) {
            log.warn("核对阈值配置不可解析，按出厂缺省 0.7 回显: raw={}", raw);
            return 0.7;
        }
    }

    /**
 * Pending coverage gaps of the current generation with their own reasons
 * . The step list lives in
 * tp_doc_extract_ledger, written by ai-service through its own door; the
 * preview side stays a read-only echo — an absent ledger (summary-only
 * document or generation) honestly answers "no gaps", broken
 * JSON never fails the preview call (mirrors the store's fail-soft
 * posture), and a chunk row deleted after the run keeps the ledger note
 * while answering null seq/chapter and 0 contentChars — never a
 * fabricated label.
 */
    private List<ExtractionGapRow> readPendingGapRows(long documentId, String extractToken) {
        if (!hasText(extractToken)) {
            return List.of();
        }
        List<String> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    "SELECT steps_json FROM tp_doc_extract_ledger"
                            + " WHERE document_id=? AND extract_token=?",
                    String.class, documentId, extractToken);
        } catch (RuntimeException doorDown) {
            log.warn("提取覆盖账本读取不可达，预览按无缺口继续: docId={} reason={}",
                    documentId, doorDown.getMessage());
            return List.of();
        }
        if (rows.isEmpty() || !hasText(rows.get(0))) {
            return List.of();
        }
        Map<Long, String[]> pending = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> steps = objectMapper.readValue(
                    rows.get(0), new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> step : steps) {
                if (!"pending".equals(String.valueOf(step.get("state")))) {
                    continue; // done/skipped/blocked are not un-covered ground
                }
                java.util.regex.Matcher anchor = GAP_STEP_TITLE
                        .matcher(String.valueOf(step.getOrDefault("title", "")));
                if (anchor.matches()) {
                    long chunkId = Long.parseLong(anchor.group(1));
                    pending.putIfAbsent(chunkId, new String[] {
                            step.get("note") == null ? "" : String.valueOf(step.get("note")),
                            step.get("evidence") == null ? "" : String.valueOf(step.get("evidence")),
                    }); // same chunk referenced twice: the first row owns the reason
                }
            }
        } catch (Exception corrupt) {
            log.warn("提取覆盖账本不可解析，预览按无缺口继续: docId={} reason={}",
                    documentId, corrupt.toString());
            return List.of();
        }
        List<Long> ids = new ArrayList<>(pending.keySet());
        ids.sort(Long::compareTo);
        if (ids.isEmpty()) {
            return List.of();
        }
        // Chunk metadata comes from tp_document_chunk in one batched read;
        // the door being down never fails the preview (reasons stay, meta
        // honestly null/0 — the same posture as the ledger echo above).
        Map<Long, Object[]> chunkMeta = new java.util.HashMap<>();
        try {
            String marks = ids.stream().map(x -> "?")
                    .collect(java.util.stream.Collectors.joining(","));
            List<Object> args = new ArrayList<>();
            args.add(documentId);
            args.addAll(ids);
            for (Map<String, Object> r : jdbcTemplate.queryForList(
                    "SELECT id, seq, chapter, COALESCE(LENGTH(content),0) AS content_chars"
                            + " FROM tp_document_chunk WHERE document_id=? AND id IN (" + marks + ")",
                    args.toArray())) {
                chunkMeta.put(((Number) r.get("id")).longValue(), new Object[] {
                        r.get("seq"), r.get("chapter"),
                        ((Number) r.get("content_chars")).intValue(),
                });
            }
        } catch (RuntimeException chunkDoor) {
            log.warn("缺口块元数据不可达，原因行按无元数据继续: docId={} reason={}",
                    documentId, chunkDoor.getMessage());
        }
        List<ExtractionGapRow> out = new ArrayList<>();
        for (Long id : ids) {
            String[] reason = pending.get(id);
            Object[] meta = chunkMeta.get(id);
            out.add(new ExtractionGapRow(id,
                    meta == null ? null : (Integer) meta[0],
                    meta == null ? null : (String) meta[1],
                    reason[0], reason[1],
                    meta == null ? 0 : (int) meta[2]));
        }
        return out;
    }


    @Override
    @Transactional
    public ConfirmExtractionResult confirmExtraction(long operatorId, long documentId,
                                                     List<ExtractionItem> selectedItems) {
        return confirmExtraction(operatorId, documentId, selectedItems, null);
    }

    /**
 * Full-store door : enumerate the whole extraction preview
 * server-side and hand it to the very same tri-state confirm transaction,
 * so a paged viewer can never cap the landing. No second copy of the
 * gate logic exists here — dedup cards, idempotent skips and the anchor
 * write-through all come from the delegated door. Repeated replays land
 * nothing new (every item carries its requirement id after the first run).
 */
    @Override
    @Transactional
    public ConfirmExtractionResult confirmAllExtraction(long operatorId, long documentId) {
        DocumentExtractState state = loadState(documentId);
        if (state.status() != STATUS_EXTRACTED) {
            throw new ServiceException("文档尚未完成提取，暂无可入库的需求点",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        return confirmExtraction(operatorId, documentId,
                readExtractionJson(state.extractionJson()), null);
    }

    @Override
    @Transactional
    public ConfirmExtractionResult confirmExtraction(long operatorId, long documentId,
                                                     List<ExtractionItem> selectedItems,
                                                     Map<Long, String> dispositions) {
        lockStatus(documentId);
        DocumentExtractState state = loadState(documentId);
        if (state.status() != STATUS_EXTRACTED) {
            throw new ServiceException("文档尚未完成提取，暂无可确认的需求点预览",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        List<ExtractionItem> preview = readExtractionJson(state.extractionJson());
        Map<Long, ExtractionItem> byId = new LinkedHashMap<>();
        preview.forEach(i -> byId.put(i.id(), i));

        int created = 0;
        int skipped = 0;
        int drafted = 0;
        int heldAnchorless = 0;
        // one evidence-anchor gate behind both confirm doors.
        // With ai.doc.anchor-required.enabled on (factory posture; only an
        // explicit false/0 rolls back to the legacy face) a preview item whose
        // evidence never resolved to a source chunk may not become a
        // requirement row — the "landed rows keep non-empty chunkIds" contract
        // holds even for previews written before the extraction-side hold-back
        // existed (legacy generations, summary-round rows, direct write-backs).
        boolean anchorGateOn = systemConfigReader.getBoolean(KEY_ANCHOR_REQUIRED, true);
        // Landed ids travel back to the caller : the frontend
        // pre-fills "generate cases for these points" from this structured
        // list instead of re-deriving ids from the paged preview.
        List<Long> createdRequirementIds = new java.util.ArrayList<>();
        for (ExtractionItem req : selectedItems == null ? List.<ExtractionItem>of() : selectedItems) {
            ExtractionItem item = req == null ? null : byId.get(req.id());
            if (item == null || item.requirementId() != null || !hasText(item.title())) {
                skipped++; // preview has no such id / already landed (idempotent) / titleless
                continue;
            }
            if (anchorGateOn && item.chunkId() == null) {
                skipped++;
                heldAnchorless++;
                continue; // evidence never resolved to a source chunk: no landing
            }
            ExtractionDisposition disposition = ExtractionDisposition.parse(
                    dispositions == null ? null : dispositions.get(item.id()));
            if (disposition == ExtractionDisposition.KEEP_AS_IS) {
                skipped++; // human closed the item: no card, no write, no anchor change
                continue;
            }
            // Same single-source gate as the manual door (design A7): when the
            // project already carries this homonym the confirm never creates a
            // second row behind the reviewer's back - unless the reviewer
            // vouched FORCE_NEW, which lands through the same service chain
            // with an override audit naming the blocked match. Without that
            // vouch the item re-emerges as an anchored update-proposal card
            // behind the human one-click door (auto-apply is never wired here).
            var verdict = dedupGate.evaluateRequirement(state.projectId(), item.title());
            if (verdict.blocksCreate() && disposition != ExtractionDisposition.FORCE_NEW) {
                var outcome = updateProposalService.propose(operatorId, state.projectId(),
                        documentId, item, verdict.matched());
                if (outcome == ExtractionUpdateProposalService.Outcome.MINTED) {
                    drafted++;
                } else {
                    skipped++; // card already in place / target gone: never resend, never land
                }
                continue;
            }
            var info = requirementService.create(operatorId, new RequirementCreateInput(
                    state.projectId(), 2, ExtractionSourceId.forDocument(documentId),
                    item.title(), item.acceptanceCriteria(), com.sixbean.testpike.common.util.ChapterEchoStrip.strip(item.chapter()),
                    disposition == ExtractionDisposition.FORCE_NEW,
                    // Evidence anchor travels with the adoption : the verbatim
                    // excerpt and its chunk id land on the requirement row itself so
                    // the generation chain can pull the original text block later.
                    // Items without any anchor info stay exactly as before (null).
                    com.sixbean.testpike.core.requirement.api.dto.RequirementSourceAnchor
                            .ofNullable(documentId, item.chunkId(), item.evidence())));
            byId.put(item.id(), new ExtractionItem(item.id(), item.title(),
                    item.acceptanceCriteria(), com.sixbean.testpike.common.util.ChapterEchoStrip.strip(item.chapter()), item.confidence(),
                    true, info.id(), item.evidence(), item.chunkId()));
            created++;
            createdRequirementIds.add(info.id());
            // A checked-and-landed point is a human adoption of the extraction
            // output (behavior outcome wiring): single-source intake, WARN-only
            // — ledger trouble never rolls back the confirm transaction.
            outcomeIntake.recordReviewVerdict(
                    com.sixbean.testpike.core.behavior.domain.component.OutcomeChannel.CHIP,
                    info.id(), "adopted", String.valueOf(info.id()), state.projectId());
        }
        if (created > 0) {
            jdbcTemplate.update("UPDATE tp_document SET extraction_json=?::jsonb, "
                            + "updated_at=now() WHERE id=?",
                    toJson(new ArrayList<>(byId.values())), documentId);
        }
        if (heldAnchorless > 0) {
            log.warn("F2 确认入库拦下证据锚缺失条目 documentId={} 条数={}；"
                            + "开关 ai.doc.anchor-required.enabled 可在系统设置调整（保存即生效，无需重启）",
                    documentId, heldAnchorless);
        }
        return new ConfirmExtractionResult(created, skipped, drafted, createdRequirementIds);
    }


    @Override
    @Transactional
    public void writeExtractionResult(long documentId, List<ExtractionItem> items,
                                      String extractToken) {
        lockStatus(documentId);
        DocumentExtractState state = loadState(documentId);
        List<ExtractionItem> merged = mergeByIdempotentTitle(
                readExtractionJson(state.extractionJson()), items);
        int updated = jdbcTemplate.update("UPDATE tp_document SET status=?, extraction_json=?::jsonb, "
                        + "extract_error=NULL, updated_at=now() "
                        + "WHERE id=? AND status IN (?,?) AND extract_token=?",
                STATUS_EXTRACTED, toJson(merged), documentId,
                STATUS_PARSE_OK, STATUS_EXTRACTING, extractToken);
        if (updated == 0) {
            throw new ServiceException(rejectionMessage(state, extractToken, "回写"),
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        aiGenPublisher.publishKbIngest(new AiKbIngestMessage(
                documentId, state.projectId(), state.createdBy(), System.currentTimeMillis(),
                state.category()));
    }

    @Override
    @Transactional
    public void markExtractionFailed(long documentId, String errorMsg, String extractToken) {
        lockStatus(documentId);
        DocumentExtractState state = loadState(documentId);
        String human = hasText(errorMsg) ? errorMsg : "需求点提取失败，请重试";
        if (human.length() > 480) {
            human = human.substring(0, 480);
        }
        int updated = jdbcTemplate.update("UPDATE tp_document SET status=?, extract_error=?, "
                        + "updated_at=now() WHERE id=? AND status IN (?,?) AND extract_token=?",
                STATUS_PARSE_OK, human, documentId,
                STATUS_PARSE_OK, STATUS_EXTRACTING, extractToken);
        if (updated == 0) {
            throw new ServiceException(rejectionMessage(state, extractToken, "失败回写"),
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    
    private static String rejectionMessage(DocumentExtractState state,
                                           String token, String actionWord) {
        if (state.status() == STATUS_EXTRACTING
                && !Objects.equals(state.extractToken(), token)) {
            return "该文档已有新的提取任务在进行中，本次旧任务" + actionWord + "已忽略";
        }
        return "文档当前不处于提取中状态，" + actionWord + "已忽略";
    }

    
    private static List<ExtractionItem> mergeByIdempotentTitle(List<ExtractionItem> oldItems,
                                                               List<ExtractionItem> newItems) {
        // selection semantics (mode A) live in the common
        // merge single source shared with the ai-service grouping plane —
        // this consumer only adopts confirmed rows as selected and re-indexes
        // the preview ids 1..N, both unchanged from the legacy behaviour.
        List<ExtractionItem> chosen = com.sixbean.testpike.common.util.TitleIdempotentMerge
                .choosePreservingConfirmed(oldItems, newItems,
                        ExtractionItem::title,
                        item -> item.requirementId() != null,
                        item -> new ExtractionItem(item.id(), item.title(),
                                item.acceptanceCriteria(), com.sixbean.testpike.common.util.ChapterEchoStrip.strip(item.chapter()),
                                item.confidence(), true, item.requirementId(),
                                item.evidence(), item.chunkId()));
        List<ExtractionItem> result = new ArrayList<>(chosen.size());
        long nextId = 1;
        for (ExtractionItem kept : chosen) {
            result.add(new ExtractionItem(nextId++, kept.title(), kept.acceptanceCriteria(),
                    com.sixbean.testpike.common.util.ChapterEchoStrip.strip(kept.chapter()),
                    kept.confidence(), kept.selected(),
                    kept.requirementId(), kept.evidence(), kept.chunkId()));
        }
        return result;
    }


    
    private int lockStatus(long documentId) {
        try {
            Integer status = jdbcTemplate.queryForObject(
                    "SELECT status FROM tp_document WHERE id=? FOR UPDATE",
                    Integer.class, documentId);
            if (status == null) {
                throw new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);
            }
            return status;
        } catch (EmptyResultDataAccessException e) {
            throw new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);
        }
    }

    private DocumentExtractState loadState(long documentId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT project_id, status, extract_error, extraction_json::text, extract_token, created_by, doc_category "
                            + "FROM tp_document WHERE id=?",
                    (rs, n) -> new DocumentExtractState(rs.getLong(1), rs.getInt(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), (Long) rs.getObject(6), rs.getString(7)),
                    documentId);
        } catch (EmptyResultDataAccessException e) {
            throw new ServiceException("文档不存在（或已删除）", BizErrorCode.NOT_FOUND);
        }
    }

    private List<ExtractionItem> readExtractionJson(String json) {
        if (!hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ExtractionItem>>() {
            });
        } catch (Exception e) {
            log.warn("F2 extraction_json 反序列化失败按空预览处理: {}", e.toString());
            return new ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("提取预览序列化失败", BizErrorCode.VALIDATION_ERROR, e);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
