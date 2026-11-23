package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatFailureWording;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.ChatRepairDraft;
import com.sixbean.testpike.aiservice.aichat.domain.component.ConversationAssemblyRequest;
import com.sixbean.testpike.aiservice.aichat.domain.component.ConversationContextAssembler;
import com.sixbean.testpike.aiservice.aichat.domain.component.DialogHistoryWindow;
import com.sixbean.testpike.aiservice.llm.pipeline.RedactGate;
import com.sixbean.testpike.aiservice.aichat.domain.component.SessionPinnedFacts;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureDiagnosis;
import com.sixbean.testpike.aiservice.aichat.domain.component.TimeTravelContext;
import com.sixbean.testpike.aiservice.aichat.domain.component.FailureScreenshotEvidence;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedger;
import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanReconciler;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingCitation;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.aichat.domain.component.RefResolution;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.tool.domain.component.FrameLedgerStreamSink;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;

/**
 * Dialog-run orchestration on the ai-service side (AO batch γ
 * step C): one broker message = one streaming ReAct turn. The answer streams
 * through {@link FrameLedgerStreamSink} into the core replayable frame ledger,
 * so the admin SSE bridge, a Last-Event-ID reconnect and the abort command all
 * read exactly what the live client saw.
 * <p>Honesty contract (the frame protocol's "never leave the user waiting"
 * adjudication):
 * <ul>
 * <li>execution switch {@code ai.chat.exec-enabled} off → the turn is refused
 * with an error frame that names the switch (never a silently swallowed
 * message — the user asked, the ledger must say why nothing happened);</li>
 * <li>provider/chain failure → an error frame carrying the human-readable
 * reason, turn sealed as {@code error};</li>
 * <li>the done frame (usage numbers) is appended BEFORE the seal, because a
 * closed turn cannot gain frames — usage belongs to the answer, not to the
 * billing side-channel;</li>
 * <li>a ledger write failure is NOT swallowed: output that could not be
 * journaled surfaces to the container retry, then the dead-letter queue
 * keeps the run replayable (fail-closed).</li>
 * </ul>
 * <p>Grounding : before the model is called, {@link
 * GroundingService} assembles help-chapter + knowledge candidates into one
 * outcome; the turn journals a state frame (hit count, honest zero-hit line)
 * plus one cite frame per source, and the context block rides at the tail of
 * the system message with the matching posture instruction. A retrieval source
 * failing drops candidates, never the turn.
 * <p>Scope of this segment: single-turn streaming with the shared FC tool loop
 * (rounds/refusals journaled as trace frames by the sink). Multi-turn context
 * assembly is the AP batch's job and plugs in at {@link #buildRequest}.
 */
