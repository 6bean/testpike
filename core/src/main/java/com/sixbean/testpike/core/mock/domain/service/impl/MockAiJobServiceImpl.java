package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobCreateInput;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobDraftItemView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobEstimateView;
import com.sixbean.testpike.core.mock.api.dto.MockAiJobInfo;
import com.sixbean.testpike.core.mock.api.dto.MockDraftArtifactGrantView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.service.MockAiJobService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockAiJobDO;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI mock-drafting job control plane .
 * <p>Gate order is the abuse-resistance contract under test: prompt shape and
 * job-type domain first (plain-word validation), then space existence (the
 * space service's own wording), then the master switch ANDed with the point's
 * sub-switch. A closed gate refuses FORBIDDEN naming the exact key the
 * operator must open, persists NO job row, and leaves one audit line — silent
 * no-ops are the failure mode this class exists to prevent. The M10 types
 * (llm-fallback / record-summarize, unlocked by V241) ride the same
 * switch-gated path: closed gates refuse in plain words, accepted jobs queue
 * with a pinned budget exactly like the M9 points.
 * <p>Accepted jobs pin the cost account at creation (budget_snapshot JSONB —
 * explore V57 precedent: readers never recompute from live keys) and land
 * 'queued'. MQ publication is the controller publish point's job ,
 * LLM execution is ai-service's — this plane only gates, pins, and ledger.
 * <p>confirm is the single ingress of AI drafts into the rule source: preview
 * status only, every ticked stub re-validated through MockStubService's save
 * chain with provenance forced to source=nl-ai + sourceRef=job:{id} (caller
 * input cannot forge provenance), then the job seals 'consumed' once.
 */
@Service
public class MockAiJobServiceImpl implements MockAiJobService {

    /** K8 hot key: master gate for every mock AI application point. */
    static final String MASTER_KEY = "ai.mock.enabled";

    static final String TYPE_NL_STUB = "nl-stub";
    static final String TYPE_EDGE_SCENARIOS = "edge-scenarios";
    static final String TYPE_SPEC_SYNC = "spec-sync";
    static final String TYPE_LLM_FALLBACK = "llm-fallback";
    static final String TYPE_RECORD_SUMMARIZE = "record-summarize";

    private static final Set<String> SUPPORTED_TYPES =
            Set.of(TYPE_NL_STUB, TYPE_EDGE_SCENARIOS, TYPE_SPEC_SYNC,
                    TYPE_LLM_FALLBACK, TYPE_RECORD_SUMMARIZE);

    private static final Map<String, String> SUB_SWITCH_KEYS = Map.of(
            TYPE_NL_STUB, "ai.mock.nl-stub",
            TYPE_EDGE_SCENARIOS, "ai.mock.edge-scenarios",
            TYPE_SPEC_SYNC, "ai.mock.spec-sync",
            TYPE_LLM_FALLBACK, "ai.mock.llm-fallback",
            TYPE_RECORD_SUMMARIZE, "ai.mock.record-summarize");

    /**
     * Per-point output ceilings (constants by design: they describe what the
     * point generates — a few stub rules vs a full fault-scenario pack — not
     * what the prompt costs). Estimate math reads these, never a live key.
     */
    private static final Map<String, Integer> OUTPUT_QUOTA_TOKENS = Map.of(
            TYPE_NL_STUB, 2_000,
            TYPE_EDGE_SCENARIOS, 6_000,
            TYPE_SPEC_SYNC, 3_000,
            TYPE_LLM_FALLBACK, 2_000,
            TYPE_RECORD_SUMMARIZE, 4_000);

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_PREVIEW = "preview";
    private static final String STATUS_CONSUMED = "consumed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";

    /** Column caps (V229): over-length preview refs are refused with guidance,
     *  over-length failure reasons are clipped (losing the signal is worse). */
    private static final int RESULT_PREVIEW_REF_MAX = 300;
    private static final int ERROR_MESSAGE_MAX = 500;
    private static final Set<String> CANCELLABLE = Set.of("queued", "running", STATUS_PREVIEW);

    /** Audit action for gate refusals (page/replay read the same spectrum). */
    static final String AUDIT_GATE_REFUSED = "mock_ai_job_gate_refused";

    private static final BeanPropertyRowMapper<MockAiJobDO> JOB_MAPPER =
            new BeanPropertyRowMapper<>(MockAiJobDO.class);

    private static final String JOB_COLUMNS = """
            id, space_id, job_type, status, input_json::text AS inputJson,
            budget_snapshot::text AS budgetSnapshotJson, result_preview_ref,
            error_message, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MockSpaceService spaceService;
    private final MockStubService stubService;
    private final SystemConfigReader configReader;
    private final AuditLogService auditLogService;
    private final ArtifactStorage artifactStorage;
    private final ObjectMapper objectMapper;

    public MockAiJobServiceImpl(NamedParameterJdbcTemplate jdbc, MockSpaceService spaceService,
                                MockStubService stubService, SystemConfigReader configReader,
                                AuditLogService auditLogService, ArtifactStorage artifactStorage,
                                ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.spaceService = spaceService;
        this.stubService = stubService;
        this.configReader = configReader;
        this.auditLogService = auditLogService;
        this.artifactStorage = artifactStorage;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public MockAiJobInfo createJob(MockAiJobCreateInput input) {
        String prompt = input.prompt() == null ? "" : input.prompt().trim();
        if (prompt.isBlank()) {
            throw new ServiceException("AI 草稿任务的提示词不能为空：请用一句话描述期望的 Mock 行为"
                    + "（如「订单接口超时返回 5001」）", BizErrorCode.VALIDATION_ERROR);
        }
        String jobType = input.jobType() == null ? "" : input.jobType().trim();
        if (!SUPPORTED_TYPES.contains(jobType)) {
            throw new ServiceException("不支持的 AI 草稿类型 " + jobType + "，本期可选值："
                    + String.join(" / ", SUPPORTED_TYPES)
                    + "——请在「发起 AI 草稿任务」弹窗的「应用点（草稿类型）」中改选其一后重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Cassette binding spectrum runs AFTER the gate matrix so a
        // closed switch still wins with FORBIDDEN (the abuse-resistance order is
        // a frozen contract). record-summarize induction MUST name a
        // sealed box with recorded frames of the SAME space; every other
        // drafting point MUST NOT carry one. Refusals land before any row
        // exists — a refusal never persists a queued job.
        MockSpaceInfo space = spaceService.get(input.spaceId());

        // Master gate first — an open sub-switch never overrides a closed master.
        String closedKey = null;
        String closedLabel = null;
        if (!configReader.getBoolean(MASTER_KEY, false)) {
            closedKey = MASTER_KEY;
            closedLabel = "Mock 模块 AI 总闸";
        } else if (!configReader.getBoolean(SUB_SWITCH_KEYS.get(jobType), false)) {
            closedKey = SUB_SWITCH_KEYS.get(jobType);
            closedLabel = "应用点「" + jobType + "」分开关";
        }
        if (closedKey != null) {
            refuseAtGate(space, jobType, prompt, closedKey, closedLabel);
        }
        requireCassetteBinding(jobType, input.spaceId(), input.recordingId());

        int inputTokens = estimateInputTokens(prompt);
        int quota = OUTPUT_QUOTA_TOKENS.get(jobType);
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_mock_ai_job
                    (space_id, job_type, input_json, status, budget_snapshot, updated_at)
                VALUES
                    (:spaceId, :jobType, CAST(:input AS jsonb), 'queued',
                     CAST(:snapshot AS jsonb), :now)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("spaceId", input.spaceId())
                .addValue("jobType", jobType)
                .addValue("input", writeJson(inputBody(prompt, jobType, input.recordingId())))
                .addValue("snapshot", writeJson(budgetBody(jobType, prompt, inputTokens, quota)))
                .addValue("now", LocalDateTime.now()), Long.class);
        return get(id);
    }

    /**
     * A-4 cassette binding contract: exactly the record-summarize point carries
     * a recordingId, and the box must be one that actually finished capture in
     * this space. Each refusal names the real fact the caller violated (ghost /
     * still open / nothing recorded / foreign space / stray binding) so the UI
     * can act on it; an induction over an empty cassette would burn tokens to
     * summarize nothing, so honesty gates it out before queuing.
     */
    private void requireCassetteBinding(String jobType, long spaceId, Long recordingId) {
        if (!TYPE_RECORD_SUMMARIZE.equals(jobType)) {
            if (recordingId != null) {
                throw new ServiceException("录制盒只允许由「record-summarize」归纳任务绑定，"
                        + "应用点 " + jobType + " 是纯文本起草，无需录制盒——请在「发起 AI 草稿任务」弹窗不选录制盒直接提交",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return;
        }
        if (recordingId == null) {
            throw new ServiceException("录制归纳必须指定一个已封盒的录制盒（recordingId）："
                    + "请先在「录制回放」页点「封盒」停止录制，再回到「发起 AI 草稿任务」弹窗重新发起归纳",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT space_id, status, cassette_ref FROM tp_mock_recording WHERE id = :id",
                new MapSqlParameterSource("id", recordingId));
        if (rows.isEmpty()) {
            throw new ServiceException("录制盒不存在或已被删除（录制盒 ID " + recordingId
                    + "）——请在「录制回放」页改选列表中仍在的录制盒后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> box = rows.get(0);
        if (spaceId != ((Number) box.get("space_id")).longValue()) {
            throw new ServiceException("录制盒 " + recordingId + " 不属于当前 Mock 空间——"
                    + "归纳产物只能落回录制发生的那个空间，请在「录制回放」页改选本空间下的录制盒",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("recording".equals(box.get("status"))) {
            throw new ServiceException("录制盒 " + recordingId + " 仍在录制中——未封盒的流量还在变化，"
                    + "归纳结果不可复现，请先在「录制回放」页点「封盒」后再发起归纳",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (box.get("cassette_ref") == null || String.valueOf(box.get("cassette_ref")).isBlank()) {
            throw new ServiceException("录制盒 " + recordingId + " 没有录制到任何流量，"
                    + "无样本可归纳——请在「录制回放」页确认代理链路已有流量进入后重新开盒录制",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Stored input body: prompt + type always, recordingId only for induction. */
    private Map<String, Object> inputBody(String prompt, String jobType, Long recordingId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", prompt);
        body.put("jobType", jobType);
        if (recordingId != null) {
            body.put("recordingId", recordingId);
        }
        return body;
    }

    /** Closed-gate refusal: zero rows, plain words naming the key, one audit line. */
    private void refuseAtGate(MockSpaceInfo space, String jobType, String prompt,
                              String key, String label) {
        auditLogService.record(new AuditLogInput(null, space.projectId(), "mock_ai_job",
                String.valueOf(space.id()), AUDIT_GATE_REFUSED,
                Map.of("jobType", jobType, "prompt", prompt),
                Map.of("closedKey", key, "label", label,
                        "hint", "请在 系统配置 → AI 配置 中打开该项后重试")));
        throw new ServiceException(label + "当前为关闭状态（配置项 " + key
                + "）：AI 草稿功能默认关——请前往「系统配置」页搜索 " + key
                + " 打开该项后重新提交",
                BizErrorCode.FORBIDDEN);
    }

    @Override
    public MockAiJobEstimateView estimatePreview(long jobId) {
        MockAiJobDO job = findJob(jobId);
        String prompt = promptOf(job.getInputJson());
        int inputTokens = estimateInputTokens(prompt);
        int quota = OUTPUT_QUOTA_TOKENS.getOrDefault(job.getJobType(), 2_000);
        return new MockAiJobEstimateView(job.getId(), job.getJobType(),
                inputTokens, quota, inputTokens + quota);
    }

    @Override
    public MockAiJobInfo get(long jobId) {
        return toInfo(findJob(jobId));
    }

    @Override
    public List<MockAiJobInfo> listBySpace(long spaceId) {
        // A ghost space is refused with real wording, never answered with a
        // pretend-empty page (same posture as the recording ledger).
        spaceService.get(spaceId);
        return jdbc.query("SELECT " + JOB_COLUMNS
                        + " FROM tp_mock_ai_job WHERE space_id = :sid ORDER BY id DESC",
                new MapSqlParameterSource("sid", spaceId), JOB_MAPPER)
                .stream().map(this::toInfo).toList();
    }

    @Override
    @Transactional
    public List<MockStubInfo> confirm(long jobId, List<MockStubInput> selectedStubs) {
        MockAiJobDO job = findJob(jobId);
        if (!STATUS_PREVIEW.equals(job.getStatus())) {
            throw new ServiceException("只有「待勾选确认」状态的 AI 任务可以确认入库（当前状态 "
                    + job.getStatus() + "）——AI 草稿永不直接进规则库；任务若已被忽略或已入库，请在「发起 AI 草稿任务」弹窗重新发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (selectedStubs == null || selectedStubs.isEmpty()) {
            throw new ServiceException("请至少勾选一条草稿规则再确认入库；整批丢弃请改用「忽略」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<MockStubInfo> landed = new ArrayList<>(selectedStubs.size());
        for (MockStubInput draft : selectedStubs) {
            // Provenance is service-enforced, not caller-supplied: every rule
            // born from a job row is traceable back to it (badges + stale flags).
            MockStubInput traced = new MockStubInput(draft.name(), draft.priority(),
                    draft.method(), draft.pathMatchType(), draft.pathPattern(),
                    draft.matchersJson(), draft.responseJson(), draft.scenarioName(),
                    draft.requiredState(), draft.newState(),
                    "nl-ai", "job:" + jobId, draft.enabled());
            long stubId = stubService.create(job.getSpaceId(), traced);
            landed.add(stubService.get(stubId));
        }
        sealStatus(jobId, STATUS_CONSUMED);
        return landed;
    }

    @Override
    @Transactional
    public MockAiJobInfo ignore(long jobId) {
        MockAiJobDO job = findJob(jobId);
        if (!CANCELLABLE.contains(job.getStatus())) {
            throw new ServiceException("任务已处于终态 " + job.getStatus()
                    + "，不能忽略——历史不可改写；「忽略任务」只适用于排队中/生成中/待勾选确认的任务，请在「AI 起草任务」列表刷新确认状态",
                    BizErrorCode.VALIDATION_ERROR);
        }
        sealStatus(jobId, STATUS_CANCELLED);
        return get(jobId);
    }

    private void sealStatus(long jobId, String status) {
        jdbc.update("UPDATE tp_mock_ai_job SET status = :st, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("st", status)
                        .addValue("now", LocalDateTime.now()).addValue("id", jobId));
    }

    @Override
    @Transactional
    public MockAiJobInfo markRunning(long jobId) {
        transition(jobId, STATUS_QUEUED, STATUS_RUNNING, "AI 任务只有排队中才能开始执行");
        return get(jobId);
    }

    @Override
    @Transactional
    public MockAiJobInfo markPreview(long jobId, String resultPreviewRef) {
        String ref = resultPreviewRef == null ? "" : resultPreviewRef.trim();
        if (ref.isBlank()) {
            throw new ServiceException("AI 草稿回写缺少产物内容：预览引用不能为空——请在「AI 起草任务」列表重新发起该任务，待执行方产出后回写",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (ref.length() > RESULT_PREVIEW_REF_MAX) {
            throw new ServiceException("AI 草稿回写产物过长（" + ref.length() + " 字符，上限 "
                    + RESULT_PREVIEW_REF_MAX + "）：正确写法是只回写草稿工件的地址——请把完整产物改存工件后重新回写",
                    BizErrorCode.VALIDATION_ERROR);
        }
        transition(jobId, STATUS_RUNNING, STATUS_PREVIEW,
                "AI 草稿必须先开始执行（running）才能回写预览——排队中的任务未经消费领取不允许直接产出");
        jdbc.update("UPDATE tp_mock_ai_job SET result_preview_ref = :ref, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("ref", ref)
                        .addValue("now", LocalDateTime.now()).addValue("id", jobId));
        return get(jobId);
    }

    @Override
    @Transactional
    public MockAiJobInfo markFailed(long jobId, String errorMessage) {
        String reason = errorMessage == null || errorMessage.isBlank()
                ? "模型执行失败（未提供具体原因）"
                : errorMessage.trim();
        if (reason.length() > ERROR_MESSAGE_MAX) {
            reason = reason.substring(0, ERROR_MESSAGE_MAX);
        }
        transition(jobId, STATUS_RUNNING, STATUS_FAILED,
                "AI 任务只有执行中才能标记失败（当前状态由转换闸点名）——终态历史不可改写");
        jdbc.update("UPDATE tp_mock_ai_job SET error_message = :msg, updated_at = :now WHERE id = :id",
                new MapSqlParameterSource().addValue("msg", reason)
                        .addValue("now", LocalDateTime.now()).addValue("id", jobId));
        return get(jobId);
    }

    /**
     * Single-point status spectrum guard for the consumer write-backs: the row
     * must currently sit in {@code from} or the refusal names the real status
     * in plain words. Being @Transactional on the impl seam, this reads and
     * checks inside the same transaction the UPDATE rides — terminal and
     * out-of-spectrum states can never slip past the transition.
     */
    private void transition(long jobId, String from, String to, String why) {
        MockAiJobDO job = findJob(jobId);
        if (!from.equals(job.getStatus())) {
            throw new ServiceException(why + "（当前状态 " + job.getStatus() + "，要求 " + from
                    + "）——请在「AI 起草任务」列表刷新查看该任务当前状态后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        sealStatus(jobId, to);
    }

    /**
 * Draft-bundle upload grant prereq): resolves the job row, reads
 * the owning project from its space (never from a request context), and
 * hands the presigned PUT to the caller. Storage routing stays a single
 * point inside ArtifactStorage.
 */
    @Override
    public MockDraftArtifactGrantView artifactGrant(long jobId, String fileName) {
        MockAiJobDO job = findJob(jobId);
        MockSpaceInfo space = spaceService.get(job.getSpaceId());
        Long projectId = space.projectId();
        StorageGrant grant = artifactStorage.grantMockDraftUpload(
                projectId == null ? 0L : projectId, fileName);
        return new MockDraftArtifactGrantView(job.getId(), grant.key(), grant.url());
    }

    /**
 * Draft read-back : status spectrum first — items only exist
 * once the consumer sealed a preview (consumed stays readable so the
 * drawer can review what was actually landed). The reference column is
 * read as an object key through the same route the grant wrote to; a
 * missing object or unparseable bundle is refused in plain words rather
 * than answered with an empty list (the drawer would render "the model
 * drafted nothing" — a lie this door exists to prevent).
 */
    @Override
    public List<MockAiJobDraftItemView> previewDraftItems(long jobId) {
        MockAiJobDO job = findJob(jobId);
        String status = job.getStatus();
        if (!STATUS_PREVIEW.equals(status) && !STATUS_CONSUMED.equals(status)) {
            throw new ServiceException("AI 草稿任务尚未产出预览，没有可回读的草稿条目"
                    + "（当前状态 " + status + "，可回读状态为 preview / consumed）——请在「AI 起草任务」列表待任务转为「待勾选确认」后再打开",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String ref = job.getResultPreviewRef();
        byte[] bundle = ref == null || ref.isBlank()
                ? null : artifactStorage.readBytesQuietly(ref.trim());
        if (bundle == null) {
            throw new ServiceException("草稿产物缺失（工件 " + ref
                    + " 不可读），无法回读草稿条目——请在「AI 起草任务」列表重新发起起草",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            JsonNode root = objectMapper.readTree(new String(bundle, java.nio.charset.StandardCharsets.UTF_8));
            List<MockAiJobDraftItemView> items = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                JsonNode fault = item.path("fault");
                items.add(new MockAiJobDraftItemView(
                        item.path("name").asText(""),
                        item.path("method").asText(""),
                        item.path("path").asText(""),
                        item.path("pathMatchType").asText(""),
                        item.path("responseJson").asText(""),
                        item.hasNonNull("scenarioName") ? item.get("scenarioName").asText() : null,
                        fault.isObject() ? fault.toString() : null,
                        item.path("sourceAnchor").asText("")));
            }
            return items;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("草稿产物内容不是合法 JSON 束，无法回读草稿条目——"
                    + "请在「AI 起草任务」列表重新发起起草", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private MockAiJobDO findJob(long id) {
        List<MockAiJobDO> rows = jdbc.query("SELECT " + JOB_COLUMNS
                + " FROM tp_mock_ai_job WHERE id = :id",
                new MapSqlParameterSource("id", id), JOB_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("AI 草稿任务不存在或已被删除——请在「AI 起草任务」列表确认任务仍在；"
                    + "如需新的草稿，请点「AI 起草」重新发起", BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    /**
     * Creation-time cost pin (single write point; readers use the column, never
     * the live keys — a hot switch flip must not re-price an in-flight job).
     */
    private Map<String, Object> budgetBody(String jobType, String prompt,
                                           int inputTokens, int quota) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobType", jobType);
        body.put("promptChars", prompt.length());
        body.put("estimatedInputTokens", inputTokens);
        body.put("outputQuotaTokens", quota);
        body.put("estimatedTotalTokens", inputTokens + quota);
        return body;
    }

    /**
     * Input-size heuristic: CJK-dominant prompts run ~1 token per char; mixed
     * text is discounted. Deliberately dumb and constant — an estimate shown
     * before the fact must not depend on anything the operator can flip.
     */
    private int estimateInputTokens(String prompt) {
        int len = prompt == null ? 0 : prompt.trim().length();
        return Math.max(1, (int) Math.ceil(len / 1.5));
    }

    private String promptOf(String inputJson) {
        try {
            return objectMapper.readTree(inputJson == null ? "{}" : inputJson)
                    .path("prompt").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ServiceException("AI 任务载荷序列化失败——请在「发起 AI 草稿任务」弹窗稍后重试；"
                    + "持续失败时请在「系统配置」页检查模型服务配置", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private MockAiJobInfo toInfo(MockAiJobDO row) {
        return new MockAiJobInfo(row.getId(), row.getSpaceId(), row.getJobType(),
                row.getStatus(), row.getInputJson(), row.getBudgetSnapshotJson(),
                row.getResultPreviewRef(), row.getErrorMessage(),
                row.getCreatedAt(), row.getUpdatedAt());
    }
}
