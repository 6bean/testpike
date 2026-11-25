package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDensityGate;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDirectiveSwitch;
import com.sixbean.testpike.aiservice.mock.domain.service.MockDraftGenService;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockAiJobInfoRemote;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGateway;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockGenMessage;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Drafting orchestration behind the ai.mock.gen consumer ;
 * A-1 nl-stub — edge-scenarios/spec-sync land as extensions of the same
 * pipeline, which is why the job type is carried into the prompt but never
 * branched into a second chain here).
 * <p>Order (each step is a RED-pinned contract in the sibling test): pull the
 * job row over the core door → anything not 'queued' is skipped with zero
 * side effects → seal running (the seal doubles as the concurrency guard: a
 * losing redelivery catches the transition refusal and acks silently, no LLM
 * burn, no failure seal on someone else's job) → route the project endpoint
 * and run one drafting chat under the {@code MOCK_DRAFT_GEN_JSON} marker →
 * strip echo/fences with {@link JsonOutputExtractor} and require at least one
 * draft item (an empty bundle fails in plain words — a preview with no rules
 * would only tease the confirmation page) → upload the bundle through the
 * presigned grant → write back preview carrying the object key only (the
 * 300-char reference column never sees the payload).
 * <p>Failure semantics: any mid-chain exception seals the job failed with the
 * real reason, then is swallowed — the row is terminal truth for the UI, so a
 * requeue loop would only re-burn tokens on the same broken input. Gate
 * spectrum and ghost-row wording stay single-sourced in core.
 */
@Service
public class MockDraftGenServiceImpl implements MockDraftGenService {

    private static final Logger log = LoggerFactory.getLogger(MockDraftGenServiceImpl.class);

    /** Drafting contract marker (local stub branch judgement; single-sourced in common). */
    static final String DRAFT_MARKER = LlmExtractMarkers.MOCK_DRAFT_GEN;

    /** Only a queued job may enter the pipeline — cancelled/consumed/terminal skip. */
    private static final String STATUS_QUEUED = "queued";

    /** Plain-word refusal when a ghost row was pulled (deleted between publish and consume). */
    private static final String GHOST_ROW_MESSAGE = "AI 草稿任务不存在或已被删除";

    /** Behaviour-directive gate for the drafting leg (factory default on). */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.mock.draft-gen-directives.enabled";

    /** Template family — carries only the shared role+shape header. */
    public static final String TEMPLATE_NAME = "mock-draft-gen-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "mock-draft-gen";

    /**
     * Process-stream kind of a bulk-enrichment run (constant name matches the
     * R67 ledger-gate discovery pattern). The value is the same literal the
     * core sweeper and the revisit drawer key on — never a second spelling.
     */
    public static final String EVENT_KIND_SPEC_ENRICH = "mock-spec-enrich";

    /** K8 hot key: endpoints one AI chat covers inside one enrichment run. */
    public static final String KEY_ENRICH_BATCH_SIZE = "mock.spec-import.enrich-batch-size";
    static final int ENRICH_BATCH_DEFAULT = 20;
    static final int ENRICH_BATCH_MIN = 1;
    static final int ENRICH_BATCH_MAX = 100;

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final MockDraftArtifactGateway artifactGateway;
    private final ObjectMapper objectMapper;
    /** Nullable business-step ledger (same tolerant shape as the spec-extract leg). */
    private final TaskProcessEventStore processEventStore;

    public MockDraftGenServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                   ProjectLlmRouter projectLlmRouter,
                                   MockDraftArtifactGateway artifactGateway,
                                   ObjectMapper objectMapper,
                                   TaskProcessEventStore processEventStore) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.artifactGateway = artifactGateway;
        this.objectMapper = objectMapper;
        this.processEventStore = processEventStore;
    }

    @Override
    public void generate(MockGenMessage message) {
        long jobId = message.jobId();
        MockAiJobInfoRemote job;
        try {
            job = coreGateway.getMockJob(jobId);
        } catch (ServiceException e) {
            if (isGhostRow(e)) {
                log.info("AI draft job row no longer exists, skipping silently jobId={}", jobId);
                return;
            }
            throw e;
        }
        if (job == null || !STATUS_QUEUED.equals(job.status())) {
            log.info("AI draft job not in queued state, skipping jobId={} status={}",
                    jobId, job == null ? "null" : job.status());
            return;
        }

        String prompt = promptOf(job);
        if (prompt.isBlank()) {
            sealFailed(jobId, "任务输入中没有需求描述，AI 起草未执行——请重新发起并填写桩需求");
            return;
        }

        try {
            coreGateway.markMockRunning(jobId);
        } catch (ServiceException e) {
            if (isGhostRow(e)) {
                log.info("AI draft job row deleted before claim, skipping silently jobId={}", jobId);
                return;
            }
            // queued→running refused by the core spectrum = a concurrent winner
            // already holds the job (redelivery race). Silent ack: no second LLM
            // burn, no failure seal on the winner's running row.
            log.info("AI draft job status write rejected (lost the race / terminal state already sealed), skipping silently jobId={} reason={}",
                    jobId, e.getMessage());
            return;
        }

        // Triage ③): the M10 job types ride the ai.mock.gen queue
        // through the same create door (core gate spectrum is single-sourced,
        // switch-gated like M9) but they are NOT drafting points. Sealing
        // running first then failing in plain words keeps the ledger honest:
        // the row was consumed and refused, not silently abandoned in queued.
        String triageRefusal = triageRefusalFor(job.jobType());
        if (triageRefusal != null) {
            sealFailed(jobId, triageRefusal);
            log.info("AI draft queue task sealed after dispatch routing by application point jobId={} jobType={}", jobId, job.jobType());
            return;
        }

        // The enrichment leg streams business steps even when it dies mid-run,
        // so its run token is minted here and the shared failure tail lands
        // one honest error frame on the same ledger.
        final String[] enrichRunToken = new String[1];

        try {
            // Leg split : A-4 induction reads the cassette through
            // runSummarize; every other accepted point drafts from text.
            if ("spec-enrich".equals(job.jobType())) {
                enrichRunToken[0] = java.util.UUID.randomUUID().toString();
                runSpecEnrich(message, job, prompt, enrichRunToken[0]);
            } else if ("record-summarize".equals(job.jobType())) {
                runSummarize(message, job);
            } else {
                runDrafting(message, job, prompt);
            }
        } catch (Exception e) {
            String reason = e instanceof ServiceException se
                    ? se.getMessage()
                    : "AI 起草服务异常: " + e.getMessage();
            if (enrichRunToken[0] != null) {
                recordEnrichFrame(enrichRunToken[0], jobId, "error", "执行失败：" + reason);
            }
            sealFailed(jobId, reason);
            // The row is now terminal truth; swallowing prevents a pointless
            // container-retry that would re-burn tokens on the same input.
            log.warn("AI draft job failed and sealed jobId={} reason={}", jobId, reason);
        }
    }

    /**
 * Plain-word refusal for job types the drafting chain must never draft,
 * null for accepted drafting points. Why each M10 type is refused here:
 * llm-fallback answers are produced by the SYNCHRONOUS miss-time leg
 * (cache identity = space + normalized path + masked fingerprint only
 * exists on that leg; a queue payload carries none of it, so drafting one
 * would burn tokens on an answer no cache key can case). record-summarize
 * (A-4 induction) left this triage in it rides its own
 * summarize leg below, still on this consumer.
 */
    private static String triageRefusalFor(String jobType) {
        return switch (jobType == null ? "" : jobType) {
            case "llm-fallback" -> "应用点「llm-fallback」不走 AI 起草队列：兜底代答由未命中请求的"
                    + "同步生成链路即时代答并缓存（下次同问秒回），排队起草没有可入库的缓存身份；"
                    + "请在 mock 空间把未命中策略设为 AI 并打开该开关，由真实请求触发";
            default -> null;
        };
    }

    /** LLM drafting → parse → bundle upload → preview write-back (job already sealed running). */
    private void runDrafting(MockGenMessage message, MockAiJobInfoRemote job, String prompt) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", buildSystemPrompt(job.jobType())),
                new ChatMessage("user", "需求描述：" + prompt + "\nmock 空间：" + job.spaceId()));
        draftToPreview(message, job, messages);
    }

    /**
 * A-4 induction leg : pull the box's masked frame sample
 * over the core door (cap K8 = mock.ai.summarize.max-sample-frames, newest
 * N — a long cassette never swallows the pinned 4000-token budget), fold
 * the sample into one chat, and file the result through the SAME draft
 * bundle tail so the confirmation drawer stays single-sourced. Frames are
 * already masked by the capture-side pipeline (MaskingGate single source);
 * raw originals never existed on this path, and each body is additionally
 * truncated to keep the prompt honest to its size.
 */
    private void runSummarize(MockGenMessage message, MockAiJobInfoRemote job) {
        Long recordingId = recordingIdOf(job);
        if (recordingId == null) {
            throw new ServiceException("录制归纳任务没有绑定录制盒（recordingId 缺失）——本次归纳未执行；"
                    + "请在流量检视页选中一个已封盒的录制后重新发起", BizErrorCode.VALIDATION_ERROR);
        }
        List<MockRecordingFrameMessage> frames = coreGateway.getMockRecordingFrames(recordingId);
        if (frames == null || frames.isEmpty()) {
            // Induction over silence would burn tokens to summarize nothing.
            throw new ServiceException("录制盒 " + recordingId + " 没录到任何流量帧（空磁带）——"
                    + "归纳无米下锅，本次任务按失败封存；请先录制一段真实流量再发起归纳",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", buildSystemPrompt(job.jobType())),
                new ChatMessage("user", "归纳要求：" + promptOf(job) + "\n已脱敏录制帧样本：\n"
                        + framesDigest(frames)));
        draftToPreview(message, job, messages);
    }

    /**
     * Bulk contract-enrichment leg (AI data enrichment point): endpoints ride
     * the pinned contract version row, batched by the K8 hot key. One chat
     * per batch; the deterministic floor rows come back through the SAME core
     * drafts door the wizard reads (single-sourced expansion, never a second
     * schema-to-sample engine) and land in the bundle beside the AI rows.
     * The refined advice rides the core intake door after the batches — that
     * backfill is advisory: a refused round never invalidates the drafts, the
     * wizard degrades to rule advice with an honest label past the deadline.
     * The uploaded bundle still ends at the human confirm gate unchanged.
     */
    private void runSpecEnrich(MockGenMessage message, MockAiJobInfoRemote job,
                               String prompt, String runToken) {
        long jobId = message.jobId();
        Long specId = longFieldOf(job, "specId");
        if (specId == null) {
            throw new ServiceException("批量完善任务没有绑定契约版本行（specId 缺失）——本次完善未执行；"
                    + "请在「导入契约·批量生成」向导选择契约版本后重新发起", BizErrorCode.VALIDATION_ERROR);
        }
        recordEnrichFrame(runToken, jobId, "step",
                "入队：AI 数据完善任务进入执行，契约版本行 " + specId);
        CoreGateway.ApiSpecBrief spec = coreGateway.getApiSpec(specId);
        List<CoreGateway.ApiEndpointBrief> endpoints =
                spec == null ? List.of() : spec.endpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new ServiceException("契约版本行 " + specId + " 没有已解析的接口——"
                    + "请先在「接口管理」完成契约解析后再发起批量完善", BizErrorCode.VALIDATION_ERROR);
        }
        String specName = spec.name() == null || spec.name().isBlank()
                ? "契约 " + specId : spec.name();
        recordEnrichFrame(runToken, jobId, "step",
                "契约解析：从「" + specName + "」读取 " + endpoints.size() + " 个接口");
        int batchSize = enrichBatchSize();
        int batchCount = (endpoints.size() + batchSize - 1) / batchSize;
        recordEnrichFrame(runToken, jobId, "step",
                "端点分" + batchCount + "批：每次 AI 完善覆盖最多 " + batchSize + " 个接口");
        List<CoreGateway.SpecDraftAdviceRow> floorRows =
                coreGateway.listSpecDraftAdvice(specId, job.spaceId());
        int floorCount = floorRows == null ? 0 : floorRows.size();
        recordEnrichFrame(runToken, jobId, "step",
                "确定性直出完成：契约直出桩 " + floorCount + " 条（未等待 AI）");

        LlmEndpointConfig endpoint = projectLlmRouter.route(message.projectId());
        LlmFallbackNotice notice = new LlmFallbackNotice(message.operatorId(),
                message.projectId(), jobNoticeLabel(job.jobType(), jobId));
        ArrayNode enriched = objectMapper.createArrayNode();
        List<CoreGateway.SpecVerdictRow> verdictRows = new java.util.ArrayList<>();
        for (int b = 0; b < batchCount; b++) {
            List<CoreGateway.ApiEndpointBrief> slice = endpoints.subList(
                    b * batchSize, Math.min(endpoints.size(), (b + 1) * batchSize));
            List<ChatMessage> messages = List.of(
                    new ChatMessage("system", buildSystemPrompt(job.jobType())),
                    new ChatMessage("user", "本批契约端点（批 " + (b + 1) + "/" + batchCount + "）：\n"
                            + endpointsJson(slice)
                            + "\n补充要求：" + prompt + "\nmock 空间：" + job.spaceId()));
            LlmChatResult result = llmFallbackChain.chat(
                    new ChatCompletionRequest(endpoint.modelName(), messages, null, null),
                    message.projectId(), notice, false);
            ArrayNode items = parseDraftItems(result.content());
            for (JsonNode item : items) {
                if (item instanceof ObjectNode row) {
                    row.put("origin", "ai");
                    enriched.add(row);
                }
            }
            verdictRows.addAll(parseVerdictRows(result.content()));
            recordEnrichFrame(runToken, jobId, "step",
                    "AI 数据增强 批 " + (b + 1) + "/" + batchCount
                            + "：本批模型产出 " + items.size() + " 条");
        }
        if (enriched.isEmpty()) {
            throw new ServiceException("模型未产出任何可用的完善条目（输出为空或不合契约），"
                    + "本次 AI 数据完善按失败处理，可调整补充要求后重新发起", BizErrorCode.AI_FORMAT_ERROR);
        }
        int refined = 0;
        try {
            coreGateway.refineSpecVerdicts(specId, job.spaceId(), verdictRows);
            refined = verdictRows.size();
        } catch (Exception backfillOutage) {
            log.warn("Batch enrichment pre-verdict refill unreachable (draft unaffected, preview shows the rule suggestion) jobId={} reason={}",
                    jobId, backfillOutage.getMessage());
        }
        recordEnrichFrame(runToken, jobId, "step", refined > 0
                ? "预裁回填：已回写 " + refined + " 条智能建议"
                : "预裁回填：本次未回写（智能建议暂为规则默认）");

        ArrayNode bundleItems = objectMapper.createArrayNode();
        for (CoreGateway.SpecDraftAdviceRow row : floorRows == null ? List.<CoreGateway.SpecDraftAdviceRow>of() : floorRows) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("name", row.name());
            item.put("method", row.method());
            item.put("path", row.pathPattern());
            item.put("pathMatchType", row.pathMatchType());
            item.put("responseJson", row.responseJson());
            item.put("source", row.source());
            item.put("sourceRef", row.sourceRef());
            item.put("origin", "contract");
            bundleItems.add(item);
        }
        bundleItems.addAll(enriched);
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.set("items", bundleItems);
        bundle.put("jobType", job.jobType());
        String objectKey = artifactGateway.uploadDraftBundle(jobId,
                MockDraftArtifactGateway.DRAFT_FILE_NAME,
                bundle.toString().getBytes(StandardCharsets.UTF_8));
        coreGateway.markMockPreview(jobId, objectKey);
        recordEnrichFrame(runToken, jobId, "step",
                "草稿就绪：共 " + bundleItems.size() + " 条完善草稿待人工勾选确认");
        log.info("AI data-enrichment job produced preview jobId={} rule-direct={} ai-generated={}",
                jobId, floorCount, enriched.size());
    }

    /** One business-step frame on the enrichment ledger (null-tolerant door). */
    private void recordEnrichFrame(String runToken, long jobId, String level, String summary) {
        if (processEventStore == null || runToken == null) {
            return;
        }
        processEventStore.record(EVENT_KIND_SPEC_ENRICH, String.valueOf(jobId),
                runToken, level, summary, null);
    }

    /** Batch size from the K8 hot key, clamped; garbage falls back to the shipped default. */
    private int enrichBatchSize() {
        String raw = coreGateway.getSysConfig(KEY_ENRICH_BATCH_SIZE);
        try {
            int parsed = Integer.parseInt(raw == null ? "" : raw.trim());
            return Math.min(ENRICH_BATCH_MAX, Math.max(ENRICH_BATCH_MIN, parsed));
        } catch (NumberFormatException unreadable) {
            return ENRICH_BATCH_DEFAULT;
        }
    }

    /** Compact endpoint list for the batch prompt (method/path/parameter names/response schemas). */
    private String endpointsJson(List<CoreGateway.ApiEndpointBrief> endpoints) {
        ArrayNode out = objectMapper.createArrayNode();
        for (CoreGateway.ApiEndpointBrief e : endpoints) {
            ObjectNode row = objectMapper.createObjectNode();
            row.put("method", e.method());
            row.put("path", e.path());
            ArrayNode params = row.putArray("parameters");
            if (e.parameters() != null) {
                e.parameters().forEach(p -> params.add(p.name()));
            }
            row.set("responseSchemas", objectMapper.valueToTree(
                    e.responseSchemas() == null ? Map.of() : e.responseSchemas()));
            out.add(row);
        }
        return out.toString();
    }

    /** Lenient verdict parse: absent or malformed rows are skipped (core intake validates again). */
    private List<CoreGateway.SpecVerdictRow> parseVerdictRows(String content) {
        List<CoreGateway.SpecVerdictRow> rows = new java.util.ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(JsonOutputExtractor.extractJson(content))
                    .path("verdicts");
            if (!arr.isArray()) {
                return rows;
            }
            for (JsonNode v : arr) {
                String key = v.path("endpointKey").asText("");
                if (key.isBlank()) {
                    continue;
                }
                rows.add(new CoreGateway.SpecVerdictRow(key,
                        v.path("verdict").asText(""), v.path("reason").asText(""),
                        v.path("conflictAction").asText(null), v.path("impactJson").asText(null),
                        v.path("realism").asInt(0), v.path("confidence").asInt(0)));
            }
        } catch (Exception malformed) {
            log.info("Batch enrichment output carries no parsable pre-verdict suggestion, continuing with the rule suggestion verdictParseSkipped");
        }
        return rows;
    }

    /** Long input field from input_json (core stamps specId only for the enrichment point). */
    private Long longFieldOf(MockAiJobInfoRemote job, String field) {
        try {
            JsonNode node = objectMapper.readTree(job.inputJson() == null ? "{}" : job.inputJson())
                    .path(field);
            return node.isMissingNode() || node.isNull() ? null : node.asLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** Shared tail of both legs: one chat under the draft contract → bundle upload → preview. */
    private void draftToPreview(MockGenMessage message, MockAiJobInfoRemote job,
                                List<ChatMessage> messages) {
        long jobId = message.jobId();
        LlmEndpointConfig endpoint = projectLlmRouter.route(message.projectId());
        LlmFallbackNotice notice = new LlmFallbackNotice(message.operatorId(),
                message.projectId(), jobNoticeLabel(job.jobType(), jobId));
        LlmChatResult result = llmFallbackChain.chat(
                new ChatCompletionRequest(endpoint.modelName(), messages, null, null),
                message.projectId(), notice, false);

        ArrayNode items = parseDraftItems(result.content());
        if (items.isEmpty()) {
            throw new ServiceException("模型未产出任何可保存的桩条目（输出为空或不合契约），"
                    + "本次 AI 起草按失败处理，可调整需求描述后重新发起",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        ObjectNode bundle = objectMapper.createObjectNode();
        bundle.set("items", items);
        bundle.put("jobType", job.jobType());
        String objectKey = artifactGateway.uploadDraftBundle(jobId,
                MockDraftArtifactGateway.DRAFT_FILE_NAME,
                bundle.toString().getBytes(StandardCharsets.UTF_8));
        coreGateway.markMockPreview(jobId, objectKey);
        log.info("AI draft job produced preview jobId={} item-count={} key={}", jobId, items.size(), objectKey);
        recallDensityCheck(jobId, items.size(), RecallDensityGate.estimateTokensFromChars(
                messages.get(messages.size() - 1).content().length()));
    }

    /** piece 2: per-door recall-density floor hot key (band 0-50,
 * 0 = check off), factory 20.3 from the V501 drafting measurement x 0.6.
 * This plane has no planner face, so the denominator rides the gate's
 * char→estimated-token helper over the prompt the run was shown; the
 * decision arithmetic stays single-sourced in {@link RecallDensityGate}. */
    static final String KEY_RECALL_DENSITY_FLOOR =
            "ai.mock.recall-density-floor-per-ktoken";
    static final double DEFAULT_RECALL_DENSITY_FLOOR = 20.3;

    /** WARN one honest numbers line when the drafted bundle stays under the
     *  floor; a met or switched-off floor keeps the write-back byte-silent. */
    private void recallDensityCheck(long jobId, int itemCount, int estimatedTokens) {
        double floor = RecallDensityGate.parseFloor(
                coreGateway.getSysConfig(KEY_RECALL_DENSITY_FLOOR),
                DEFAULT_RECALL_DENSITY_FLOOR);
        if (RecallDensityGate.missed(floor, itemCount, estimatedTokens)) {
            log.warn("Mock drafting density floor gate jobId={} received={} min-items={} source-tokens={}",
                    jobId, itemCount,
                    RecallDensityGate.expectedItems(estimatedTokens, floor), estimatedTokens);
        }
    }

    private static String jobNoticeLabel(String jobType, long jobId) {
        if ("record-summarize".equals(jobType)) {
            return "AI Mock 录制归纳任务#" + jobId;
        }
        if ("spec-enrich".equals(jobType)) {
            return "AI Mock 数据完善任务#" + jobId;
        }
        return "AI Mock 起草任务#" + jobId;
    }

    /** recordingId from input_json (core stamps it only for record-summarize). */
    private Long recordingIdOf(MockAiJobInfoRemote job) {
        try {
            JsonNode node = objectMapper.readTree(job.inputJson() == null ? "{}" : job.inputJson())
                    .path("recordingId");
            return node.isMissingNode() || node.isNull() ? null : node.asLong();
        } catch (Exception e) {
            return null;
        }
    }

    /** Compact frame digest for the prompt: method/path/status + body capped at 500 chars. */
    private String framesDigest(List<MockRecordingFrameMessage> frames) {
        ArrayNode out = objectMapper.createArrayNode();
        for (MockRecordingFrameMessage f : frames) {
            ObjectNode row = objectMapper.createObjectNode();
            row.put("method", f.method());
            row.put("path", f.path());
            row.put("status", f.responseStatus() == null ? -1 : f.responseStatus());
            String body = f.responseBody() == null ? "" : f.responseBody();
            row.put("body", body.length() > 500 ? body.substring(0, 500) : body);
            out.add(row);
        }
        return out.toString();
    }

    /** Parse the model output into the draft items array (illegal shape = plain-word failure). */
    private ArrayNode parseDraftItems(String content) {
        String json = JsonOutputExtractor.extractJson(content);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ServiceException("AI 起草输出不是合法 JSON，本次任务按失败处理",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        JsonNode items = root.path("items");
        return items.isArray() ? (ArrayNode) items : objectMapper.createArrayNode();
    }

    /**
     * Full drafting prompt = the shared role+shape header through the single
     * L0 assembly (published template row when the gate is open, built-in
     * contract on any miss, kernel invariant appended exactly once) plus the
     * job-type clause, which deliberately stays on the code side: an
     * operator-edited template row must never be able to drop the
     * point-specific output semantics (edge-scenarios fault spectrum,
     * spec-sync, record-summarize).
     */
    String buildSystemPrompt(String jobType) {
        return behaviorDirectivesHead() + "\n\n应用点："
                + (jobType == null ? "" : jobType) + "。" + pointInstruction(jobType)
                // : recall block appended at the tail shared by both
                // queue consumers; OFF state byte-identical to the pre- prompt.
                + recallSegment();
    }

    /**
 * per-domain hot switch for the exhaustive-recall clause block on
 * the mock-draft generation face (fail-soft ON, read single-sourced in
 * RecallDirectiveSwitch).
 */
    public static final String KEY_RECALL_DIRECTIVE_ENABLED = "ai.mock.recall-directive.enabled";

    /** Recall segment appended to the prompt tail (zero bytes off). */
    String recallSegment() {
        return RecallDirectiveSwitch.segment(
                coreGateway.getSysConfig(KEY_RECALL_DIRECTIVE_ENABLED), KEY_RECALL_DIRECTIVE_ENABLED);
    }

    /** Shared role+shape header through the single behaviour-kernel assembly. */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHeadFromGateway(coreGateway,
                KEY_DIRECTIVES_ENABLED, TEMPLATE_NAME, TEMPLATE_DOMAIN,
                sharedDraftingContract(),
                "行为指令模板读路径不可用，本轮 AI 起草回退内置契约");
    }

    private static String sharedDraftingContract() {
        String role = "你是 API Mock 桩规则起草器（" + DRAFT_MARKER + "）。";
        String shape = "根据需求描述草拟 mock 桩规则条目，输出 JSON：\n"
                + "{\"items\":[{\"name\":\"规则名\",\"method\":\"GET|POST|PUT|DELETE|PATCH|ANY\","
                + "\"path\":\"/路径\",\"pathMatchType\":\"exact|prefix|regex|template\","
                + "\"responseJson\":\"{\\\"status\\\":200,\\\"body\\\":...}\","
                + "\"scenarioName\":\"可空，有状态场景名\"}]}\n";
        return role + shape
                + "约束：只起草需求描述内的接口，不发明额外端点；responseJson 为响应构造器 JSON 文本；"
                + "条目仅供人工勾选确认——未经确认不会进入规则库。";
    }

    /** Fault-playbook clause shared by the edge-scenarios and enrichment legs (one source). */
    private static final String EDGE_FAULT_PLAYBOOK =
            "400/401/403/429/500 业务形态各一条，再加响应缓慢（fault.type=delay）与连接中断"
                    + "（fault.type=disconnect）两条；responseJson 为响应构造器文档，"
                    + "fault 键与 status/body 同级。";

    private static String pointInstruction(String jobType) {
        return switch (jobType == null ? "" : jobType) {
            // A-2: the edge pack must span the whole failure spectrum, not happy stubs.
            case "edge-scenarios" -> "本任务为契约边缘场景包：对需求中的每个端点产出全套故障剧本——"
                    + EDGE_FAULT_PLAYBOOK;
            // MJ P4: the enrichment leg rides the same playbook plus a refined-advice tail.
            case "spec-enrich" -> "本任务为 AI 数据完善（契约批量）：对「本批契约端点」清单里的每个端点，"
                    + "先产出一条完善后的 200 桩条目——responseJson 的 body 按契约字段填贴近真实业务的"
                    + "样例数据（枚举取契约枚举值，金额/日期/编号形态可信），不发明契约没有的字段；"
                    + "再为每个端点产出全套故障剧本——" + EDGE_FAULT_PLAYBOOK
                    + "同时在输出 JSON 中与 items 平级返回本批每个端点的预裁建议："
                    + "\"verdicts\":[{\"endpointKey\":\"METHOD 路径\",\"verdict\":\"recommend 或 avoid\","
                    + "\"reason\":\"一句话业务用途\",\"conflictAction\":\"skip/override/rename 或 null\","
                    + "\"realism\":0-100,\"confidence\":0-100}]。不得发明清单之外的端点。";
            // A-5 adjacent: sync proposals restate the contract's happy shape honestly.
            case "spec-sync" -> "本任务为契约同步：每个端点产出 200 契约形态桩条目，"
                    + "responseJson 的 body 携带 contractSync 标记，与现行契约保持一致，不发明新字段。";
            // A-4: induction summarizes recorded traffic; dubious items carry the marker.
            case "record-summarize" -> "本任务为录制流量归纳：从已脱敏的帧样本中归纳该服务的端点形态，"
                    + "每个端点产出一条代表性桩条目（同端点多帧取正常 2xx 响应）；样本不足以确认端点含义时"
                    + "（如同路径多次响应矛盾、疑似一次性接口），条目的 name 前加「[待人工确认]」标记，"
                    + "人工勾选是唯一入库通道；不得发明样本里没有的端点。";
            default -> "按需求描述逐条起草 happy-path 桩规则。";
        };
    }

    /** Drafting prompt stored in input_json at create time (core sanitizes it). */
    private String promptOf(MockAiJobInfoRemote job) {
        try {
            return objectMapper.readTree(job.inputJson() == null ? "{}" : job.inputJson())
                    .path("prompt").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isGhostRow(ServiceException e) {
        return e.getMessage() != null && e.getMessage().contains(GHOST_ROW_MESSAGE);
    }

    /** Failure seal best-effort: a core-side refusal (row vanished mid-chain) is logged, never rethrown. */
    private void sealFailed(long jobId, String reason) {
        try {
            coreGateway.markMockFailed(jobId, reason);
        } catch (Exception e) {
            log.warn("AI draft job failure-seal write rejected jobId={} (row may already be deleted) sealError={} original-failure={}",
                    jobId, e.getMessage(), reason);
        }
    }
}