@Service
public class ChatRunOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatRunOrchestrator.class);

    /** Execution switch of the dialog kernel (K8, factory default on per R32). */
    static final String KEY_EXEC_ENABLED = "ai.chat.exec-enabled";
    /**
 * Safety cap of the autonomous goal-plan continuation : how many
 * extra batches one turn may pick up on its own before it stops and
 * reports honestly. K8 hot key, factory default 3, valid range 1-10 —
 * a garbage or out-of-range box falls back to the default so a typo can
 * never unlock an endless self-feeding loop.
 */
    static final String KEY_GOAL_MAX_AUTO_BATCHES = "ai.chat.goal.max-auto-batches";
    static final int GOAL_AUTO_BATCH_DEFAULT = 3;
    static final int GOAL_AUTO_BATCH_MIN = 1;
    static final int GOAL_AUTO_BATCH_MAX = 10;

    /**
     * Repair-draft switch (K8, V300, factory default on — a draft never
     * lands anything, so there is no production-change exemption here). When
     * off, a badge-triggered question still gets its answer, plus one honest
     * state frame naming the setting path and the key (R32 guidance).
     */
    static final String KEY_REPAIR_DRAFT_ENABLED = "ai.chat.repair-draft.enabled";

    /**
     * Case-write draft switch (K8, V303, factory default on — a draft card
     * lands nothing until the human confirms through the shared door). Kept
     * separate from the repair key: the two features have independent cost
     * faces (an extra LLM call vs. a pure transcript projection) and closing
     * one must not silence the other. Off → a case-write tool call is still
     * refused from executing, the turn gets one honest state frame naming the
     * setting path and the key (R32 guidance) instead of a draft card.
     */
    static final String KEY_CASE_DRAFT_ENABLED = "ai.chat.case-draft.enabled";

    /**
     * Tag-write draft switch (K8, V311, factory default on — same posture as
     * the case key: a card lands nothing by itself). One key per draft domain
     * so an operator can silence exactly one feature (R32: the refusal line
     * names the key that actually governs the refused card).
     */
    static final String KEY_TAG_DRAFT_ENABLED = "ai.chat.tag-draft.enabled";

    /** Dialog-side switch for the interface pass-through change card (K8, factory default on). */
    static final String KEY_API_ACCESS_WRITE_DRAFT_ENABLED = "ai.chat.api-access-write-draft.enabled";

    /**
     * Prompt-template draft switch (K8, V312, factory default on — same
     * posture as the tag key: one key per draft domain, refusal names exactly
     * the key that governs the refused card).
     */
    static final String KEY_PPT_DRAFT_ENABLED = "ai.chat.prompttpl-draft.enabled";

    /**
     * Skill-draft switch (K8, V313, factory default on — same posture as the
     * tag and prompt-template keys: one key per draft domain, refusal names
     * exactly the key that governs the refused card).
     */
    static final String KEY_SKILL_DRAFT_ENABLED = "ai.chat.skill-draft.enabled";

    /**
 * Review-decision draft switch (tool-surface clearing batch stage 3c):
 * one key per dialog-write domain, factory-on, off = honest guidance frame.
 */
    static final String KEY_REVIEW_DRAFT_ENABLED = "ai.chat.review-draft.enabled";
    /**
     * Draft kind tokens (mirror of core DraftObjectType wire names).
     */
    static final String DRAFT_KIND_CASE_WRITE = "CASE_WRITE";
    /**
 * Case-rollback proposal kind . Its own registry
 * token — the emitter routes the card to CaseRollbackDraftStrategy, which
 * replays through the shared CaseService.rollback chain — while the
 * kill-switch stays the existing case key: rollback is a case write, so
 * no second default-value source (R40).
 */
    static final String DRAFT_KIND_CASE_ROLLBACK = "CASE_ROLLBACK";
    static final String DRAFT_KIND_TAG_WRITE = "TAG_WRITE";
    static final String DRAFT_KIND_API_ACCESS_WRITE = "API_ACCESS_WRITE";
    static final String DRAFT_KIND_PPT_WRITE = "PROMPT_TPL_WRITE";
    static final String DRAFT_KIND_SKILL_WRITE = "SKILL_WRITE";
    static final String DRAFT_KIND_CASE_REVIEW = "CASE_REVIEW";
    static final String DRAFT_KIND_PLAN_WRITE = "PLAN_WRITE";
    static final String DRAFT_KIND_BATCH_WRITE = "BATCH_WRITE";
    static final String DRAFT_KIND_REPORT_WRITE = "REPORT_WRITE";
    static final String DRAFT_KIND_APITEST_WRITE = "APITEST_WRITE";
    static final String DRAFT_KIND_APISPEC_WRITE = "APISPEC_WRITE";
    static final String DRAFT_KIND_PERF_LAUNCH = "PERF_LAUNCH";
    static final String DRAFT_KIND_RESERVATION_WRITE = "RESERVATION_WRITE";
    static final String DRAFT_KIND_POPUP_RULE_LEARN = "POPUP_RULE_LEARN";
    static final String DRAFT_KIND_MOCK_SPACE_WRITE = "MOCK_SPACE_WRITE";
    static final String DRAFT_KIND_MOCK_STUB_WRITE = "MOCK_STUB_WRITE";
    static final String DRAFT_KIND_AIGEN_BATCH_WRITE = "AIGEN_BATCH_WRITE";
    static final String DRAFT_KIND_DEFECT_WRITE = "DEFECT_WRITE";
    static final String DRAFT_KIND_DATA_FACTORY_WRITE = "DATA_FACTORY_WRITE";
    static final String DRAFT_KIND_EXPLORE_CREATE = "EXPLORE_CREATE";
    static final String DRAFT_KIND_FLAKY_ISOLATION = "FLAKY_ISOLATION";
    static final String DRAFT_KIND_REQUIREMENT_WRITE = "REQUIREMENT_WRITE";

    /** Checked-subset login-state registration card (screenshot batch piece D). */
    static final String DRAFT_KIND_LOGIN_STATE_WRITE = "LOGIN_STATE_WRITE";

    /**
 * Plan-draft switch (tool-surface clearing batch stage 4a): one key
 * per dialog-write domain, factory-on, off = honest guidance frame.
 */
    static final String KEY_PLAN_DRAFT_ENABLED = "ai.chat.plan-draft.enabled";

    /**
 * Batch-draft switch (tool-surface clearing batch stage 4b): same
 * one-key-per-domain shape as every other write draft.
 */
    static final String KEY_BATCH_DRAFT_ENABLED = "ai.chat.batch-draft.enabled";

    /**
 * Report-draft switch (tool-surface clearing batch stage 4c): same
 * one-key-per-domain shape as every other write draft.
 */
    static final String KEY_REPORT_DRAFT_ENABLED = "ai.chat.report-draft.enabled";

    /**
 * Api-test defaults draft switch (tool-surface clearing batch stage
 * 5a): same one-key-per-domain shape as every other write draft.
 */
    static final String KEY_APITEST_DRAFT_ENABLED = "ai.chat.apitest-draft.enabled";

    /**
 * Api-inventory contract-pull draft switch (tool-surface clearing batch
 * stage 5b): tenth per-domain key — the pair table moves from
 * Map.of (hard cap 10) to Map.ofEntries here so later stages add pairs
 * freely.
 */
    static final String KEY_APISPEC_DRAFT_ENABLED = "ai.chat.apispec-draft.enabled";

    /**
 * Perf-launch draft switch (tool-surface clearing batch stage 5c):
 * eleventh per-domain key. Off = the launch proposal executes nothing and
 * journals one guidance frame naming this key — the dialog never presses
 * an environment behind the operator's back.
 */
    static final String KEY_PERF_DRAFT_ENABLED = "ai.chat.perf-draft.enabled";

    /**
 * Device-reservation draft switch (tool-surface clearing batch
 * stage 6a): twelfth per-domain key. Off = the reservation proposal
 * executes nothing and journals one guidance frame naming this key — the
 * dialog never books a device behind the operator's back.
 */
    static final String KEY_RESERVATION_DRAFT_ENABLED = "ai.chat.reservation-draft.enabled";

    /**
 * Popup-rule-learning draft switch (tool-surface clearing batch
 * stage 6b): thirteenth per-domain key. Off = the learning proposal
 * executes nothing and journals one guidance frame naming this key — the
 * dialog never asks to spend an LLM call on frame recognition behind
 * the operator's back. Note this key only governs the CARD; the domain
 * learning switch ai.appauto.popup-rule-learn still owns the actual
 * gate and refuses landing in plain words when it is closed.
 */
    static final String KEY_POPUP_RULE_DRAFT_ENABLED = "ai.chat.popup-rule-draft.enabled";

    /**
 * Mock-space create draft switch (tool-surface clearing batch
 * stage 7): fourteenth per-domain key. Off = the space proposal executes
 * nothing and journals one guidance frame naming this key — the dialog
 * never opens a site-wide routing handle behind the operator's back.
 */
    static final String KEY_MOCK_SPACE_DRAFT_ENABLED = "ai.chat.mock-space-draft.enabled";

    /**
 * Mock-stub draft switch (tool-surface clearing batch stage 8): one
 * key per domain lets an operator silence exactly the endpoint-rule
 * cards. Off-state guidance names this key verbatim (R32).
 */
    static final String KEY_MOCK_STUB_DRAFT_ENABLED = "ai.chat.mock-stub-draft.enabled";

    /**
 * AI-generation batch draft switch (tool-surface clearing batch
 * stage 9): one key per domain lets an operator silence exactly the
 * case-generation submit cards. Off-state guidance names this key
 * verbatim (R32).
 */
    static final String KEY_AIGEN_BATCH_DRAFT_ENABLED = "ai.chat.aigen-batch-draft.enabled";

    /**
 * Defect-library draft switch (tool-surface clearing batch stage
 * 10): one key per domain lets an operator silence exactly the defect
 * write cards. Off-state guidance names this key verbatim (R32).
 */
    static final String KEY_DEFECT_DRAFT_ENABLED = "ai.chat.defect-draft.enabled";

    /**
 * Data-factory draft switch (tool-surface clearing batch stage
 * 11): one key per domain lets an operator silence exactly the
 * data-factory script write cards. Off-state guidance names this key verbatim (R32).
 */
    static final String KEY_DATA_FACTORY_DRAFT_ENABLED = "ai.chat.datafactory-draft.enabled";

    /**
 * Exploration draft switch (tool-surface clearing batch stage
 * 12): one key per domain lets an operator silence exactly the
 * explore-session cards. Off-state guidance names this key verbatim (R32).
 */
    static final String KEY_EXPLORE_DRAFT_ENABLED = "ai.chat.explore-draft.enabled";

    /**
 * Flaky-isolation draft switch : factory
 * default ON (V337, R32); off-state guidance names this key verbatim.
 */
    static final String KEY_FLAKY_DRAFT_ENABLED = "ai.chat.flaky-draft.enabled";

    /**
 * Requirement-write draft switch : one key per domain lets
 * an operator silence exactly the requirement-point cards. Off-state
 * guidance names this key verbatim (R32); factory default ON (V329 seed,
 * live presence asserted by the requirement-write tool seed test).
 */
    static final String KEY_REQUIREMENT_DRAFT_ENABLED = "ai.chat.requirement-draft.enabled";

    /**
 * Login-state registration draft switch (screenshot capture batch
 * piece D): silences exactly the checked-subset registration cards.
 * Off-state guidance names this key verbatim (R32); factory default ON
 * (V518 seed). Literal lives on the stager (R40 single source — the
 * screenshot parse leg reads the same switch before any OCR call).
 */
    static final String KEY_LOGIN_STATE_DRAFT_ENABLED =
            com.sixbean.testpike.aiservice.devtoolspanel.domain.service
                    .ScreenshotLoginStateStager.KEY_LOGIN_STATE_GATE;

    /**
     * Draft-kind → its own switch key (single source). A kind missing from
     * this table is treated as ON by its emitter and OFF never silently:
     * unknown kinds are filtered earlier by the registry routing, so this map
     * only carries the K8 governance of kinds the emitter can produce.
     */
    private static final java.util.Map<String, String> DRAFT_SWITCH_KEYS = java.util.Map.ofEntries(
            java.util.Map.entry(DRAFT_KIND_CASE_WRITE, KEY_CASE_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_CASE_ROLLBACK, KEY_CASE_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_TAG_WRITE, KEY_TAG_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_PPT_WRITE, KEY_PPT_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_SKILL_WRITE, KEY_SKILL_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_CASE_REVIEW, KEY_REVIEW_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_PLAN_WRITE, KEY_PLAN_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_BATCH_WRITE, KEY_BATCH_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_REPORT_WRITE, KEY_REPORT_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_APITEST_WRITE, KEY_APITEST_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_APISPEC_WRITE, KEY_APISPEC_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_PERF_LAUNCH, KEY_PERF_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_RESERVATION_WRITE, KEY_RESERVATION_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_POPUP_RULE_LEARN, KEY_POPUP_RULE_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_MOCK_SPACE_WRITE, KEY_MOCK_SPACE_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_MOCK_STUB_WRITE, KEY_MOCK_STUB_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_AIGEN_BATCH_WRITE, KEY_AIGEN_BATCH_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_DEFECT_WRITE, KEY_DEFECT_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_DATA_FACTORY_WRITE, KEY_DATA_FACTORY_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_EXPLORE_CREATE, KEY_EXPLORE_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_FLAKY_ISOLATION, KEY_FLAKY_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_REQUIREMENT_WRITE, KEY_REQUIREMENT_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_LOGIN_STATE_WRITE, KEY_LOGIN_STATE_DRAFT_ENABLED),
            java.util.Map.entry(DRAFT_KIND_API_ACCESS_WRITE, KEY_API_ACCESS_WRITE_DRAFT_ENABLED));

    /** Conservative fallback head : kept verbatim for absent/off/broken reads. */
    static final String SYSTEM_PROMPT =
            "你是 testPike 的在线 AI 助手，基于平台数据与知识库回答测试工作问题。"
                    + "不确定的内容直说并指路，绝不编造；写操作只会先出草案，需用户确认后才落地。";

    /** Appended when grounding found material: the answer must lean on the [C] block. */
    private static final String GROUNDED_SUFFIX =
            "\n仅据文末 [C] 材料作答平台事实，并在回答中标注来源编号（如 [C1]）；"
                    + "材料覆盖不到的部分直说不确定，不得当成事实陈述。"
                    + "事实类结论附「截至 X 时点」小标，时点取参考材料末尾的【数据时刻】行；"
                    + "材料未记录时点时如实说明时点未知，不得虚构时间。\n";

    /** Appended when grounding hit nothing: plain "I don't know" beats an improvised fact. */
    private static final String ZERO_HIT_SUFFIX =
            "\n本次检索没有命中可靠材料：涉及平台事实时直说不知道并指路（帮助文档/知识库），"
                    + "绝不编造来源或平台行为。\n";

    /** Appended when chips are pinned: the answer must address the [R] objects head-on. */
    private static final String REFS_SUFFIX =
            "\n用户在本问题中钉住了文末「引用对象」列出的平台记录（[R] 开头）：\n"
                    + "回答必须先针对这些对象本身，需要更多细节时调用查询工具核实，不得凭空假设其内容。\n"
                    + "[R] 块已含最新版本/评审意见/失败摘要，直接依据作答禁要求用户复述。\n";

    /** Appended when a failure diagnosis is on the table: the [E] block IS the evidence. */
    private static final String DIAGNOSIS_SUFFIX =
            "\n用户从失败徽标进入本次提问，文末「失败诊断」（[E] 开头）是该执行的真实失败素材：\n"
                    + "分析必须以这些素材为依据；素材不足以定因时直说还缺什么，不得编造失败原因。\n";

    /**
     * Per-session token budget (K8, V296): once the session's accumulated
     * consumption (the same ledger the E9 cost view reads) exceeds this cap,
     * the next turn is refused with an honest error frame — never a silent
     * model call that quietly overruns the number the operator set. Absent or
     * unparsable value = unlimited (fail-open on the config read: garbage in
     * the box must not turn the dialog off for everyone).
     */
    static final String KEY_SESSION_BUDGET_TOKENS = "ai.chat.session-budget-tokens";

    /** Appended when the failure-scene screenshot was read . */
    private static final String SCENE_SUFFIX =
            "\n「截图场景观察」是视觉模型对失败现场截图的可见状态描述，属辅助证据：\n"
                    + "与失败诊断互证时使用；两者矛盾时以执行记录原文为准并向用户点名矛盾。\n";

    /** Appended when user-attached images were read . */
    private static final String USER_IMAGES_SUFFIX =
            "\n「用户图片观察」是视觉模型对用户随提问附上的图片的可见状态描述，属辅助证据：\n"
                    + "回答必须结合提问文字与图片观察；图片未能读取的条目已在块内点名，\n"
                    + "不得假装看过未读取成功的图片。\n"
                    + "每张图片附有一行〔会话句柄 …〕，需要逐字提取文字或读取表格时，\n"
                    + "请把该句柄原样传给只读贴图工具（ocr.text_extract / ocr.grid_read），不要用文件名代替。\n";

    /**
 * Write-proposal draft card ( stage 2, generalized by the
 * tool-surface clearing batch stage 2, emitter side): the dialog
 * chain NEVER executes an x-write tool — the dispatcher refuses the call
 * token-less (an unconfirmed write is always refused) and the model sees
 * that refusal
 * as its tool result. What the user gets instead is a PROPOSAL: this
 * method scans the finished ReAct transcript for a tool call the registry
 * routes to a draft kind and journals one draft frame whose meta is
 * assembled entirely server-side (the confirm door re-reads the anchors
 * from the journaled snapshot, never from a client round-trip — a forged
 * card would have no server-side anchor row to confirm against).
 * <p>Registry routing replaces the old name literals: a tool becomes
 * card-capable by carrying a draft objectType in its published exec spec,
 * with zero orchestrator change. A door failure on the registry read is
 * logged and drops the card, never the turn (the answer already streamed
 * outranks a missing projection).
 * <p>Posture ladder (mirrors emitRepairDraft):
 * <ul>
 * <li>no draft-routed write call in the transcript → nothing happens
 * (a plain Q&A turn cannot grow a card);</li>
 * <li>kind switch off → one honest state frame naming the setting path
 * and the key (R32), no card;</li>
 * <li>arguments unparsable / shape without its landing anchors → honest
 * absence: no half-shape card that would bait a confirmation click;</li>
 * <li>one valid call → exactly one draft frame BEFORE the done frame;
 * NOTHING lands — applying needs the confirm-token chain and the
 * per-kind write strategy on the core side.</li>
 * </ul>
 */
    private boolean emitWriteDraft(long projectId, long actorId, long sessionId,
                                long assistantMessageId, ReActOutcome outcome,
                                java.util.List<String> liveStagingTokens) {
        java.util.Map<String, String> kindsByTool;
        try {
            kindsByTool = new java.util.LinkedHashMap<>();
            java.util.List<com.sixbean.testpike.aiservice.aichat.domain.component.DraftToolKind>
                    registry = coreGateway.getDraftToolKinds(projectId, actorId);
            if (registry != null) {
                for (com.sixbean.testpike.aiservice.aichat.domain.component.DraftToolKind kind
                        : registry) {
                    if (kind != null && kind.hasDraftKind()) {
                        kindsByTool.put(kind.toolName(), kind.objectType());
                    }
                }
            }
        } catch (RuntimeException doorDown) {
            log.warn("草案路由门不可达，本轮不出卡不改回答 sessionId={} reason={}",
                    sessionId, doorDown.getMessage());
            return false;
        }
        if (kindsByTool.isEmpty()) {
            return false;
        }
        // One scan answers both questions: whether the transcript routed ANY
        // registry write call (the clarify-card suppression flag) and the
        // first landing-valid proposal (one card per turn — multi-write
        // batching is the batch-draft stage's job, never smuggled in here;
        // a broken argument body or an unlandable shape = honest absence,
        // never a half-shape card that would bait a confirmation click).
        boolean routedCallPresent = false;
        WriteProposal proposal = null;
        for (ChatMessage message : outcome.messages()) {
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (com.sixbean.testpike.aiservice.llm.api.dto.AssistantToolCall call
                    : message.toolCalls()) {
                String name = call.function() == null ? null : call.function().name();
                String objectType = name == null ? null : kindsByTool.get(name);
                if (objectType == null) {
                    continue;
                }
                routedCallPresent = true;
                if (proposal == null) {
                    proposal = DraftProposalShapes.toWriteProposal(
                            name, objectType, call.function().arguments(), draftBodyReader,
                            projectId, this::stagedLoginStateNames, liveStagingTokens);
                }
            }
        }
        if (proposal == null) {
            return routedCallPresent;
        }
        String switchKey = DRAFT_SWITCH_KEYS.get(proposal.objectType());
        if (switchKey != null
                && !com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans.on(
                        coreGateway.getSysConfig(switchKey))) {
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                    DenialGuidanceFrame.switchClosedAfterAttempt(
                            "AI 草案卡", "本次回答不受影响", switchKey),
                    null);
            return routedCallPresent;
        }
        coreGateway.appendChatFrame(sessionId, assistantMessageId, "draft",
                proposal.summary(), writeDraftMeta(proposal));
        return routedCallPresent;
    }

    /**
 * Piece F name-domain probe : asks core which staged rows one
 * screenshot slot actually holds, so the login-state card can rewrite
 * its rows onto the server's spelling before minting. A door exception
 * answers the empty list — the shape then refuses the card honestly
 * instead of offering rows the landing door would refuse forever.
 */
    private java.util.List<String> stagedLoginStateNames(String token) {
        try {
            return coreGateway.getStagedLoginStateNames(token);
        } catch (RuntimeException probeDown) {
            log.warn("登录态暂存名域探测失败，本轮草案诚实缺席 reason={}", probeDown.getMessage());
            return java.util.List.of();
        }
    }

    /**
 * Multi-step plan card : a plan.propose call in the
 * finished transcript journals exactly ONE {@code plan} frame whose meta
 * carries the step list the client renders as tickable / reorderable
 * entries. The gate is the hot-updated {@code ai.chat.plan-threshold}
 * config, read here as the single consumer — a missing or unparsable
 * value falls back to the factory default and never refuses the turn.
 * Below the threshold = zero plan frames (short chains are not
 * bothered); an unparsable argument body = honest absence (never a
 * half-shape card baiting a confirmation click). Confirmation landing
 * and during-run ticks ride the existing draft/progress channels
 * (package 3). Appended BEFORE the done frame, same ladder as every
 * other card.
 */
    private void emitPlanFrame(long sessionId, long assistantMessageId, ReActOutcome outcome) {
        String argumentsJson = findPlanProposalArguments(outcome);
        if (argumentsJson == null) {
            return;
        }
        java.util.List<String> steps =
                com.sixbean.testpike.aiservice.tool.domain.component.PlanProposeTool
                        .readSteps(argumentsJson);
        if (steps.isEmpty()) {
            return;
        }
        if (steps.size() < com.sixbean.testpike.aiservice.tool.domain.component.PlanProposeTool
                .readThreshold(coreGateway)) {
            return;
        }
        StringBuilder line = new StringBuilder("多步执行计划（共 ").append(steps.size())
                .append(" 步，确认后开始执行）：");
        StringBuilder meta = new StringBuilder("{\"steps\":[");
        for (int i = 0; i < steps.size(); i++) {
            line.append('\n').append(i + 1).append(". ").append(steps.get(i));
            if (i > 0) {
                meta.append(',');
            }
            meta.append("{\"index\":").append(i + 1)
                    .append(",\"title\":\"").append(escape(steps.get(i))).append("\"}");
        }
        meta.append("]}");
        coreGateway.appendChatFrame(sessionId, assistantMessageId, "plan",
                line.toString(), meta.toString());
    }

    /** First plan.propose call of the transcript (one card per turn); null = none. */
    private static String findPlanProposalArguments(ReActOutcome outcome) {
        return findFirstCallArgument(outcome,
                com.sixbean.testpike.aiservice.tool.domain.component.PlanProposeTool::isPlanCall,
                null);
    }

    /**
     * Single-source transcript scanner (R40): first assistant tool call whose
     * name matches and — when a body predicate is given — whose argument body
     * passes it; null = none. Every built-in card (plan / clarify) reads the
     * finished transcript through here, so "one card per turn, first
     * accepted call wins" is decided in exactly one place.
     */
    private static String findFirstCallArgument(ReActOutcome outcome,
            java.util.function.Predicate<String> nameMatches,
            java.util.function.Predicate<String> bodyAccepts) {
        for (ChatMessage message : outcome.messages()) {
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (com.sixbean.testpike.aiservice.llm.api.dto.AssistantToolCall call
                    : message.toolCalls()) {
                String name = call.function() == null ? null : call.function().name();
                if (name == null || !nameMatches.test(name)) {
                    continue;
                }
                if (bodyAccepts == null || bodyAccepts.test(call.function().arguments())) {
                    return call.function().arguments();
                }
            }
        }
        return null;
    }

    /**
 * Clarification card : an accepted chat.clarify
 * call in the finished transcript journals exactly ONE {@code clarify}
 * frame — question + 2-4 one-click options + the missing slot name in its
 * meta — so the reload replays the question that was actually asked. The
 * hot budget {@code ai.chat.clarify-per-turn-max} gates the card shape:
 * at or below zero the same content degrades to a plain-text delta frame
 * (the question never silently vanishes, the card just stops popping).
 * A transcript that routed a write call journals nothing here at all:
 * one action per card is the contract, and a draft proposal and a
 * clarification on the same turn would bait a half-understood click.
 * Appended AFTER the plan frame and BEFORE the done frame, same ladder as
 * every other card.
 */
    private void emitClarifyFrame(long sessionId, long assistantMessageId,
                                  ReActOutcome outcome, boolean writeRouted) {
        if (writeRouted) {
            return;
        }
        String argumentsJson = findAcceptedClarifyArguments(outcome);
        if (argumentsJson == null) {
            return;
        }
        String question = com.sixbean.testpike.aiservice.tool.domain.component
                .ClarifyTool.readQuestion(argumentsJson);
        java.util.List<String> options = com.sixbean.testpike.aiservice.tool.domain.component
                .ClarifyTool.readOptions(argumentsJson);
        String missingSlot = com.sixbean.testpike.aiservice.tool.domain.component
                .ClarifyTool.readMissingSlot(argumentsJson);
        StringBuilder line = new StringBuilder("需要你确认一点：").append(question);
        for (int i = 0; i < options.size(); i++) {
            line.append('\n').append(i + 1).append(". ").append(options.get(i));
        }
        line.append("\n点选一个选项，或直接回复补充说明。");
        if (com.sixbean.testpike.aiservice.tool.domain.component
                .ClarifyTool.readMaxPerTurn(coreGateway) < 1) {
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "delta",
                    line.toString(), null);
            return;
        }
        StringBuilder meta = new StringBuilder("{\"question\":\"")
                .append(escape(question)).append("\",\"options\":[");
        for (int i = 0; i < options.size(); i++) {
            meta.append(i > 0 ? "," : "")
                    .append("\"").append(escape(options.get(i))).append("\"");
        }
        meta.append(']');
        if (!missingSlot.isEmpty()) {
            meta.append(",\"missingSlot\":\"").append(escape(missingSlot)).append("\"");
        }
        meta.append('}');
        coreGateway.appendChatFrame(sessionId, assistantMessageId, "clarify",
                line.toString(), meta.toString());
    }

    /**
     * First accepted chat.clarify call of the transcript (one card per turn);
     * null = none. Acceptance uses the same {@code isAcceptedShape} predicate
     * the loop executed with — a refused shape stays out of the ledger.
     */
    private static String findAcceptedClarifyArguments(ReActOutcome outcome) {
        return findFirstCallArgument(outcome,
                com.sixbean.testpike.aiservice.tool.domain.component.ClarifyTool::isClarifyCall,
                com.sixbean.testpike.aiservice.tool.domain.component.ClarifyTool::isAcceptedShape);
    }

    private final CoreGateway coreGateway;
    /** Argument-body parser of write-tool calls (model output = untrusted input). */
    private final com.fasterxml.jackson.databind.ObjectMapper draftBodyReader =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final ReActRunner reActRunner;
    private final ProjectLlmRouter projectLlmRouter;
    private final GroundingService groundingService;
    private final RefObjectResolver refObjectResolver;
    private final FailureDiagnosisResolver failureDiagnosisResolver;
    private final FailureScreenshotResolver failureScreenshotResolver;
    private final RepairProposalGenerator repairProposalGenerator;
    private final TimeTravelContextResolver timeTravelContextResolver;
    private final ChatUserImageResolver chatUserImageResolver;
    /** K6 long-context knobs reader . */
    private final AiCtxConfigResolver ctxResolver;
    /** Rolling older-history summariser ; null = honest no-summary shape. */
    private final com.sixbean.testpike.aiservice.aichat.domain.component.DialogRollingSummary
            rollingSummary;
    /**
 * Goal-plan ledger store ; null keeps the exact
 * pre-ledger dialog shape (the storage door is not registered — every
 * finish behaves as before). One ledger row identity is
 * (session, plan_version); version minting for superseded goals belongs
 * to the lifecycle verdict, this segment lands the first version.
 */
    private final com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedgerStore
            goalPlanStore;
    /**
 * Domain-experience read door : the L0 head appends the
 * project's experience section — distilled memory + SKILL usage ledger,
 * annotated with confidence and freshness, capped by the hot K8 key.
 * Null keeps the exact pre-experience dialog shape (the door is not
 * registered — e.g. in unit wiring); absence is zero noise either way.
 */
    private final com.sixbean.testpike.aiservice.aichat.domain.component.DomainExperienceReader
            domainExperience;
    /**
 * Cross-message image replay scanner : consulted
 * ONLY when the current turn carries no fresh pictures — null keeps the
 * exact pre-slice dialog shape (unit wiring), a reachable scan caps the
 * candidates by the hot key through this single door (R40).
 */
    private final com.sixbean.testpike.aiservice.aichat.domain.component.ChatImageLedgerScanner
            imageLedgerScanner;

    /** The ledger version the dialog writes and reads today (v2+ minting: ). */
    private static final int CURRENT_PLAN_VERSION = 1;

    public ChatRunOrchestrator(CoreGateway coreGateway, ReActRunner reActRunner,
                               ProjectLlmRouter projectLlmRouter,
                               GroundingService groundingService,
                               RefObjectResolver refObjectResolver,
                               FailureDiagnosisResolver failureDiagnosisResolver,
                               FailureScreenshotResolver failureScreenshotResolver,
                               RepairProposalGenerator repairProposalGenerator,
                               TimeTravelContextResolver timeTravelContextResolver,
                               ChatUserImageResolver chatUserImageResolver,
                               AiCtxConfigResolver ctxResolver,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .DialogRollingSummary rollingSummary) {
        this(coreGateway, reActRunner, projectLlmRouter, groundingService, refObjectResolver,
                failureDiagnosisResolver, failureScreenshotResolver, repairProposalGenerator,
                timeTravelContextResolver, chatUserImageResolver, ctxResolver, rollingSummary,
                null, null);
    }

    /** Ledger-aware wiring ; no experience door (unit-test shape). */
    public ChatRunOrchestrator(CoreGateway coreGateway, ReActRunner reActRunner,
                               ProjectLlmRouter projectLlmRouter,
                               GroundingService groundingService,
                               RefObjectResolver refObjectResolver,
                               FailureDiagnosisResolver failureDiagnosisResolver,
                               FailureScreenshotResolver failureScreenshotResolver,
                               RepairProposalGenerator repairProposalGenerator,
                               TimeTravelContextResolver timeTravelContextResolver,
                               ChatUserImageResolver chatUserImageResolver,
                               AiCtxConfigResolver ctxResolver,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .DialogRollingSummary rollingSummary,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .GoalPlanLedgerStore goalPlanStore) {
        this(coreGateway, reActRunner, projectLlmRouter, groundingService, refObjectResolver,
                failureDiagnosisResolver, failureScreenshotResolver, repairProposalGenerator,
                timeTravelContextResolver, chatUserImageResolver, ctxResolver, rollingSummary,
                goalPlanStore, null);
    }

    public ChatRunOrchestrator(CoreGateway coreGateway, ReActRunner reActRunner,
                               ProjectLlmRouter projectLlmRouter,
                               GroundingService groundingService,
                               RefObjectResolver refObjectResolver,
                               FailureDiagnosisResolver failureDiagnosisResolver,
                               FailureScreenshotResolver failureScreenshotResolver,
                               RepairProposalGenerator repairProposalGenerator,
                               TimeTravelContextResolver timeTravelContextResolver,
                               ChatUserImageResolver chatUserImageResolver,
                               AiCtxConfigResolver ctxResolver,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .DialogRollingSummary rollingSummary,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .GoalPlanLedgerStore goalPlanStore,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .DomainExperienceReader domainExperience) {
        this(coreGateway, reActRunner, projectLlmRouter, groundingService, refObjectResolver,
                failureDiagnosisResolver, failureScreenshotResolver, repairProposalGenerator,
                timeTravelContextResolver, chatUserImageResolver, ctxResolver, rollingSummary,
                goalPlanStore, domainExperience, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatRunOrchestrator(CoreGateway coreGateway, ReActRunner reActRunner,
                               ProjectLlmRouter projectLlmRouter,
                               GroundingService groundingService,
                               RefObjectResolver refObjectResolver,
                               FailureDiagnosisResolver failureDiagnosisResolver,
                               FailureScreenshotResolver failureScreenshotResolver,
                               RepairProposalGenerator repairProposalGenerator,
                               TimeTravelContextResolver timeTravelContextResolver,
                               ChatUserImageResolver chatUserImageResolver,
                               AiCtxConfigResolver ctxResolver,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .DialogRollingSummary rollingSummary,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .GoalPlanLedgerStore goalPlanStore,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .DomainExperienceReader domainExperience,
                               com.sixbean.testpike.aiservice.aichat.domain.component
                                       .ChatImageLedgerScanner imageLedgerScanner) {
        this.coreGateway = coreGateway;
        this.reActRunner = reActRunner;
        this.projectLlmRouter = projectLlmRouter;
        this.groundingService = groundingService;
        this.refObjectResolver = refObjectResolver;
        this.failureDiagnosisResolver = failureDiagnosisResolver;
        this.failureScreenshotResolver = failureScreenshotResolver;
        this.repairProposalGenerator = repairProposalGenerator;
        this.timeTravelContextResolver = timeTravelContextResolver;
        this.chatUserImageResolver = chatUserImageResolver;
        this.ctxResolver = ctxResolver;
        this.rollingSummary = rollingSummary;
        this.goalPlanStore = goalPlanStore;
        this.domainExperience = domainExperience;
        this.imageLedgerScanner = imageLedgerScanner;
    }

    /** Execute one dialog run; every outcome lands in the frame ledger. */
    public void run(AiChatRunMessage message) {
        long sessionId = message.sessionId();
        long assistantMessageId = message.assistantMessageId();
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_EXEC_ENABLED))) {
            refuseDisabled(message);
            return;
        }
        // phase 1: session budget gate at the turn boundary (ONE ledger
        // read per turn, never inside the frame loop — a per-frame config/DB
        // read would amplify exactly the load this gate is meant to cap).
        long budgetCap = sessionBudgetCap();
        if (budgetCap >= 0) {
            long consumed = consumedTokensOrUnknown(sessionId);
            if (consumed > budgetCap) {
                refuseOverBudget(message, consumed);
                return;
            }
        }
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(coreGateway, sessionId,
                assistantMessageId, steeringReader(sessionId), goalPlanStore == null ? null
                        : new GoalPlanReconciler(sessionId, CURRENT_PLAN_VERSION,
                                goalPlanStore, coreGateway),
                        () -> messageSealedAborted(assistantMessageId));
        try {
            // Knife 4c: every pinned chip must verify against the session's
            // project segment BEFORE the model sees the question — a chip the
            // run cannot honour is refused by name, never silently dropped.
            // stage 1: the actor id rides along so tool/skill chips meet
            // the same gated assembly the @-palette offered.
            RefResolution refs = refObjectResolver.resolve(message.projectId(),
                    message.userId(), message.refObjects());
            if (refs.refused()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "error",
                        "本次提问未执行：" + refs.rejectionReason(), null);
                coreGateway.closeChatMessage(assistantMessageId, "error");
                return;
            }
            // Knife 1 : a badge deep link pins one execution — verify the
            // pointer against the session segment before the model sees anything.
            FailureDiagnosis diagnosis = failureDiagnosisResolver.resolve(
                    message.projectId(), message.executionId());
            if (diagnosis.refused()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "error",
                        "本次提问未执行：" + diagnosis.rejectionReason(), null);
                coreGateway.closeChatMessage(assistantMessageId, "error");
                return;
            }
            if (diagnosis.degraded()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                        diagnosis.absentReason(), null);
            }
            // Knife 1 : with the vision point on, the failure-scene
            // screenshot of a RESOLVED diagnosis is read by the configured
            // vision model and its observation joins the evidence. Off = NONE
            // (deterministic chain untouched); degraded = one honest state
            // frame, the text-only answer still runs — never half-material.
            FailureScreenshotEvidence scene = failureScreenshotResolver == null
                    ? FailureScreenshotEvidence.NONE
                    : failureScreenshotResolver.resolve(message.projectId(), diagnosis);
            if (scene.absent()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                        scene.absentReason(), null);
            }
            if (scene.attached()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                        "已读取失败现场截图（" + scene.objectKey() + "），场景观察将并入诊断依据",
                        null);
            }
            // Knife P4 : photos the user attached to THIS
            // question get the same treatment as the failure screenshot —
            // off/photoless = NONE (deterministic chain untouched), degraded
            // = one honest state frame, attached = observations join the
            // system prompt. A mixed outcome still answers: the block names
            // the images that could not be read, never a silent drop.
            // (assembly face): a question WITHOUT fresh pictures
            // still sees the pictures pasted into earlier turns of this
            // session — the ledger scanner names the capped candidates and
            // they join the SAME resolve channel below (one image-read path,
            // R40; the observation block downstream stays untouched). Fresh
            // attachments win outright and the ledger is never consulted.
            boolean freshUserImages = message.attachments() != null
                    && !message.attachments().isEmpty();
            java.util.List<com.sixbean.testpike.common.aichat.ChatImageAttachment> imagesForTurn =
                    (freshUserImages || imageLedgerScanner == null)
                            ? message.attachments()
                            : imageLedgerScanner.replayCandidatesFor(message.userId(),
                                    sessionId, message.attachments());
            com.sixbean.testpike.aiservice.aichat.domain.component.ChatUserImageEvidence
                    userImages = chatUserImageResolver == null
                            ? com.sixbean.testpike.aiservice.aichat.domain.component
                                    .ChatUserImageEvidence.NONE
                            : chatUserImageResolver.resolve(message.projectId(),
                                    sessionId, imagesForTurn);
            if (userImages.absent()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                        userImages.absentReason(), null);
            }
            if (userImages.attached()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                        (freshUserImages ? "已读取用户图片 " : "已回看历史贴图 ")
                                + userImages.readCount() + " 枚，图片观察将并入回答依据",
                        null);
            }
            emitVariableParsingFrame(sessionId, assistantMessageId, message.prompt());
            GroundingOutcome grounding = groundingService.assemble(message.projectId(),
                    message.prompt(), message.systemManager());
            // Knife P5 : a pointer that passed the diagnosis segment gate
            // also feeds the replay timeline block (time-travel Q&A). No
            // pointer, an unverified pointer, or a null resolver seam = no
            // block — badge/diagnosis turns keep their exact old prompt.
            TimeTravelContext replay = diagnosis.resolved() && timeTravelContextResolver != null
                    ? timeTravelContextResolver.resolve(message.executionId())
                    : TimeTravelContext.NONE;
            // Data-moment display contract (behaviour kernel round-2 item 5):
            // the pool's as-of moment rides the retrieval state frame's meta
            // as a structured value — the answer surface renders its "as-of"
            // mini-label from here, with zero extra fetch and no new frame
            // type. Untimed pools and the honest zero-hit posture keep a null
            // meta so the client never invents a clock either.
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                    grounding.zeroHit()
                            ? "检索完成：没有命中可靠材料，回答将如实说明"
                            : "检索完成：命中 " + grounding.citations().size() + " 条来源材料",
                    grounding.zeroHit() || grounding.poolMoment() == null
                            ? null : "{\"dataMoment\":\"" + grounding.poolMoment() + "\"}");
            emitCiteFrames(sessionId, assistantMessageId, grounding);
            // Model-call stage frame :
            // the provider round is the slowest gap before the first token,
            // so the dwell sentence must name it explicitly. Existing state
            // frame channel, no new frame type.
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                    "模型调用中，正在生成回答", null);
            ReActOutcome outcome = reActRunner.runStreaming(
                    buildRequest(message, grounding, refs, diagnosis, scene, replay,
                            userImages, null),
                    message.projectId(),
                    new LlmFallbackNotice(message.userId(), message.projectId(),
                            "在线 AI 对话#" + assistantMessageId),
                    message.userId(), null, null, sessionId, sink);
            if (sink.aborted()) {
                // Chat-abort gate : the user's abort already sealed
                // the ledger honestly in core — this run adds nothing more:
                // no draft cards, no plan/clarify frames, no goal batches,
                // and never a done seal over the aborted status.
                return;
            }
            if (outcome.limitReached()) {
                // Round cap without a final answer: say so honestly instead of
                // sealing a truncated stream as if the answer had landed.
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "error",
                        "工具循环达到轮次上限，本次回答可能不完整（可在系统设置调大 ai.tool.fc-max-rounds）。",
                        null);
                coreGateway.closeChatMessage(assistantMessageId, "error");
                return;
            }
            emitRepairDraft(message.projectId(), sessionId, assistantMessageId, diagnosis);
            boolean writeRouted = emitWriteDraft(message.projectId(), message.userId(),
                    sessionId, assistantMessageId, outcome,
                    userImages.stagingTokens());
            emitPlanFrame(sessionId, assistantMessageId, outcome);
            emitClarifyFrame(sessionId, assistantMessageId, outcome, writeRouted);
            if (!runGoalAutoBatches(message, sessionId, assistantMessageId, sink,
                    grounding, refs, diagnosis, scene, replay, userImages,
                    attributionText(sink, outcome))) {
                return;
            }
            appendDoneThenClose(sessionId, assistantMessageId, outcome, message.projectId(),
                    message.userId());
        } catch (RuntimeException ex) {
            journalFailure(sessionId, assistantMessageId, ex);
        }
    }

    /**
 * Abort read door : true once the user's abort command has sealed
 * this turn's ledger in core. Fail-soft: an unreachable read door reads
 * as "not aborted" (same posture as the steering door) — the seal is
 * durable, so the next checkpoint retries and a transient outage never
 * kills a running turn.
 */
    private boolean messageSealedAborted(long assistantMessageId) {
        try {
            return "aborted".equals(coreGateway.chatMessageStatus(assistantMessageId));
        } catch (RuntimeException doorDown) {
            log.warn("abort read door down, continuing this turn messageId={} reason={}",
                    assistantMessageId, doorDown.getMessage());
            return false;
        }
    }

    /**
 * Steering read door for this turn : the cursor
 * opens at the last ledger seq when the run starts, each poll reads the
 * user-inject frames above it and advances to the highest consumed seq —
 * every supplement is fed to the model exactly once even when several
 * land between two round boundaries. A steering door that cannot answer
 * reads as empty (the next boundary retries; the frames stay journaled in
 * the ledger, so a transient outage must never take the running turn
 * down).
 */
    private com.sixbean.testpike.aiservice.tool.domain.component.UserInjectReader
            steeringReader(long sessionId) {
        java.util.concurrent.atomic.AtomicLong cursor =
                new java.util.concurrent.atomic.AtomicLong(coreGateway.lastChatSeq(sessionId));
        return () -> {
            java.util.List<com.sixbean.testpike.aiservice.gen.infrastructure.remote
                    .InjectFrameRemote> pending;
            try {
                pending = coreGateway.pendingInjects(sessionId, cursor.get());
            } catch (RuntimeException doorDown) {
                log.warn("转向读取门不可达，本轮界按无注入继续 sessionId={} reason={}",
                        sessionId, doorDown.getMessage());
                return java.util.List.of();
            }
            if (pending.isEmpty()) {
                return java.util.List.of();
            }
            java.util.List<String> texts = new java.util.ArrayList<>(pending.size());
            long max = cursor.get();
            for (com.sixbean.testpike.aiservice.gen.infrastructure.remote
                    .InjectFrameRemote frame : pending) {
                if (frame.seq() > max) {
                    max = frame.seq();
                }
                if (frame.payload() != null && !frame.payload().isBlank()) {
                    texts.add(frame.payload());
                }
            }
            cursor.set(max);
            return texts;
        };
    }

    /** Switch off = honest refusal in the ledger, then the turn is sealed as error. */
    private void refuseDisabled(AiChatRunMessage message) {
        log.info("对话执行开关未开启，诚实拒跑 sessionId={} messageId={}",
                message.sessionId(), message.assistantMessageId());
        coreGateway.appendChatFrame(message.sessionId(), message.assistantMessageId(), "error",
                DenialGuidanceFrame.switchClosed("在线 AI 对话", KEY_EXEC_ENABLED), null);
        coreGateway.closeChatMessage(message.assistantMessageId(), "error");
    }

    /**
 * Session budget cap , from the K8 value
 * {@code ai.chat.session-budget-tokens} — negative = unlimited (key
 * absent or unparsable). Fail-open on the config read: garbage in the
 * box must never be the reason the dialog goes dark for everyone.
 */
    private long sessionBudgetCap() {
        try {
            String raw = coreGateway.getSysConfig(KEY_SESSION_BUDGET_TOKENS);
            if (raw == null || raw.isBlank()) {
                return -1L;
            }
            return Long.parseLong(raw.trim());
        } catch (RuntimeException garbageValue) {
            // NumberFormatException (a typed "abc" in the box) and a config
            // door outage both land here: unlimited for this turn.
            return -1L;
        }
    }

    /**
     * The session's accumulated consumption — the same ledger the E9 cost
     * view shows, so the refusal threshold and the reported number can never
     * drift apart. A ledger door that cannot answer reads as -1 (unknown),
     * which is below any positive cap: the turn runs and the ledger catches
     * up next turn (fail-open on the read path — a billing hiccup is not a
     * reason to take the dialog down).
     */
    private long consumedTokensOrUnknown(long sessionId) {
        try {
            return coreGateway.sessionTotalTokens(sessionId);
        } catch (RuntimeException doorDown) {
            log.warn("会话预算账门不可达，本轮放行 sessionId={} reason={}",
                    sessionId, doorDown.getMessage());
            return -1L;
        }
    }

    /**
     * Over-budget posture: one error frame that names the setting path and
     * the key verbatim (R32 guidance contract — the operator must be able to
     * raise the cap from the message alone), carries the session's real
     * consumption, and points at the cost view for the breakdown. Then the
     * turn is sealed as error: the refusal belongs to the ledger, never a
     * silently dropped question. {@code consumed} is the number the gate
     * already read — refusal never re-reads the ledger.
     */
    private void refuseOverBudget(AiChatRunMessage message, long consumed) {
        String usage = consumed >= 0
                ? "已消耗 " + consumed + " tokens"
                : "已消耗量本次未能读取";
        log.info("会话 token 预算超限，诚实拒跑 sessionId={} messageId={}",
                message.sessionId(), message.assistantMessageId());
        coreGateway.appendChatFrame(message.sessionId(), message.assistantMessageId(), "error",
                "本次提问未执行：该会话的 token 用量已超过预算上限（" + usage + "）。"
                        + "可在「系统管理 → 系统配置」调整 " + KEY_SESSION_BUDGET_TOKENS
                        + "，或在「成本中心 → 用量校准」查看该会话的消耗明细。", null);
        coreGateway.closeChatMessage(message.assistantMessageId(), "error");
    }

    /**
     * Request assembly: fixed system role + grounding posture (lean on the [C]
     * block, or disclaim everything when the retrieval hit nothing) + the
     * user's prompt; the context block lands at the tail of the system message.
     * Session context / pinned facts land later (AP batch assembly) — the seam
     * is deliberately here so nothing else about the loop changes.
     */
    private ChatCompletionRequest buildRequest(AiChatRunMessage message,
                                               GroundingOutcome grounding,
                                               RefResolution refs,
                                               FailureDiagnosis diagnosis,
                                               FailureScreenshotEvidence scene,
                                               TimeTravelContext replay,
                                               com.sixbean.testpike.aiservice.aichat.domain.component
                                                       .ChatUserImageEvidence userImages,
                                               String ledgerJsonCarry) {
        String model = projectLlmRouter.route(message.projectId()).modelName();
        // Pre-send redaction (behaviour kernel round-2 item 2, R40 single
        // source): the outbound dialog payload — free-text prompt and both
        // vision observation blocks — passes the one masking gate. Config
        // reads are hot per send; stored originals are never transformed.
        String redactSwitch = coreGateway.getSysConfig(RedactGate.KEY_ENABLED);
        String redactForms = coreGateway.getSysConfig(RedactGate.KEY_PATTERNS);
        StringBuilder system = new StringBuilder(behaviorDirectivesHead(message.projectId()))
                .append(grounding.zeroHit() ? ZERO_HIT_SUFFIX : GROUNDED_SUFFIX);
        if (!grounding.zeroHit()) {
            system.append("\n—— 参考材料 ——\n").append(grounding.contextBlock());
        }
        if (!refs.contextBlock().isEmpty()) {
            system.append(REFS_SUFFIX).append("\n—— 引用对象 ——\n").append(refs.contextBlock());
        }
        if (!diagnosis.contextBlock().isEmpty()) {
            system.append(DIAGNOSIS_SUFFIX)
                    .append("\n—— 失败诊断 ——\n").append(diagnosis.contextBlock());
        }
        if (scene.attached()) {
            system.append(SCENE_SUFFIX).append("\n—— 截图场景观察 ——\n")
                    .append(RedactGate.redactOutbound(redactSwitch, redactForms,
                            scene.sceneBlock()));
        }
        if (userImages != null && userImages.attached()) {
            system.append(USER_IMAGES_SUFFIX)
                    .append("\n—— 用户图片观察 ——\n")
                    .append(RedactGate.redactOutbound(redactSwitch, redactForms,
                            userImages.observationBlock()));
        }
        if (replay.present()) {
            system.append("\n\n").append(replay.block());
        }
        // Goal-plan ledger carry :
        // an auto batch hands the already-read steps JSON straight in — ONE
        // ledger read per batch, never two; a turn whose whole prompt is the
        // resume command reads the stored ledger here. Same single-source
        // block the in-run nudge hands the loop, so a nudge, a resume and an
        // auto batch never disagree. Any other fresh prompt never reads the
        // ledger (a new question is not a continuation).
        if (goalPlanStore != null && (ledgerJsonCarry != null
                || GoalPlanLedger.isResumeCommand(message.prompt()))) {
            String ledgerBlock = GoalPlanLedger.renderSystemSection(ledgerJsonCarry != null
                    ? ledgerJsonCarry
                    : goalPlanStore.findStepsJson(message.sessionId(), CURRENT_PLAN_VERSION));
            if (!ledgerBlock.isEmpty()) {
                system.append("\n\n").append(ledgerBlock);
            }
        }
        // a (AP-1 ② first stage): sealed history turns ride the
        // request between the system head and the live prompt. Pinned facts
        // land with 1b-a2 (pinnedFactsFor) and the rolling summariser with
        // 1b-b — the summariser is fail-soft on its own (blank = no section),
        // and a missing door renders the honest "no older context" shape
        // instead of a fabricated summary.
        return ChatCompletionRequest.of(model, ConversationContextAssembler.assemble(
                new ConversationAssemblyRequest(system.toString(), pinnedFactsFor(message),
                        historyFor(message),
                        RedactGate.redactOutbound(redactSwitch, redactForms, message.prompt()),
                        ctxResolver.sessionKeepRecentTurns()),
                rollingSummary == null ? null
                        : rollingSummary.forTurn(model, message.projectId())));
    }

    /**
 * System head resolution for this turn : the
 * gate -> published-row -> degrade-to-contract sequence is single-sourced
 * in the behaviour-kernel assembly piece, so the dialog head carries the
 * kernel invariant exactly once like every other upgraded reasoning
 * domain (L0 clause assembly must reach all consumers — the L3 live proof
 * caught this head shipping without it). The built-in constant remains
 * the conservative fallback for the three honest-degradation shapes:
 * switch explicitly off (the family is not even read), no published row,
 * or a failing core read — the turn never gets an empty head and a
 * template outage never takes the dialog down.
 */
    private String behaviorDirectivesHead(long projectId) {
        String governanceRawValue;
        try {
            governanceRawValue = coreGateway.getSysConfig(
                    com.sixbean.testpike.aiservice.aichat.domain.component
                            .ChatBehaviorDirectives.KEY_ENABLED);
        } catch (RuntimeException e) {
            // A failing config read is just another degradation shape: treat
            // the switch as absent and let the assembly piece fall back.
            governanceRawValue = null;
        }
        return com.sixbean.testpike.aiservice.llm.pipeline
                .BehaviorKernelAssembly.assembleHead(
                        governanceRawValue,
                        () -> coreGateway.promptTemplateLatestByStatus(
                                com.sixbean.testpike.aiservice.aichat.domain.component
                                        .ChatBehaviorDirectives.TEMPLATE_NAME,
                                com.sixbean.testpike.aiservice.aichat.domain.component
                                        .ChatBehaviorDirectives.TEMPLATE_DOMAIN,
                                com.sixbean.testpike.aiservice.aichat.domain.component
                                        .ChatBehaviorDirectives.PUBLISHED_STATUS),
                        SYSTEM_PROMPT,
                        "行为指令模板读路径不可用，本轮对话回退内置保守条款（不影响对话执行）")
                + goalEchoClauseGroup()
                + goalAttributionClauseGroup()
                + domainExperienceSection(projectId);
    }

    /**
 * The project's domain-experience section for the L0 head :
 * distilled memory + SKILL usage ledger with confidence/freshness
 * annotations, capped by the hot K8 line key. The read door is fail-soft
 * on its own (no rows or a dead read = the section is simply absent,
 * zero noise), and an unregistered door is the exact pre-experience shape.
 */
    private String domainExperienceSection(long projectId) {
        return domainExperience == null ? "" : domainExperience.sectionFor(projectId);
    }

    /**
 * Goal-echo clause group : appended to the dialog system
 * head while the factory-on gate is open. The published
 * chat-goal-echo-directives row wins; a read failure lands on the built-in
 * conservative clause — same shape as the directive family above, the turn
 * never loses its echo instruction and never breaks on a template outage.
 * Closed gate = the clause is not injected and the template is not read.
 */
    private String goalEchoClauseGroup() {
        String governanceRawValue;
        try {
            governanceRawValue = coreGateway.getSysConfig(
                    com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.KEY_ENABLED);
        } catch (RuntimeException e) {
            governanceRawValue = null;
        }
        if (!com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.enabled(governanceRawValue)) {
            return "";
        }
        CoreGateway.PromptTemplateBrief publishedRow = null;
        try {
            publishedRow = coreGateway.promptTemplateLatestByStatus(
                    com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.TEMPLATE_NAME,
                    com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.TEMPLATE_DOMAIN,
                    com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.PUBLISHED_STATUS);
        } catch (RuntimeException e) {
            log.warn("目标回显模板读路径不可用，本轮回退内置回显条款（不影响对话执行）: {}", e.toString());
        }
        return "\n\n" + com.sixbean.testpike.aiservice.llm.pipeline.GoalEchoGate.headClause(
                publishedRow == null ? null : publishedRow.content());
    }

    /**
     * Goal-attribution clause group (behaviour kernel round-2 item 1): the
     * per-round self-report instruction appended to the dialog system head
     * while the factory-on gate is open. Published
     * chat-goal-attribution-directives row wins; a read failure lands on
     * the built-in conservative clause — same shape as the echo family, so
     * a template outage never costs the dialog its lifecycle instruction
     * and a closed gate simply removes the clause (legacy posture, byte
     * for byte).
     */
    private String goalAttributionClauseGroup() {
        String governanceRawValue;
        try {
            governanceRawValue = coreGateway.getSysConfig(
                    com.sixbean.testpike.aiservice.aichat.domain.component
                            .GoalAttributionGate.KEY_ENABLED);
        } catch (RuntimeException e) {
            governanceRawValue = null;
        }
        if (!com.sixbean.testpike.aiservice.aichat.domain.component
                .GoalAttributionGate.enabled(governanceRawValue)) {
            return "";
        }
        CoreGateway.PromptTemplateBrief publishedRow = null;
        try {
            publishedRow = coreGateway.promptTemplateLatestByStatus(
                    com.sixbean.testpike.aiservice.aichat.domain.component
                            .GoalAttributionGate.TEMPLATE_NAME,
                    com.sixbean.testpike.aiservice.aichat.domain.component
                            .GoalAttributionGate.TEMPLATE_DOMAIN,
                    com.sixbean.testpike.aiservice.aichat.domain.component
                            .GoalAttributionGate.PUBLISHED_STATUS);
        } catch (RuntimeException e) {
            log.warn("目标归属模板读路径不可用，本轮回退内置归属条款（不影响对话执行）: {}", e.toString());
        }
        return "\n\n" + com.sixbean.testpike.aiservice.aichat.domain.component
                .GoalAttributionGate.headClause(publishedRow == null ? null : publishedRow.content());
    }

    /**
 * The sealed turns of this session for the rolling window a
 * contract ①): one read of the core history door, minus the current turn's
 * echoed user row. A door failure is fail-soft on the read path — the turn
 * still gets its single-turn answer, because history is an enhancement and
 * refusing the whole run on a read hiccup would take the dialog down.
 */
    private List<ChatMessageRemote> historyFor(AiChatRunMessage message) {
        try {
            List<ChatMessageRemote> sealed =
                    coreGateway.listChatMessages(message.userId(), message.sessionId());
            return DialogHistoryWindow.withoutInFlightPrompt(sealed, message.prompt());
        } catch (RuntimeException e) {
            log.warn("dialog history door unavailable, turn runs single-shot: {}", e.toString());
            return List.of();
        }
    }

    /**
 * The session's pinned navigation context as fact lines ( segment
 * 1b-a2): one read of the core context door per turn, whitelisted again
 * on the read side (defence in depth over the write door's validation,
 * single vocabulary in SessionPinnedFacts). A dead door or a broken
 * payload keeps the turn alive without the pinned section — same
 * fail-soft posture as the history read door.
 */
    private List<String> pinnedFactsFor(AiChatRunMessage message) {
        try {
            return SessionPinnedFacts.render(
                    coreGateway.sessionContext(message.userId(), message.sessionId()));
        } catch (RuntimeException e) {
            log.warn("pinned-context door unavailable, turn runs without pinned facts: {}",
                    e.toString());
            return List.of();
        }
    }

    /**
 * Data-key parsing frame : a
 * question that references {{DATA:KEY}} pool variables froze on the
 * client for the whole assembly gap — the server resolved keys,
 * retrieved context and called the model behind one silent wait. When
 * (and only when) such references exist this frame names the first few
 * keys and counts the rest, so the dwell sentence can tell the user
 * WHICH variables are being parsed. Rides the existing state frame
 * channel — no new ChatFrameType, no second event stream (AT-batch red
 * line, R40 single source).
 */
    static final int DATA_KEY_DISPLAY_CAP = 3;
    private static final java.util.regex.Pattern DATA_KEY_PATTERN =
            java.util.regex.Pattern.compile("\\{\\{DATA:([^{}]*)\\}\\}");

    void emitVariableParsingFrame(long sessionId, long assistantMessageId, String prompt) {
        if (prompt == null || prompt.indexOf("{{DATA:") < 0) {
            return;
        }
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        int total = 0;
        java.util.regex.Matcher matcher = DATA_KEY_PATTERN.matcher(prompt);
        while (matcher.find()) {
            total++;
            if (keys.size() < DATA_KEY_DISPLAY_CAP) {
                String key = matcher.group(1).trim();
                if (!key.isEmpty()) {
                    keys.add(key);
                }
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        String listed = String.join("、", keys);
        String tail = total > keys.size() ? " 等 " + total + " 项" : "";
        coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                "变量解析中：" + listed + tail + "，随后进行上下文检索", null);
    }

    /**
     * One cite frame per grounded source (FrameLedger channel, zero new frame
     * types): payload is the human-readable line, meta the machine-readable
     * card fields the client renders as a clickable source row. Titles/excerpts
     * may carry quotes — the same hand-rolled flat-JSON escape the trace
     * frames use (no JSON library in this hot path for a handful of scalars).
     */
    private void emitCiteFrames(long sessionId, long assistantMessageId,
                                GroundingOutcome grounding) {
        if (grounding.zeroHit()) {
            return;
        }
        for (GroundingCitation citation : grounding.citations()) {
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "cite",
                    "[C" + citation.index() + "] " + citation.title(),
                    citeMeta(citation));
        }
    }

    /**
 * Repair-proposal draft : a badge-deep-link turn that
 * carries a RESOLVED diagnosis gets one proposal card, journaling BEFORE
 * the done frame. Posture ladder:
 * <ul>
 * <li>switch off → zero outbound call, one state frame that names the
 * setting path and the key literally (R32: a disabled AI feature must
 * say where to turn it on, never just stay silent);</li>
 * <li>switch on, no trustworthy proposal → honest absence: no draft
 * frame at all (a half-baked card would bait a confirmation);</li>
 * <li>switch on, valid proposal → exactly one draft frame; NOTHING
 * lands here — applying needs the confirm-token chain of knife 3b,
 * re-checking the baseVersion the card carries.</li>
 * </ul>
 */
    private void emitRepairDraft(long projectId, long sessionId, long assistantMessageId,
                                 FailureDiagnosis diagnosis) {
        if (!diagnosis.resolved() || repairProposalGenerator == null) {
            return;
        }
        if (!SysConfigBooleans.on(coreGateway.getSysConfig(KEY_REPAIR_DRAFT_ENABLED))) {
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                    DenialGuidanceFrame.switchClosedAfterAttempt("AI 失败修复草案",
                            "本次回答不受影响", KEY_REPAIR_DRAFT_ENABLED),
                    null);
            return;
        }
        ChatRepairDraft draft = repairProposalGenerator.generate(projectId,
                diagnosis.caseId(), diagnosis.errorDetail());
        if (!draft.valid()) {
            log.info("对话修复草案诚实缺席 sessionId={} messageId={} reason={}",
                    sessionId, assistantMessageId, draft.invalidReason());
            return;
        }
        coreGateway.appendChatFrame(sessionId, assistantMessageId, "draft",
                draft.summary(), repairDraftMeta(draft, diagnosis.executionId()));
    }

    /**
     * Draft card meta: flat single-line JSON, same escape/wire contract as the
     * repair card. objectType routes the core-side ledger to the matching
     * strategy; actionJson carries the model's raw body so the ledger stores a
     * trustworthy snapshot of exactly what the human will confirm.
     */
    private static String writeDraftMeta(WriteProposal proposal) {
        // Generic anchor key is "targetId"; "caseId" is emitted for CASE_WRITE
        // as a wire alias because the card component and the ledger's
        // repair-era reader consume that literal — dropping it would silently
        // break in-flight clients (alias removal = its own frontend segment).
        return "{\"objectType\":\"" + escape(proposal.objectType()) + "\""
                + ",\"targetId\":" + proposal.targetId()
                + (DRAFT_KIND_CASE_WRITE.equals(proposal.objectType())
                        ? ",\"caseId\":" + proposal.targetId() : "")
                + ",\"baseVersion\":" + proposal.baseVersion()
                + ",\"toolName\":\"" + escape(proposal.toolName())
                + "\",\"actionJson\":\"" + escape(proposal.actionJson()) + "\"}";
    }

    /**
     * Repair card meta: flat single-line JSON (the escape/wire contract the
     * cite frames already pin). actionJson rides inside as an escaped string
     * so the landing side can persist a trustworthy snapshot of exactly what
     * the user confirmed; the client renders the diff from before (baseVersion's
     * script) and after (this field).
     */
    private static String repairDraftMeta(ChatRepairDraft draft, long executionId) {
        return "{\"executionId\":" + executionId
                + ",\"caseId\":" + draft.caseId()
                + ",\"baseVersion\":" + draft.baseVersion()
                + ",\"schemaVersion\":\"" + escape(draft.schemaVersion())
                + "\",\"model\":\"" + escape(draft.modelName())
                + "\",\"actionJson\":\"" + escape(draft.actionJson()) + "\"}";
    }

    private static String citeMeta(GroundingCitation citation) {
        StringBuilder meta = new StringBuilder(128)
                .append("{\"index\":").append(citation.index())
                .append(",\"kind\":\"").append(escape(citation.kind()))
                .append("\",\"refId\":").append(citation.refId())
                .append(",\"domainCode\":");
        if (citation.domainCode() == null) {
            meta.append("null");
        } else {
            meta.append('"').append(escape(citation.domainCode())).append('"');
        }
        meta.append(",\"title\":\"").append(escape(citation.title()))
                .append("\",\"excerpt\":\"").append(escape(citation.excerpt()))
                .append("\",\"score\":").append(citation.score())
                .append(",\"revision\":");
        if (citation.revision() == null) {
            meta.append("null");
        } else {
            meta.append(citation.revision());
        }
        // Paragraph anchor (behaviour-kernel round-2 item 3): rides quoted or
        // null — the client only aims the chunk pointer when it is a string.
        meta.append(",\"chunkId\":");
        if (citation.chunkId() == null) {
            meta.append("null");
        } else {
            meta.append('"').append(escape(citation.chunkId())).append('"');
        }
        meta.append('}');
        return meta.toString();
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // Remaining C0 controls ride as \\u escapes: a literal
                        // one anywhere would break both JSON parsing and the
                        // single-line invariant the SSE :meta comment needs.
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
 * Autonomous goal-plan continuation : when the turn's ledger still
 * owes open steps, the run picks up the NEXT batch on its own — the user
 * is never asked to type the resume word first (that trigger stays as
 * Every batch rides the same assembly as a resume turn: the open ledger
 * JSON read here is handed into the system head, so one ledger read per
 * batch and the card door sentence stays single-source. The loop stops
 * on: every step done (one honest close sentence flipping the card to
 * zero open steps), the tool-cycle round cap (sealed as an error like
 * the initial run), or the safety cap — K8 hot key
 * {@code ai.chat.goal.max-auto-batches}. Write drafts of each batch
 * still land as confirmation cards: the human write gate is untouched
 * (red line). No store = the exact legacy posture (nothing happens).
 * Returns false only when this helper sealed the message as an error
 * and the caller must not add the done frame.
 */
    private boolean runGoalAutoBatches(AiChatRunMessage message, long sessionId,
                                       long assistantMessageId,
                                       com.sixbean.testpike.aiservice.tool.domain.component
                                               .ReActStreamSink sink,
                                       GroundingOutcome grounding, RefResolution refs,
                                       FailureDiagnosis diagnosis,
                                       FailureScreenshotEvidence scene,
                                       TimeTravelContext replay,
                                       com.sixbean.testpike.aiservice.aichat.domain.component
                                               .ChatUserImageEvidence userImages,
                                       String lastAnswer) {
        if (goalPlanStore == null) {
            return true;
        }
        int cap = goalAutoBatchCap();
        // Attribution guard (behaviour kernel round-2 item 1), read ONCE per
        // turn (hot-update semantics): while the factory-on gate is open,
        // all loads nothing (the ruling's pinned fail-safe — prefer losing
        // continuity over polluting correctness).
        boolean attributionGuard;
        try {
            attributionGuard = com.sixbean.testpike.aiservice.aichat.domain.component
                    .GoalAttributionGate.enabled(
                            coreGateway.getSysConfig(com.sixbean.testpike.aiservice
                                    .aichat.domain.component.GoalAttributionGate.KEY_ENABLED));
        } catch (RuntimeException doorDown) {
            attributionGuard = true;
        }
        // Cost transparency : ONE ledger read per
        // turn — the same sessionTotalTokens door the budget gate and the E9
        // cost view use — carried into every open-tail sentence below.
        final long consumed = consumedTokensOrUnknown(sessionId);
        int auto = 0;
        while (true) {
            if (sink.aborted()) {
                // Batch head checkpoint : never open a new goal
                // auto batch against an aborted turn. false = terminal
                // handled by the abort's own seal.
                return false;
            }
            String stepsJson = goalPlanStore.findStepsJson(sessionId, CURRENT_PLAN_VERSION);
            java.util.List<String> open = GoalPlanLedger.openTitles(stepsJson);
            if (open.isEmpty()) {
                if (auto > 0) {
                    coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                            com.sixbean.testpike.aiservice.aichat.domain.component.GoalCostLine
                                    .appendCostTail("目标计划还有 0 步未完成（）；已自动接续 " + auto
                                            + " 批，全部步骤完成。", consumed), null);
                }
                return true;
            }
            if (attributionGuard) {
                com.sixbean.testpike.aiservice.aichat.domain.component.GoalAttributionFrame
                        frame = com.sixbean.testpike.aiservice.aichat.domain.component
                                .GoalAttributionGate.parse(lastAnswer);
                if (frame == com.sixbean.testpike.aiservice.aichat.domain.component
                        .GoalAttributionFrame.OPEN_NEW) {
                    goalPlanStore.upsertIfChanged(sessionId, CURRENT_PLAN_VERSION,
                            com.sixbean.testpike.aiservice.aichat.domain.component
                                    .GoalPlanLedger.withNewGoalClosure(stepsJson));
                    coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                            com.sixbean.testpike.aiservice.aichat.domain.component.GoalCostLine
                                    .appendCostTail("本轮自报开新：旧目标已显式结束并留痕（"
                                            + open.size() + " 步未继续推进），"
                                            + "其内容不会进入新答案。", consumed), null);
                    return true;
                }
                if (frame == com.sixbean.testpike.aiservice.aichat.domain.component
                        .GoalAttributionFrame.FRAME_MISSING) {
                    coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                            com.sixbean.testpike.aiservice.aichat.domain.component.GoalCostLine
                                    .appendCostTail("本轮回答未自报目标归属（续旧/开新），"
                                            + "为防旧目标内容混入，未完成的目标本轮未自动接续；"
                                            + "发送「" + GoalPlanLedger.RESUME_COMMAND
                                            + "」可手动接续。", consumed), null);
                    return true;
                }
            }
            if (auto >= cap) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                        com.sixbean.testpike.aiservice.aichat.domain.component.GoalCostLine
                                .appendCostTail("目标计划还有 " + open.size() + " 步未完成（"
                                        + String.join("、", open)
                                        + "）；自主续跑已达安全上限 " + KEY_GOAL_MAX_AUTO_BATCHES + "="
                                        + cap + "，发送「" + GoalPlanLedger.RESUME_COMMAND
                                        + "」可手动接续。", consumed), null);
                return true;
            }
            auto++;
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "state",
                    com.sixbean.testpike.aiservice.aichat.domain.component.GoalCostLine
                            .appendCostTail("目标计划还有 " + open.size() + " 步未完成（"
                                    + String.join("、", open)
                                    + "），自动接续第 " + auto + " 批。", consumed), null);
            ReActOutcome outcome = reActRunner.runStreaming(
                    buildRequest(message, grounding, refs, diagnosis, scene, replay,
                            userImages, stepsJson),
                    message.projectId(),
                    new LlmFallbackNotice(message.userId(), message.projectId(),
                            "在线 AI 对话#" + assistantMessageId),
                    message.userId(), null, null, sessionId, sink);
            if (sink.aborted()) {
                // Post-batch abort read : a mid-batch click seals the
                // turn — the batch's outcome never reaches draft emission.
                return false;
            }
            if (outcome.limitReached()) {
                coreGateway.appendChatFrame(sessionId, assistantMessageId, "error",
                        "工具循环达到轮次上限，本次回答可能不完整（可在系统设置调大 ai.tool.fc-max-rounds）。",
                        null);
                coreGateway.closeChatMessage(assistantMessageId, "error");
                return false;
            }
            // The batch's own streamed answer is the attribution source for
            // the NEXT carry decision (per-round self-report, item 1) — read
            // the whole turn text, same seam as the initial run (R40: one
            // helper, never a second copy of the fallback rule).
            lastAnswer = attributionText(sink, outcome);
            boolean writeRouted = emitWriteDraft(message.projectId(), message.userId(),
                    sessionId, assistantMessageId, outcome,
                    userImages.stagingTokens());
            emitPlanFrame(sessionId, assistantMessageId, outcome);
            emitClarifyFrame(sessionId, assistantMessageId, outcome, writeRouted);
        }
    }

    /**
     * The text the attribution guard reads after a streamed run: everything
     * the sink accumulated for this run (the live FC shape writes the
     * self-report line on an earlier tool round while the sealed final round
     * only appends a short closer), falling back to the provider's final
     * content when nothing streamed — the exact legacy reading for sinks
     * without a buffer. Taking resets the window so every carry decision
     * consults only its own batch's answer.
     */
    private static String attributionText(
            com.sixbean.testpike.aiservice.tool.domain.component.ReActStreamSink sink,
            ReActOutcome outcome) {
        String streamed = sink == null ? null : sink.takeTurnAnswer();
        if (streamed != null && !streamed.isBlank()) {
            return streamed;
        }
        return outcome.result() == null ? null : outcome.result().content();
    }

    /**
 * Safety cap for this turn : read ONCE per turn through the core
 * config door (hot-updatable, never re-polled inside the batch loop).
 * Missing, garbage or out-of-range values all land on the factory
 * default 3 — a typo in the box must never unlock an endless loop.
 */
    private int goalAutoBatchCap() {
        try {
            String raw = coreGateway.getSysConfig(KEY_GOAL_MAX_AUTO_BATCHES);
            if (raw == null || raw.isBlank()) {
                return GOAL_AUTO_BATCH_DEFAULT;
            }
            int parsed = Integer.parseInt(raw.trim());
            return parsed < GOAL_AUTO_BATCH_MIN || parsed > GOAL_AUTO_BATCH_MAX
                    ? GOAL_AUTO_BATCH_DEFAULT
                    : parsed;
        } catch (RuntimeException garbageValue) {
            return GOAL_AUTO_BATCH_DEFAULT;
        }
    }

    /**
 * Done frame carries the usage numbers (E9 attribution) plus — AX batch
 * — the 1-3 one-click next-step chips the model proposed through the
 * built-in chat.suggest tool. Delivery gate: every proposal is re-checked
 * server-side against the turn's mounted intersection (registered
 * candidates of the same assembly the loop would execute against, plus
 * the dialog built-ins) BEFORE journaling; an out-of-scope capability
 * drops the whole entry. A suggestion door fault drops the chips but
 * never takes the seal down (fail-soft: the answer landed, the extension
 * is optional). No suggestions = byte-identical legacy payload.
 */
    private void appendDoneThenClose(long sessionId, long assistantMessageId,
                                     ReActOutcome outcome, long projectId, Long actorId) {
        int promptTokens = outcome.result() == null ? 0 : outcome.result().promptTokens();
        int completionTokens = outcome.result() == null ? 0 : outcome.result().completionTokens();
        StringBuilder payload = new StringBuilder("{\"messageId\":").append(assistantMessageId)
                .append(",\"promptTokens\":").append(promptTokens)
                .append(",\"completionTokens\":").append(completionTokens);
        String suggestJson = readGatedSuggestionsJson(outcome, projectId, actorId);
        if (!suggestJson.isEmpty()) {
            payload.append(",\"suggest\":").append(suggestJson);
        }
        payload.append('}');
        coreGateway.appendChatFrame(sessionId, assistantMessageId, "done",
                payload.toString(), null);
        coreGateway.closeChatMessage(assistantMessageId, "done");
    }

    /**
     * Intersection-gated suggestion array for the done frame: reads the
     * first accepted chat.suggest call of the transcript (single-source
     * predicate in {@code SuggestTool}, so refused shapes never surface),
     * then drops every entry whose capability is not mounted for this
     * actor/project. Empty string = nothing to append. The mounted-set read
     * re-queries the same core assembly door the loop uses — no second
     * intersection definition exists (R40); it fires only when the model
     * actually suggested something.
     */
    private String readGatedSuggestionsJson(ReActOutcome outcome, long projectId, Long actorId) {
        java.util.List<com.sixbean.testpike.aiservice.tool.domain.component
                .SuggestTool.Suggestion> proposals =
                com.sixbean.testpike.aiservice.tool.domain.component.SuggestTool
                        .readAcceptedSuggestions(outcome);
        if (proposals.isEmpty()) {
            return "";
        }
        java.util.Set<String> allowed;
        try {
            allowed = reActRunner.allowedCapabilities(projectId, actorId);
        } catch (RuntimeException doorFault) {
            log.warn("扩展建议交集复校不可达，本轮不附建议 reason={}", doorFault.getMessage());
            return "";
        }
        var kept = com.sixbean.testpike.aiservice.tool.domain.component.SuggestTool
                .filterAllowed(proposals, allowed);
        if (kept.isEmpty()) {
            return "";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < kept.size(); i++) {
            json.append(i > 0 ? "," : "")
                    .append("{\"text\":\"").append(escape(kept.get(i).text()))
                    .append("\",\"capability\":\"").append(escape(kept.get(i).capability()))
                    .append("\"}");
        }
        return json.append(']').toString();
    }

    /**
     * Failure posture: journal the human-readable reason as an error frame and
     * seal the turn. When the ledger door itself is down, the journaling fails
     * too — that original failure surfaces (fail-closed: an unjournaled run
     * must retry, never silently succeed).
     */
    private void journalFailure(long sessionId, long assistantMessageId, RuntimeException cause) {
        // One fold for the screen sentence (ChatFailureWording): technical
        // messages (parser internals such as "closed", guard internals,
        // upstream status text) never reach the user; the class + the
        // working resend door do. The raw text stays in the log line below.
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName()
                : cause.getMessage();
        try {
            coreGateway.appendChatFrame(sessionId, assistantMessageId, "error",
                    ChatFailureWording.screenSentence(cause), null);
            coreGateway.closeChatMessage(assistantMessageId, "error");
        } catch (RuntimeException ledgerDown) {
            // When the journaling failure IS the original failure (the sink
            // threw it mid-stream), addSuppressed would self-suppress and
            // blow past the intended surface — rethrow the original as-is.
            if (ledgerDown != cause) {
                ledgerDown.addSuppressed(cause);
                throw ledgerDown;
            }
            throw cause;
        }
        log.warn("对话运行失败已入账 sessionId={} messageId={} reason={}",
                sessionId, assistantMessageId, reason);
    }
}
