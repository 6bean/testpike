package com.sixbean.testpike.aiservice.spec.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatBehaviorDirectives;
import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedger;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityMeter;
import com.sixbean.testpike.aiservice.doc.domain.component.ModelAdaptiveBudget;
import com.sixbean.testpike.aiservice.doc.domain.component.PlaneLabels;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupFanoutAbortException;
import com.sixbean.testpike.aiservice.llm.domain.component.GroupParallelFanout;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmTimeouts;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.domain.component.RunBudgetAbort;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDensityGate;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.llm.pipeline.RecallDirectiveSwitch;
import com.sixbean.testpike.aiservice.spec.domain.component.SpecDraftItemsParser;
import com.sixbean.testpike.aiservice.spec.domain.component.SpecDraftParseOutcome;
import com.sixbean.testpike.aiservice.spec.domain.service.SpecDraftExtractService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
public class SpecDraftExtractServiceImpl implements SpecDraftExtractService {

    private static final Logger log = LoggerFactory.getLogger(SpecDraftExtractServiceImpl.class);

    
    static final String EXTRACT_MARKER = LlmExtractMarkers.SPEC_DRAFT_EXTRACT;

    
    public static final String KEY_MAX_CHUNKS = AiSpecExtractMessage.KEY_MAX_CHUNKS;

    
    static final int DEFAULT_MAX_CHUNKS = AiSpecExtractMessage.DEFAULT_MAX_CHUNKS;

    /**
     * Factory-on governance key for the operator-tuned behaviour-directive
     * template read (same tri-state semantics as the dialog family: only an
     * explicit 0/false closes it; a missing row keeps directives on).
     */
    public static final String KEY_DIRECTIVES_ENABLED = "ai.spec.extract-directives.enabled";

    /** Template family of this domain (tp_prompt_template name). */
    public static final String TEMPLATE_NAME = "spec-extract-directives";

    /** Template business domain of this family (tp_prompt_template domain). */
    public static final String TEMPLATE_DOMAIN = "spec-extract";

    /**
 * grouped-intake door (tri-state, factory on): open = over-count
 * chunk sets are sealed into token-bounded groups through the shared
 * planner instead of being rejected; only an explicit 0/false restores
 * the legacy cost-gate rejection face byte-for-byte.
 */
    public static final String KEY_GROUPED_INTAKE_ENABLED =
            AiSpecExtractMessage.KEY_GROUPED_INTAKE_ENABLED;

    /** A3 namespace rule: this door's grouping caps live under its own
     *  governance prefix and never borrow the ai.doc.* rows. */
    static final String GROUP_KEY_PREFIX = "apitest.spec-extract.";

    private final CoreGateway coreGateway;
    private final LlmFallbackChain llmFallbackChain;
    private final ProjectLlmRouter projectLlmRouter;
    private final SpecDraftItemsParser itemsParser;
    /** Shared fidelity ledger store (; null or a failing door never
 * fails the extraction run — bookkeeping is fail-soft by contract). */
    private final ExtractFidelityLedgerStore fidelityStore;

    public SpecDraftExtractServiceImpl(CoreGateway coreGateway, LlmFallbackChain llmFallbackChain,
                                       ProjectLlmRouter projectLlmRouter, ObjectMapper objectMapper,
                                       ExtractFidelityLedgerStore fidelityStore) {
        this.coreGateway = coreGateway;
        this.llmFallbackChain = llmFallbackChain;
        this.projectLlmRouter = projectLlmRouter;
        this.itemsParser = new SpecDraftItemsParser(objectMapper);
        this.fidelityStore = fidelityStore;
    }

    /** Kind token of the channel-B spec extraction inside tp_ai_task_event
     *  (writer side of the tail window; the core read door mirrors it). */
    private static final String EVENT_KIND_SPEC_EXTRACT = "spec-extract";

    /** Detail serialisation mapper (bookkeeping-only, no shared state). */
    private static final ObjectMapper EVENT_DETAIL_MAPPER = new ObjectMapper();

    /** segment C1: optional process event ledger door (null = legacy
 * zero-report face, pipeline byte-identical — mirrors the doc-extract
 * writer seam, R40 single shared ledger). A live store books
 * start/progress/done/error frames per attempt so the wizard's tail
 * drawer replays the run that actually executes (R62: the user never
 * has to refresh to see what a long job is doing). */
    private com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore
            processEventStore;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProcessEventStore(
            com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore store) {
        this.processEventStore = store;
    }

    /** Append one frame, fail-soft on every face: absent door = silent
     *  no-op (legacy wiring), a serialisation hiccup degrades the detail
     *  blob to "{}" but still lands the human summary — progress
     *  bookkeeping must never fail the extraction it observes (the store
     *  door itself is already fail-soft). */
    private void recordProcessEventQuiet(long documentId, String runToken, String level,
                                         String summary, Map<String, Object> detail) {
        if (processEventStore == null || runToken == null || runToken.isBlank()) {
            return;
        }
        String detailJson;
        try {
            detailJson = EVENT_DETAIL_MAPPER.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException bad) {
            detailJson = "{}";
        }
        processEventStore.record(EVENT_KIND_SPEC_EXTRACT, String.valueOf(documentId),
                runToken, level, summary, detailJson);
    }

    @Override
    public void extract(AiSpecExtractMessage message) {
        // segment C1: one fresh run token per attempt — the ledger rows
        // carry it and the observation door resolves the newest run from
        // the ledger itself (channel B keeps no task-state row). A failed
        // attempt lands exactly one error frame with the honest human
        // reason, then rethrows untouched: MQ retry -> dead-letter
        // semantics stay byte-identical.
        String runToken = UUID.randomUUID().toString();
        try {
            runExtraction(message, runToken);
        } catch (RuntimeException failure) {
            String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            recordProcessEventQuiet(message.documentId(), runToken, "error",
                    "接口文档抽取失败|" + reason, Map.of("phase", "failed"));
            throw failure;
        }
    }

    private void runExtraction(AiSpecExtractMessage message, String runToken) {
        long documentId = message.documentId();
        LlmEndpointConfig endpoint = projectLlmRouter.route(message.projectId());
        String model = message.modelHint() == null || message.modelHint().isBlank()
                ? endpoint.modelName() : message.modelHint();
        boolean modelPinned = message.modelHint() != null && !message.modelHint().isBlank();

        List<Map<String, Object>> chunks = coreGateway.getDocumentChunks(documentId);
        if (chunks == null || chunks.isEmpty()) {
            throw new ServiceException("文档没有可用分块，接口清单抽取失败，请确认文档解析成功后重试",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        boolean grouped = ChatBehaviorDirectives.switchOn(
                coreGateway.getSysConfig(KEY_GROUPED_INTAKE_ENABLED));
        ContextBudgetPlanner planner = null;
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        if (grouped) {
            planner = ContextBudgetPlanner.intakePlanner(
                    ModelAdaptiveBudget.effectiveTokens(endpoint.promptLimitTokens(),
                            coreGateway.getSysConfig(ModelAdaptiveBudget.KEY_SAFETY_FACTOR)),
                    endpoint.tokenizerFamily(), model, coreGateway::getSysConfig,
                    GROUP_KEY_PREFIX);
            groups = planner.planGroupsByTokens(chunks);
        } else {
            int maxChunks = SysConfigInts.positiveInt(
                    coreGateway.getSysConfig(KEY_MAX_CHUNKS), DEFAULT_MAX_CHUNKS);
            if (chunks.size() > maxChunks) {
                throw new ServiceException("文档分块数 " + chunks.size() + " 超过单次抽取上限 "
                        + maxChunks + "（系统设置-" + KEY_MAX_CHUNKS + " 可调），请拆分文档后重试",
                        BizErrorCode.AI_FORMAT_ERROR);
            }
            for (Map<String, Object> chunk : chunks) {
                groups.add(List.of(chunk));
            }
        }

        log.info("通道 B 抽取开始 documentId={} projectId={} model={} chunks={} groups={} grouped={}",
                documentId, message.projectId(), model, chunks.size(), groups.size(), grouped);
        recordProcessEventQuiet(documentId, runToken, "step",
                "接口文档抽取已发起|分块 " + chunks.size() + " 组 " + groups.size(),
                Map.of("chunks", chunks.size(), "groups", groups.size(), "grouped", grouped));
        LlmFallbackNotice notice = new LlmFallbackNotice(message.operatorId(),
                message.projectId(), "接口文档抽取任务#" + documentId);
        // Head assembled once per run (fresh read, uncached): every chunk of
        // one extraction shares the same behaviour-directive head.
        String systemHead = behaviorDirectivesHead();
        ExtractFidelityMeter meter = new ExtractFidelityMeter();
        List<Map<String, Object>> flat = new ArrayList<>();
        LinkedHashMap<String, Map<String, Object>> merged = new LinkedHashMap<>();
        int estimateTokens = 0;
        int rejectedTotal = 0;
        try {
            // wiring (R40): the group bodies ride the
            // single-source GroupParallelFanout — no fourth pool face in this
            // repo. K8 reads fresh per run: ai.pipeline.group-parallel,
            // hard cap 3. The ledger face (method+path first-seen merge,
            // rejected accounting, token estimate) is replayed strictly in
            // group order AFTER collection, so parallelism never leaks into
            // the bookkeeping (zero drift against the serial spectrum).
            int groupParallel = GroupParallelFanout.parallelismOf(
                    coreGateway.getSysConfig(GroupParallelFanout.KEY_GROUP_PARALLEL));
            // one run-level cooperative-cancel signal for the
            // whole group fan-out. The first provider-timeout family failure
            // trips it BEFORE rethrowing untouched (fail-closed, original
            // vocabulary zero drift), so the fan-out never launches an
            // unstarted sibling and the waiting caller stops within one poll
            // instead of riding every remaining group to its own clamp death.
            RunBudgetAbort budgetAbort = new RunBudgetAbort();
            final int totalGroups = groups.size();
            List<java.util.concurrent.Callable<SpecDraftParseOutcome>> groupTasks =
                    new ArrayList<>(groups.size());
            for (int gi = 0; gi < groups.size(); gi++) {
                List<Map<String, Object>> group = groups.get(gi);
                int groupSeq = gi + 1;
                groupTasks.add(() -> {
                    try {
                        SpecDraftParseOutcome outcome = extractGroupCall(model, modelPinned,
                                message, notice, group, systemHead, meter);
                        // C1: one live frame per finished group, landing
                        // in real completion order — the tail drawer follows
                        // these while the fan-out is still in flight.
                        recordProcessEventQuiet(documentId, runToken, "step",
                                "接口文档抽取进行中|第 " + groupSeq + "/" + totalGroups + " 组完成",
                                Map.of("group", groupSeq, "items", outcome.items().size()));
                        return outcome;
                    } catch (RuntimeException failure) {
                        if (LlmTimeouts.isTimeoutFamily(failure)) {
                            budgetAbort.trip("组 " + groupSeq + " 检出模型调用超时");
                        }
                        throw failure;
                    }
                });
            }
            List<SpecDraftParseOutcome> outcomes = GroupParallelFanout.runAll(
                    groupTasks, groupParallel, "spec-group-extract", budgetAbort);
            for (int i = 0; i < groups.size(); i++) {
                SpecDraftParseOutcome outcome = outcomes.get(i);
                rejectedTotal += outcome.rejectedCount();
                for (Map<String, Object> item : outcome.items()) {
                    if (grouped) {
                        // Idempotent merge across groups: method+path is the
                        // draft identity, first occurrence wins.
                        merged.putIfAbsent(mergeKeyOf(item), item);
                    } else {
                        flat.add(item);
                    }
                }
                for (Map<String, Object> chunk : groups.get(i)) {
                    estimateTokens += planner == null
                            ? String.valueOf(chunk.get("content")).length()
                            : planner.tokensOf(chunk);
                }
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // a gate-stop attributes as an honest timeout
            // (the per-call ceiling lives on the global face, the spec door
            // owns no domain timeout key — A3 forbids borrowing
            // ai.doc.*), never as the generic format-error wording.
            if (e instanceof GroupFanoutAbortException) {
                throw new ServiceException("接口清单抽取的模型调用超时，已停止未开始的分组"
                        + "（单调用时限 llm.timeout-seconds，可在系统设置调整），请重试或更换模型",
                        BizErrorCode.AI_TIMEOUT);
            }
            throw new ServiceException("接口清单抽取服务异常: " + e.getMessage(),
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        List<Map<String, Object>> drafts = grouped ? new ArrayList<>(merged.values()) : flat;
        if (drafts.isEmpty()) {
            // All entries malformed keeps the refusal (anti-overwrite guard)
            // but names the real cause — rejected path-less entries — so the
            // dead-letter row and the operator message stay honest.
            if (rejectedTotal > 0) {
                throw new ServiceException("模型返回的 " + rejectedTotal
                        + " 条接口条目均缺少路径(path)，无法作为草稿保存；请重试或更换模型",
                        BizErrorCode.AI_FORMAT_ERROR);
            }
            throw new ServiceException("文档中未识别到接口定义，请确认上传的是接口设计文档",
                    BizErrorCode.AI_FORMAT_ERROR);
        }
        if (rejectedTotal > 0) {
            log.warn("通道 B 抽取丢弃缺少路径的条目 documentId={} 丢弃数={} 有效条目照常回写",
                    documentId, rejectedTotal);
        }
        recordFidelityQuiet(documentId, chunks, groups.size(), meter, drafts, planner,
                estimateTokens);
        coreGateway.writeSpecDrafts(documentId, message.operatorId(), drafts);
        recordProcessEventQuiet(documentId, runToken, "step",
                "接口文档抽取完成|接口 " + drafts.size() + " 个",
                Map.of("interfaces", drafts.size(), "groups", groups.size()));
        log.info("通道 B 抽取完成 documentId={} 组数={} 条目数={}",
                documentId, groups.size(), drafts.size());
        recallDensityCheck(documentId, drafts.size(), estimateTokens);
    }

    /** piece 2: per-door recall-density floor hot key (band 0-50,
 * 0 = check off); the decision arithmetic itself is single-sourced in
 * {@link RecallDensityGate}, this door keeps key, factory default and
 * its own WARN face. */
    static final String KEY_RECALL_DENSITY_FLOOR =
            "ai.spec.recall-density-floor-per-ktoken";
    static final double DEFAULT_RECALL_DENSITY_FLOOR = 11.8;

    /** WARN one honest numbers line when the run came back with fewer
     *  drafts than the floor demands for the tokens it was shown; a met or
     *  switched-off floor stays fully silent. */
    private void recallDensityCheck(long documentId, int itemCount, int estimatedTokens) {
        double floor = RecallDensityGate.parseFloor(
                coreGateway.getSysConfig(KEY_RECALL_DENSITY_FLOOR),
                DEFAULT_RECALL_DENSITY_FLOOR);
        if (RecallDensityGate.missed(floor, itemCount, estimatedTokens)) {
            log.warn("通道 B 密度下限闸 documentId={} 实收={} 条数下限={} 源token={}",
                    documentId, itemCount,
                    RecallDensityGate.expectedItems(estimatedTokens, floor), estimatedTokens);
        }
    }

    /** Grouped extraction call: one LLM round per token-bounded group (the
     *  closed-door legacy face passes single-chunk groups, keeping the old
     *  per-chunk prompt byte-identical); entries pinned channel=B, provider
     *  usage booked at the seam for the fidelity row. Confidence re-judging
     *  stays single-sourced in core. */
    private SpecDraftParseOutcome extractGroupCall(String model, boolean modelPinned,
                                                   AiSpecExtractMessage message,
                                                   LlmFallbackNotice notice,
                                                   List<Map<String, Object>> group,
                                                   String systemHead,
                                                   ExtractFidelityMeter meter) {
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", systemHead),
                new ChatMessage("user", buildGroupPrompt(group)));
        LlmChatResult result = llmFallbackChain.chat(new ChatCompletionRequest(model, messages,
                null, null), message.projectId(), notice, modelPinned);
        meter.record(EXTRACT_MARKER, result.promptTokens(), result.completionTokens());
        SpecDraftParseOutcome parsed = itemsParser.parse(result.content());
        List<Map<String, Object>> pinned = new ArrayList<>(parsed.items().size());
        for (Map<String, Object> item : parsed.items()) {
            Map<String, Object> entry = new LinkedHashMap<>(item);
            entry.put("channel", "B");
            pinned.add(entry);
        }
        return new SpecDraftParseOutcome(pinned, parsed.rejectedCount());
    }

    /** Group user prompt = every block's chapter path plus body, numbered.
     *  A single-block group reuses the legacy single-chunk face verbatim so
     *  closing the door never perturbs the prompt shape. */
    private String buildGroupPrompt(List<Map<String, Object>> group) {
        if (group.size() == 1) {
            Map<String, Object> only = group.get(0);
            return buildUserPrompt(only.get("chapter"),
                    String.valueOf(only.get("content")));
        }
        StringBuilder sb = new StringBuilder("请从以下文档分块中抽取接口定义：\n");
        int index = 1;
        for (Map<String, Object> chunk : group) {
            Object chapterRaw = chunk.get("chapter");
            String chapter = chapterRaw == null ? "" : String.valueOf(chapterRaw).trim();
            sb.append("分块").append(index++);
            if (!chapter.isEmpty()) {
                sb.append("（章节：").append(chapter).append("）");
            }
            sb.append("：\n正文：").append(String.valueOf(chunk.get("content"))).append("\n\n");
        }
        return sb.toString();
    }

    /** Cross-group draft identity: method+path (first occurrence wins). The
     *  parser guarantees a non-blank path on every kept entry, so each entry
     *  is always keyable and no unkeyed fallback is needed. */
    private static String mergeKeyOf(Map<String, Object> item) {
        String method = String.valueOf(item.getOrDefault("method", "")).trim();
        String path = String.valueOf(item.getOrDefault("path", "")).trim();
        return method + " " + path;
    }

    /** : book one fidelity row per generation through the shared
 * ledger store (fail-soft — bookkeeping never fails the extraction
 * run; a null store simply skips the row). */
    private void recordFidelityQuiet(long documentId, List<Map<String, Object>> chunks,
                                     int nodeCount, ExtractFidelityMeter meter,
                                     List<Map<String, Object>> items,
                                     ContextBudgetPlanner planner, int estimateTokens) {
        if (fidelityStore == null) {
            return;
        }
        try {
            int sourceTokens = 0;
            if (planner != null) {
                sourceTokens = (int) planner.totalTokensOf(chunks);
            } else {
                for (Map<String, Object> chunk : chunks) {
                    sourceTokens += String.valueOf(chunk.get("content")).length();
                }
            }
            int anchored = 0;
            for (Map<String, Object> item : items) {
                if (item.get("sourceAnchor") instanceof String anchor && !anchor.isBlank()) {
                    anchored++;
                }
            }
            int distinctChapters = (int) chunks.stream()
                    .map(chunk -> String.valueOf(chunk.getOrDefault("chapter", "")))
                    .distinct().count();
            fidelityStore.upsertIfChanged(ExtractFidelityLedger.entry(documentId,
                    UUID.randomUUID().toString(), ExtractFidelityLedger.TASK_SPEC_EXTRACT,
                    sourceTokens, chunks.size(), nodeCount, distinctChapters, meter,
                    items.size(), anchored, estimateTokens,
                    new PlaneLabels(EXTRACT_MARKER, EXTRACT_MARKER + ":summary",
                            EXTRACT_MARKER + ":gap")));
        } catch (Exception e) {
            // : readable reject through the single-source note; a booked
            // account that silently vanished is what hid the qa-gen gap.
            log.warn(ExtractFidelityLedger.rejectNote(
                    ExtractFidelityLedger.TASK_SPEC_EXTRACT, documentId, "本轮随机令牌", e));
        }
    }

    /**
 * Behaviour-kernel head for one extraction run , R40: gate/
 * read/degrade sequence single-sourced in the assembly piece). Reads are
 * fresh per run (uncached, so a template edit lands on the next extraction
 * task); a failing read degrades to the conservative contract and never
 * takes the run down.
 */
    String behaviorDirectivesHead() {
        return BehaviorKernelAssembly.assembleHead(
                coreGateway.getSysConfig(KEY_DIRECTIVES_ENABLED),
                () -> coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME,
                        TEMPLATE_DOMAIN, ChatBehaviorDirectives.PUBLISHED_STATUS),
                buildSystemPrompt() + recallSegment(),
                "行为指令模板读路径不可用，本轮抽取改用内置指令模板");
    }

    /**
 * per-domain hot switch for the exhaustive-recall clause block
 * on the spec-contract extraction face (fail-soft ON, read single-
 * sourced in RecallDirectiveSwitch; the published template row owns its
 * own instruction text, the block rides the built-in contract tail).
 */
    public static final String KEY_RECALL_DIRECTIVE_ENABLED = "ai.spec.recall-directive.enabled";

    /** Recall segment appended to the built-in contract (zero bytes off). */
    String recallSegment() {
        return RecallDirectiveSwitch.segment(
                coreGateway.getSysConfig(KEY_RECALL_DIRECTIVE_ENABLED), KEY_RECALL_DIRECTIVE_ENABLED);
    }

    /**
 * Kept as the conservative fallback head; the published template row, when
 * present, replaces this text and must therefore retain the marker and
 * output-schema contract (seeded row below carries it verbatim). */
    private String buildSystemPrompt() {
        return "你是接口设计文档抽取器（" + EXTRACT_MARKER + "）。"
                + "从给定文档分块中抽取接口定义条目，输出 JSON：\n"
                + "{\"items\":[{\"method\":\"GET|POST|PUT|DELETE|PATCH\",\"path\":\"/...\",\n"
                + "\"title\":\"接口名\",\"description\":\"用途说明\",\"tags\":[\"分组名\"],\n"
                + "\"requestContentType\":\"application/json 等，可空\",\n"
                + "\"paramsSkeletonJson\":{\"查询/路径参数名\":{\"example\":\"示例值\"}}，可空,\n"
                + "\"bodySkeleton\":{\"请求体结构示例\"}，可空,\n"
                + "\"assertionsJson\":[{\"type\":\"status\",\"expected\":\"200\"}]，可空,\n"
                + "\"securityRefs\":[\"凭据键名\"],可空,\"authHintJson\":可空,\n"
                + "\"sourceAnchor\":\"该接口在原文中的连续原文片段（必填，逐字引用）\"}]}\n"
                + "硬性约束：只抽取分块文本中真实存在的接口，绝不发明文档外接口；"
                + "没有接口就输出 {\"items\":[]}；sourceAnchor 必须是原文逐字片段。";
    }

    /**
     * User prompt = chunk body plus its chapter path (A3 root cause: endpoint
     * method/path frequently live only in the heading line, which the splitter
     * keeps as chunk metadata — omitting it makes extraction physically
     * impossible and the all-empty guard misfires). A missing or blank chapter
     * keeps the prompt body-only; the literal "null" is never injected. The MQ
     * message never carries chunk bodies — they are fetched fresh at consumption
     * time (known pitfall a), restated here at single-chunk granularity.
     */
    private String buildUserPrompt(Object chapterRaw, String chunkContent) {
        String chapter = chapterRaw == null ? "" : String.valueOf(chapterRaw).trim();
        String head = "请从以下文档分块中抽取接口定义：\n";
        return chapter.isBlank()
                ? head + "正文：" + chunkContent
                : head + "章节：" + chapter + "\n正文：" + chunkContent;
    }
}
