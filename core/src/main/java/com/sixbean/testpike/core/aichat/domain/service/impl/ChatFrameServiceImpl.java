package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenKit;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;
import com.sixbean.testpike.core.aichat.api.dto.InjectLanding;
import com.sixbean.testpike.core.aichat.api.dto.DraftLanding;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.component.CredentialScreenshotGovernance;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftSeqAllocator;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.infrastructure.dataobject.ChatDraftRow;
import com.sixbean.testpike.core.aichat.infrastructure.dataobject.ChatFrameRow;
import com.sixbean.testpike.core.aichat.infrastructure.dataobject.ChatMessageRow;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.component.dedup.DedupCandidate;
import com.sixbean.testpike.core.common.component.dedup.DedupGate;
import com.sixbean.testpike.core.common.component.dedup.DedupLevel;
import com.sixbean.testpike.core.common.component.dedup.DedupVerdict;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.datafactory.domain.service.DataFactoryService;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Frame ledger implementation .
 * <p>Sequence allocation = {@code SELECT last_seq ... FOR UPDATE} on the session
 * row, then increment, then ledger insert — all inside one transaction. The row
 * lock is what makes the cursor trustworthy: concurrent producers (reAct rounds,
 * retrieval callbacks) interleave, yet the seq stays gap-free and never reused,
 * so Last-Event-ID dedup on reconnect is exact.
 * <p>Replay honesty: only a {@code streaming} message is replayable. A finished
 * block returns nothing — the message body already holds the answer; re-emitting
 * it as a live stream would fake a "still working" experience and double-render
 * on the client.
 * <p>Abort keeps the partial: content is rebuilt from the message's delta frames
 * (the very text the user watched) before status flips to {@code aborted}.
 */
@Service
public class ChatFrameServiceImpl implements ChatFrameService {

    private static final Set<String> ROLES = Set.of("user", "assistant", "system");
    private static final Set<String> TERMINAL_STATUSES = Set.of("done", "error");
    private static final String STATUS_STREAMING = "streaming";
    private static final String STATUS_ABORTED = "aborted";

    private static final String MESSAGE_COLUMNS = """
            SELECT id, session_id, role, content, reasoning, status, seq_from, seq_to,
                   prompt_tokens, completion_tokens, created_at, feedback, feedback_reason
            FROM tp_ai_chat_message
            """;

    /** Feedback states the per-turn thumbs door accepts (1 👍 / -1 👎 / 0 cleared). */
    private static final Set<Integer> FEEDBACK_STATES = Set.of(-1, 0, 1);
    private static final int FEEDBACK_REASON_MAX_LENGTH = 512;
    /** Skip sentinel of the frame write door: 0 means the frame did not land. */
    private static final long SKIP_SENTINEL = 0L;

    private static final Logger log = LoggerFactory.getLogger(ChatFrameServiceImpl.class);

    private final NamedParameterJdbcTemplate named;
    private final ChatSessionService sessionService;
    private final RequirementService requirementService;
    private final CaseService caseService;
    /** Shared three-tier duplicate gate (single source, AL batch ). */
    private final DedupGate dedupGate;
    /** Reader for the terminal frame's usage payload (shared, stateless). */
    private final ObjectMapper usageReader = new ObjectMapper();

    /** V330 hot-reload key: server-side landing anchor for edit-anchored draft kinds. */
    private static final String ANCHOR_SERVER_SIDE_KEY = "ai.chat.draft-anchor-server-side.enabled";

    /** Live-version reader for stub edit anchors . */
    private final MockStubService mockStubService;

    /** Live-version reader for defect edit anchors . */
    private final DefectLibraryService defectLibraryService;

    /** Live-version readers for the anchor families . */
    private final TagService tagService;
    private final DataFactoryService dataFactoryService;
    private final SkillService skillService;
    private final DraftSeqAllocator draftSeqAllocator;

    public ChatFrameServiceImpl(NamedParameterJdbcTemplate named, ChatSessionService sessionService,
                                RequirementService requirementService, CaseService caseService,
                                DedupGate dedupGate, MockStubService mockStubService,
                                DefectLibraryService defectLibraryService,
                                TagService tagService, DataFactoryService dataFactoryService,
                                SkillService skillService,
                                DraftSeqAllocator draftSeqAllocator) {
        this.named = named;
        this.sessionService = sessionService;
        this.requirementService = requirementService;
        this.caseService = caseService;
        this.dedupGate = dedupGate;
        this.mockStubService = mockStubService;
        this.defectLibraryService = defectLibraryService;
        this.tagService = tagService;
        this.dataFactoryService = dataFactoryService;
        this.skillService = skillService;
        this.draftSeqAllocator = draftSeqAllocator;
    }

    /**
     * The V330 anchor switch is factory-on (R32): only an explicit "0" turns
     * the server-side re-anchor off; a missing row keeps the documented
     * default. Read fresh per landing — config hot-reload needs no restart.
     */
    private boolean serverSideAnchorEnabled() {
        try {
            String value = named.queryForObject(
                    "SELECT config_value FROM tp_sys_config WHERE config_key = :key",
                    new MapSqlParameterSource("key", ANCHOR_SERVER_SIDE_KEY), String.class);
            return !"0".equals(value);
        } catch (org.springframework.dao.DataAccessException unread) {
            return true;
        }
    }

    /** Current optimistic version of the row an edit proposal is anchored on (null = gone). */
    private Integer currentAnchorVersion(DraftObjectType objectType, long targetId) {
        try {
            // AM batch slices 1+3: every edit-anchored kind reads its
            // live version here. The write-instant optimistic check stays
            // inside each kind's own landing strategy (single source, red
            // line 6 — this only pins the card-time anchor honestly).
            // PROMPT_TPL_WRITE and PLAN_WRITE are exempt by row review: the
            // dialog only ever emits create proposals for them (no target
            // row to anchor on), so they stay out of the switch until an
            // edit emitter exists.
            return switch (objectType) {
                case REQUIREMENT_WRITE -> requirementService.get(targetId).version();
                case CASE_WRITE -> caseService.get(targetId).version();
                case MOCK_STUB_WRITE -> mockStubService.get(targetId).version();
                case DEFECT_WRITE -> defectLibraryService.get(targetId).version();
                case TAG_WRITE -> tagService.currentVersion(targetId);
                case DATA_FACTORY_WRITE -> dataFactoryService.currentVersion(targetId);
                case SKILL_WRITE -> skillService.get(targetId).version();
                case FLAKY_ISOLATION -> caseService.get(targetId).version();
                default -> null;
            };
        } catch (ServiceException gone) {
            return null; // 40401: the row was deleted or hidden by a soft delete
        }
    }

    /**
 * Ask the shared DedupGate at card time whether this requirement CREATE
 * proposal already has a row (AL batch , design A7: the verdict is
 * burned into the card, never into an auto-write). EXACT/HIGH with a
 * still-live anchor weaves the dual-payload form the landing strategy
 * consumes and returns {payloadJson, summarySuffix}; everything else —
 * no hit, mid band (its near-miss audit is written by the gate itself),
 * unparsable body, vanished row — is an honest absence: the single-shape
 * card stays exactly as emitted and the write-instant L1 re-check inside
 * the shared requirement chain still guards the quiet window. Red line
 * 10c holds either way: only the human's one click on one branch lands
 * anything.
 */
    private String[] weaveRequirementDualBranch(long sessionId, String actionJson) {
        try {
            JsonNode body = usageReader.readTree(actionJson);
            if (!body.isObject()) {
                return null;
            }
            String title = body.path("title").asText("");
            if (title.isBlank()) {
                return null;
            }
            long projectId = sessionService.findById(sessionId).projectId();
            DedupVerdict verdict = dedupGate.evaluateRequirement(projectId, title);
            DedupCandidate hit = verdict.matched();
            boolean knownHit = verdict.level() == DedupLevel.EXACT
                    || verdict.level() == DedupLevel.HIGH;
            if (!knownHit || hit == null) {
                return null;
            }
            Integer liveVersion = currentAnchorVersion(DraftObjectType.REQUIREMENT_WRITE, hit.id());
            if (liveVersion == null) {
                return null; // the hit row vanished mid-scan — nothing to point at
            }
            ObjectNode update = ((ObjectNode) body).deepCopy();
            update.put("targetId", hit.id());
            update.put("baseVersion", liveVersion);
            ObjectNode dual = usageReader.createObjectNode();
            dual.set("create", body);
            dual.set("update", update);
            return new String[] {
                    dual.toString(),
                    "；命中既有需求点 #" + hit.id() + "，确认时可二选一：接受新建，或接受变更 #" + hit.id()
                            + "（变更直接写入既有需求点，不会重复建档）"
            };
        } catch (JsonProcessingException | RuntimeException unreadable) {
            log.warn("需求草案出卡查重跳过（载荷或门判定不可读，保持单形态出卡） reason={}",
                    unreadable.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public long openMessage(long sessionId, String role, String content) {
        return openMessage(sessionId, role, content, null);
    }

    @Override
    @Transactional
    public long openMessage(long sessionId, String role, String content, String questionContextJson) {
        if (!ROLES.contains(role)) {
            throw new ServiceException("消息角色只能是 user/assistant/system，请按正确写法重发：用户提问行用 user、AI 回复用 assistant、背景指令用 system", BizErrorCode.VALIDATION_ERROR);
        }
        sessionService.findById(sessionId);
        Long id = named.queryForObject(
                "INSERT INTO tp_ai_chat_message (session_id, role, content, status, question_context) "
                        + "VALUES (:sessionId, :role, :content, '" + STATUS_STREAMING + "', "
                        + "CAST(:questionContext AS jsonb)) RETURNING id",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("role", role)
                        .addValue("content", content)
                        .addValue("questionContext", questionContextJson),
                Long.class);
        return id;
    }

    /**
     * Per-turn thumbs feedback landing (self-evolution loop intake): the
     * message row is the single observation ledger — one UPDATE, no second
     * table. A complaint sentence only ever rides on a 👎 (-1); every other
     * state write clears it, so a toggled signal never leaves a stale text
     * behind. Ownership runs through the session door, the same horizontal
     * check every message path applies.
     */
    @Override
    @Transactional
    public ChatMessageInfo recordFeedback(long messageId, long userId, int feedback, String reason) {
        if (!FEEDBACK_STATES.contains(feedback)) {
            throw new ServiceException("反馈取值只能是 1（有帮助）/ -1（没有帮助）/ 0（取消），请直接在回答底部点击「有帮助」或「没有帮助」按钮，无需手填数字",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String stripped = reason == null || reason.isBlank() ? null : reason.strip();
        if (stripped != null && stripped.length() > FEEDBACK_REASON_MAX_LENGTH) {
            throw new ServiceException("反馈原因超长，请在「没有帮助」后的原因输入框内精简内容后重新提交，最长 " + FEEDBACK_REASON_MAX_LENGTH + " 字",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ChatMessageRow message = requireMessage(messageId);
        sessionService.requireOwned(message.getSessionId(), userId);
        named.update("UPDATE tp_ai_chat_message SET feedback = :feedback, feedback_reason = :reason "
                        + "WHERE id = :messageId",
                new MapSqlParameterSource()
                        .addValue("feedback", feedback)
                        .addValue("reason", feedback == -1 ? stripped : null)
                        .addValue("messageId", messageId));
        return requireMessage(messageId).toInfo();
    }

    @Override
    @Transactional
    public long appendFrame(long sessionId, long messageId, ChatFrameType type,
                            String payload, String metaJson) {
        if (type == null) {
            throw new ServiceException("帧类型不能为空，请按正确写法传入帧枚举名（示例：text、delta、tool_call）", BizErrorCode.VALIDATION_ERROR);
        }
        if (!type.isLedgerPersisted()) {
            throw new ServiceException(type.wireName()
                    + " 帧只是传输层保活注释，不进入帧账本，也不占用会话序号；请把需要留存的文字按正确写法改用 text 帧写入", BizErrorCode.VALIDATION_ERROR);
        }
        // Purge-race posture: the project purge is the only delete channel for
        // the message ledger, so a streaming turn can outlive its message row.
        // The live service log showed 72 first-frame FK violations from exactly
        // this race. A vanished message retires honestly at this door instead
        // of raising one data-integrity error per remaining frame. The session
        // read gate is re-entered first so an unknown session still raises its
        // named refusal (single source) — the quiet skip only covers a live
        // session whose message row is gone. Checking before allocateSeq keeps
        // the skip from burning a seq, so surviving numbering stays gapless.
        if (!messageRowExists(messageId)) {
            sessionService.findById(sessionId);
            return SKIP_SENTINEL;
        }
        long seq = allocateSeq(sessionId);
        // Conditional insert closes the window between the door check above and
        // this write: under a committed purge the statement lands nothing
        // instead of tripping tp_ai_chat_frame_message_id_fkey.
        int landedFrames = named.update("INSERT INTO tp_ai_chat_frame (session_id, message_id, seq, event_type, payload, meta_json) "
                        + "SELECT :sessionId, :messageId, :seq, :type, :payload, CAST(:meta AS jsonb) "
                        + "WHERE EXISTS (SELECT 1 FROM tp_ai_chat_message WHERE id = :messageId)",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("messageId", messageId)
                        .addValue("seq", seq)
                        .addValue("type", type.wireName())
                        .addValue("payload", payload)
                        .addValue("meta", metaJson == null || metaJson.isBlank() ? "{}" : metaJson));
        if (landedFrames == 0) {
            // Rare race: the message vanished after the door check. The frame
            // did not land, so nothing downstream (window seal, draft card,
            // token accumulation) may run; the allocated seq stays burned —
            // session numbering never promises contiguity.
            return SKIP_SENTINEL;
        }
        named.update("UPDATE tp_ai_chat_message SET seq_from = :seq WHERE id = :messageId AND seq_from IS NULL",
                new MapSqlParameterSource().addValue("seq", seq).addValue("messageId", messageId));
        if (type == ChatFrameType.DRAFT) {
            DraftLanding landed = landDraft(sessionId, messageId, seq, payload, metaJson);
            if (landed != null && landed.draftId() != null) {
                // The confirmation door is addressed by draft id, which only
                // exists after landing (BIGSERIAL at this write point). Pinning
                // it onto the frame meta here — same transaction, same ledger —
                // keeps the frame the single source the card renders from: the
                // emitter upstream cannot know it, and a client-side lookup by
                // messageId would be a second copy of the mapping.
                // When the card-time duplicate check wove a two-choice form,
                // that woven payload is pinned on the very same write: the live
                // stream is what the human chooses on, so the verdict must ride
                // the frame the live card reads, never only the ledger behind it.
                if (landed.wovenActionJson() != null) {
                    named.update("UPDATE tp_ai_chat_frame SET meta_json = jsonb_set("
                                    + "jsonb_set(meta_json, '{draftId}', to_jsonb(:draftId::bigint)), "
                                    + "'{actionJson}', to_jsonb(CAST(:woven AS text))) "
                                    + "WHERE session_id = :sessionId AND seq = :seq",
                            new MapSqlParameterSource()
                                    .addValue("draftId", landed.draftId())
                                    .addValue("woven", landed.wovenActionJson())
                                    .addValue("sessionId", sessionId)
                                    .addValue("seq", seq));
                } else {
                    named.update("UPDATE tp_ai_chat_frame "
                                    + "SET meta_json = jsonb_set(meta_json, '{draftId}', to_jsonb(:draftId::bigint)) "
                                    + "WHERE session_id = :sessionId AND seq = :seq",
                            new MapSqlParameterSource()
                                    .addValue("draftId", landed.draftId())
                                    .addValue("sessionId", sessionId)
                                    .addValue("seq", seq));
                }
            }
        }
        if (type == ChatFrameType.DONE) {
            accumulateSessionTokens(sessionId, messageId, payload);
        }
        return seq;
    }

    /**
 * Session token ledger written at the done-frame point (E9 session
 * attribution, phase 1; per-message usage landing = phase 3):
 * the done frame is the one place a turn's usage numbers land in the
 * ledger, so the session total accumulates right here — the same door
 * every turn crosses, never a second write path that could drift from
 * what the user watched. The same parse also records the numbers on the
 * assistant message they belong to: the cost view reconciles the session
 * total against message rows, so both must come from one read of one
 * payload. The session increment is atomic
 * ({@code total_tokens = total_tokens + :delta}); concurrent turns of one
 * session each count exactly once. A payload that carries no parseable
 * usage JSON (the round-limit error posture, a legacy shape) leaves both
 * ledgers untouched — honest zeros beat a fabricated number, and the
 * frame write itself never fails over a billing read.
 */
    private void accumulateSessionTokens(long sessionId, long messageId, String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonNode usage = usageReader.readTree(payload);
            long prompt = usage.path("promptTokens").asLong(0L);
            long completion = usage.path("completionTokens").asLong(0L);
            long delta = prompt + completion;
            if (delta <= 0) {
                return;
            }
            named.update("UPDATE tp_ai_chat_session "
                            + "SET total_tokens = total_tokens + :delta, updated_at = now() "
                            + "WHERE id = :sessionId",
                    new MapSqlParameterSource()
                            .addValue("delta", delta)
                            .addValue("sessionId", sessionId));
            // Same read, same transaction: the message row carries the turn's
            // own numbers so the cost view's session-vs-message reconciliation
            // compares one ledger against its own detail, not two guesses.
            named.update("UPDATE tp_ai_chat_message "
                            + "SET prompt_tokens = :prompt, completion_tokens = :completion "
                            + "WHERE id = :messageId",
                    new MapSqlParameterSource()
                            .addValue("prompt", prompt)
                            .addValue("completion", completion)
                            .addValue("messageId", messageId));
        } catch (JsonProcessingException | RuntimeException quiet) {
            // Ledger-first: an unusable usage payload is a missing number in
            // the cost view, not a lost answer — the frame row already stands.
            log.warn("session token ledger skipped sessionId={}: {}", sessionId, quiet.getMessage());
        }
    }

    /**
 * Draft-frame projection into the generic confirmable snapshot ledger
 * (AO batch knife 3b , generalized in stage 1 into
 * tp_ai_chat_draft with an object_type discriminator). Why at the write
 * point: the confirmation door needs a core-side trustworthy copy of the
 * anchors (target/base version/proposal body) — re-reading them from a
 * client-supplied payload later would let a forged draft self-apply.
 * Landing is best-effort and NEVER breaks the ledger: a meta that misses
 * an anchor, names an unregistered kind, or carries a kind whose anchors
 * are incomplete is skipped with a log line (a half-trusted snapshot must
 * not become a confirmable row, and the user's answer stream outranks the
 * card). Insert is idempotent on (message_id, dedup_key) — the message is
 * the GROUP of proposals one answer minted, the fingerprint is one
 * proposal's content: a reconnect that re-appends the same draft cannot
 * mint a second ticket, while a second distinct proposal of the same
 * answer gets its own card .
 * Nothing here writes any business table — that stays behind the confirm
 * token and the per-kind write strategy.
 * Returns the landed draft row id (replay included — it points at the same
 * row) so the caller can pin it onto the frame meta; null when the landing
 * was skipped, which keeps the card's confirm door honestly absent.
 */
    private DraftLanding landDraft(long sessionId, long messageId, long frameSeq,
                                   String summary, String metaJson) {
        if (metaJson == null || metaJson.isBlank()) {
            return null;
        }
        try {
            JsonNode meta = usageReader.readTree(metaJson);
            // Unregistered kinds never become confirmable: no strategy = no
            // honest way to write the proposal later, so skip loudly via log.
            String rawType = meta.path("objectType").asText("");
            var resolved = DraftObjectType.resolveOrDefault(rawType);
            if (resolved.isEmpty()) {
                log.warn("draft landing skipped messageId={}: unregistered objectType={}",
                        messageId, rawType);
                return null;
            }
            var objectType = resolved.get();
            long executionId = meta.path("executionId").asLong(0L);
            long caseId = meta.path("caseId").asLong(0L);
            // Generic target anchor : every write kind pins the
            // row it acts on under "targetId"; the repair-era "caseId" token
            // stays read for its own contract, defaulting targetId onto it so
            // a case-edit card keeps landing exactly as before.
            long targetId = meta.path("targetId").asLong(caseId);
            int baseVersion = meta.path("baseVersion").asInt(0);
            String actionJson = meta.path("actionJson").asText("");
            String schemaVersion = meta.path("schemaVersion").asText("");
            // Anchor contract of the repair kind (unchanged from the repair-era
            // landing; each kind validates its own shape here — this is why
            // the validation stays kind-aware).
            if (objectType == DraftObjectType.SCRIPT_REPAIR
                    && (executionId <= 0 || caseId <= 0 || baseVersion <= 0
                            || actionJson.isBlank() || schemaVersion.isBlank())) {
                return null;
            }
            // Edit-anchored kinds contract ( stage 2, generalized in
            // stage 2; AL batch adds REQUIREMENT_WRITE): the
            // proposal body is mandatory; an edit proposal must carry an
            // optimistic anchor — unless the V330 server-side anchor switch
            // is on (factory default), in which case the CURRENT row version
            // is re-read at landing and overwrites the echoed base_version,
            // so a stale echo from the model still applies against the live
            // row instead of being refused by its own age. Switch off = the
            // echoed anchor is kept exactly as emitted (old behavior).
            boolean editAnchoredKind = objectType == DraftObjectType.CASE_WRITE
                    || objectType == DraftObjectType.REQUIREMENT_WRITE
                    || objectType == DraftObjectType.MOCK_STUB_WRITE
                    || objectType == DraftObjectType.DEFECT_WRITE
                    || objectType == DraftObjectType.TAG_WRITE
                    || objectType == DraftObjectType.DATA_FACTORY_WRITE
                    || objectType == DraftObjectType.SKILL_WRITE
                    || objectType == DraftObjectType.FLAKY_ISOLATION;
            if (editAnchoredKind && actionJson.isBlank()) {
                return null;
            }
            if (editAnchoredKind && targetId > 0) {
                if (serverSideAnchorEnabled()) {
                    Integer liveVersion = currentAnchorVersion(objectType, targetId);
                    if (liveVersion == null) {
                        log.warn("draft landing skipped messageId={}: anchored row {} gone",
                                messageId, targetId);
                        return null;
                    }
                    baseVersion = liveVersion;
                } else if (baseVersion <= 0) {
                    return null;
                }
            }
            // Tag-dictionary anchor contract : the proposal body
            // is mandatory; rename/merge always name their target tag, a
            // create carries no anchor yet.
            if (objectType == DraftObjectType.TAG_WRITE && actionJson.isBlank()) {
                return null;
            }
            // Interface pass-through anchor contract (AI pass-through batch P1): the card is a
            // statement about one catalog entry, so a payload-less proposal has nothing to confirm
            // and never becomes confirmable. Which entry, and which project it lands in, is decided
            // again by the landing strategy — this door only refuses an empty shape.
            if (objectType == DraftObjectType.API_ACCESS_WRITE && actionJson.isBlank()) {
                return null;
            }
            // Evolution-proposal anchor contract (AF-i phase 2b-ii): the card
            // is meaningless without its payload (subKind + family body live
            // inside it), so a blank proposal body never becomes confirmable.
            if (objectType == DraftObjectType.EVOLUTION_PROPOSAL && actionJson.isBlank()) {
                return null;
            }
            // AL batch card-time burn-in (design A7 / ruling 10a): the
            // shared DedupGate verdict is asked BEFORE the card is journaled,
            // so a known hit surfaces on the card as the two-choice form the
            // landing strategy already speaks — {"create":…,"update":…} with
            // the hit row's id and live version pre-installed as the optimistic
            // anchor. The human still unlocks the write with one click on one
            // branch (red line 10c: no zero-click apply). No known hit keeps
            // the single create payload exactly as emitted; the write-instant
            // L1 re-check inside RequirementService still closes the quiet
            // window behind the card (TOCTOU), and the mid band keeps its
            // near-miss audit trail without weaving a card face it cannot name.
            String wovenAction = null;
            if (objectType == DraftObjectType.REQUIREMENT_WRITE && targetId <= 0
                    && !actionJson.isBlank()) {
                String[] woven = weaveRequirementDualBranch(sessionId, actionJson);
                if (woven != null) {
                    actionJson = woven[0];
                    wovenAction = woven[0];
                    summary = (summary == null ? "" : summary) + woven[1];
                }
            }
            // Proposal-granularity idempotency anchor :
            // fingerprint of the content as landed, computed AFTER the card-time
            // weave so the key always describes the payload actually stored.
            String dedupKey = GenKit.md5(objectType.wireName + "|"
                    + (targetId > 0 ? Long.toString(targetId) : "") + "|" + actionJson);
            // Whether this call inserted or hit the idempotent conflict, the
            // row keyed by (message_id, dedup_key) is the one draft this frame
            // points at — distinct proposals on one answer mint distinct cards.
            named.update("""
                    INSERT INTO tp_ai_chat_draft
                        (session_id, message_id, frame_seq, dedup_key, object_type, target_id,
                         base_version, schema_version, model_name, summary, payload_json, context_json, seq)
                    VALUES (:sessionId, :messageId, :frameSeq, :dedupKey, :objectType, :targetId,
                            :baseVersion, :schemaVersion, :modelName, :summary, :payloadJson,
                            CAST(:contextJson AS jsonb), :seq)
                    ON CONFLICT (message_id, dedup_key) DO NOTHING
                    """,
                    new MapSqlParameterSource()
                            .addValue("sessionId", sessionId)
                            .addValue("messageId", messageId)
                            .addValue("frameSeq", frameSeq)
                            .addValue("dedupKey", dedupKey)
                            .addValue("objectType", objectType.wireName)
                            .addValue("targetId", targetId > 0 ? targetId : null)
                            .addValue("seq", draftSeqAllocator.nextSeq(
                                    targetId > 0 ? targetId : null))
                            .addValue("baseVersion", baseVersion > 0 ? baseVersion : null)
                            .addValue("schemaVersion", blankToNull(schemaVersion))
                            .addValue("modelName", meta.path("model").asText(null))
                            .addValue("summary", summary == null ? null
                                    : summary.substring(0, Math.min(summary.length(), 500)))
                            .addValue("payloadJson", actionJson)
                            .addValue("contextJson", executionId > 0
                                    ? "{\"executionId\":" + executionId + "}" : null));
            Long landedId = named.queryForObject("SELECT id FROM tp_ai_chat_draft "
                            + "WHERE message_id = :messageId AND dedup_key = :dedupKey",
                    new MapSqlParameterSource()
                            .addValue("messageId", messageId)
                            .addValue("dedupKey", dedupKey), Long.class);
            return new DraftLanding(landedId, wovenAction);
        } catch (JsonProcessingException | RuntimeException e) {
            // Ledger-first: a failed projection is a lost card, not a lost answer.
            log.warn("draft landing skipped messageId={}: {}", messageId, e.getMessage());
            return null;
        }
    }

    /** Blank guard for optional anchor columns (empty string never hits the ledger). */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public long lastSeq(long sessionId) {
        return sessionService.findById(sessionId).lastSeq();
    }

    /**
 * Mid-turn steering intake : the injection rides the
 * same seq allocation as every other ledger frame, so reload/replay shows
 * it exactly as delivered. Honest absence - with no streaming turn this
 * returns null and lands nothing; the caller then sends the text through
 * the regular message channel instead of silently dropping the user's
 * words. Ownership runs through the session door like every message path.
 */
    @Override
    @Transactional
    public InjectLanding injectUserMessage(long sessionId, long userId, String content) {
        if (content == null || content.isBlank()) {
            throw new ServiceException("用户注入内容不能为空，请在对话窗口下方「输入框」填写补充说明后重新发送",
                    BizErrorCode.VALIDATION_ERROR);
        }
        sessionService.requireOwned(sessionId, userId);
        ChatMessageRow streaming = findStreamingMessage(sessionId);
        if (streaming == null) {
            return null;
        }
        long seq = appendFrame(sessionId, streaming.getId(), ChatFrameType.USER_INJECT,
                content.strip(), null);
        return new InjectLanding(streaming.getId(), seq);
    }

    @Override
    public List<ChatFrame> findInjectsAfter(long sessionId, long afterSeq) {
        return named.query("SELECT id, session_id, message_id, seq, event_type, payload, meta_json::text AS meta_json "
                        + "FROM tp_ai_chat_frame WHERE session_id = :sessionId AND event_type = :type AND seq > :after "
                        + "ORDER BY seq",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("type", ChatFrameType.USER_INJECT.wireName())
                        .addValue("after", afterSeq),
                new BeanPropertyRowMapper<>(ChatFrameRow.class))
                .stream().map(ChatFrameRow::toInfo).toList();
    }

    /**
 * Cross-message image replay read door : ownership
 * first (AF-g — a stranger cannot enumerate another user's booked picture
 * keys), then the newest ledger rows whose state frame carries a
 * {@code chatImages} booking, newest seq first, at most {@code limit}.
 * {@code jsonb_exists} stands in for the "?" operator (the JDBC driver
 * reserves it); the raw meta_json text crosses the door untouched because
 * parsing the typed value is the scanner's single-sourced job.
 */
    @Override
    public List<String> findChatImageMetas(long userId, long sessionId, int limit) {
        sessionService.requireOwned(sessionId, userId);
        if (limit <= 0) {
            return List.of();
        }
        return named.queryForList("SELECT meta_json::text FROM tp_ai_chat_frame "
                        + "WHERE session_id = :sessionId AND event_type = :stateType "
                        + "AND jsonb_exists(meta_json, 'chatImages') "
                        + "ORDER BY seq DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("stateType", ChatFrameType.STATE.wireName())
                        .addValue("limit", limit),
                String.class);
    }

    @Override
    public List<ChatFrame> replayAfter(long sessionId, long cursor) {
        ChatMessageRow streaming = findStreamingMessage(sessionId);
        if (streaming == null) {
            // Every block is finished: serve the body, replay nothing.
            return List.of();
        }
        return named.query("SELECT id, session_id, message_id, seq, event_type, payload, meta_json::text AS meta_json "
                        + "FROM tp_ai_chat_frame WHERE session_id = :sessionId AND message_id = :messageId AND seq > :cursor "
                        + "ORDER BY seq",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("messageId", streaming.getId())
                        .addValue("cursor", cursor),
                new BeanPropertyRowMapper<>(ChatFrameRow.class))
                .stream().map(ChatFrameRow::toInfo).toList();
    }

    @Override
    public List<ChatFrame> replayAfter(long userId, long sessionId, long cursor) {
        sessionService.requireOwned(sessionId, userId);
        return replayAfter(sessionId, cursor);
    }

    @Override
    public List<ChatFrame> replayMessageBlock(long userId, long messageId, long cursor) {
        ChatMessageRow message = requireMessage(messageId);
        sessionService.requireOwned(message.getSessionId(), userId);
        return named.query("SELECT id, session_id, message_id, seq, event_type, payload, meta_json::text AS meta_json "
                        + "FROM tp_ai_chat_frame WHERE message_id = :messageId AND seq > :cursor "
                        + "ORDER BY seq",
                new MapSqlParameterSource()
                        .addValue("messageId", messageId)
                        .addValue("cursor", cursor),
                new BeanPropertyRowMapper<>(ChatFrameRow.class))
                .stream().map(ChatFrameRow::toInfo).toList();
    }

    @Override
    @Transactional
    public ChatMessageInfo closeMessage(long messageId, String status) {
        if (!TERMINAL_STATUSES.contains(status)) {
            throw new ServiceException("消息收口状态只能是 done 或 error，请按正确写法传入其一：回答正常完成用 done，本轮失败用 error", BizErrorCode.VALIDATION_ERROR);
        }
        ChatMessageRow message = requireMessage(messageId);
        if (!STATUS_STREAMING.equals(message.getStatus())) {
            throw new ServiceException("该回答状态已结束，请在「输入框」重新提问来修正答案（当前状态 " + message.getStatus() + "，重复发送结束指令已拒绝）",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        sealWindow(messageId, status);
        // Auto-title seam : sealing a user turn WITH a body is the first
        // question of its thread — this is the one door every question crosses,
        // so the naming rule stays single-sourced (sessions that carry a real
        // title are untouched inside applyAutoTitle).
        if ("user".equals(message.getRole())) {
            sessionService.applyAutoTitle(message.getSessionId(), message.getContent());
        }
        if ("assistant".equals(message.getRole())) {
            observePregenSilence(message.getSessionId(), messageId, status);
        }
        return requireMessage(messageId).toInfo();
    }

    @Override
    public List<ChatMessageInfo> listSealedMessages(long userId, long sessionId) {
        sessionService.requireOwned(sessionId, userId);
        List<ChatMessageRow> rows = named.query(MESSAGE_COLUMNS + " WHERE session_id = :sessionId "
                        + "AND status <> '" + STATUS_STREAMING + "' ORDER BY id",
                new MapSqlParameterSource("sessionId", sessionId),
                new BeanPropertyRowMapper<>(ChatMessageRow.class));
        foldDrafts(rows);
        foldGoalPlanTail(sessionId, rows);
        foldCredentialMarkers(rows);
        return rows.stream().map(this::withRebuiltBody).map(this::withRebuiltUsage)
                .map(ChatMessageRow::toInfo).toList();
    }

    @Override
    public ChatMessageInfo activeStreamingMessage(long userId, long sessionId) {
        sessionService.requireOwned(sessionId, userId);
        ChatMessageRow streaming = findStreamingMessage(sessionId);
        // Honest absence: null when nothing is unsealed — the caller folds the
        // sealed body from the ledger instead, no fake pointer is booked.
        return streaming == null ? null : streaming.toInfo();
    }

    /**
     * Draft-card fold (self-evolution closed-loop visibility): a confirmable
     * card landed during the live stream lives in tp_ai_chat_draft, so a
     * reloaded page would show an answer with no card and the booked proposal
     * could never be confirmed. One batch query keyed by the sealed message
     * ids — never a per-row lookup — folded back onto each row. Messages
     * without a draft keep an honest empty list (toInfo never emits null).
     */
    private void foldDrafts(List<ChatMessageRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<Long> messageIds = rows.stream().map(ChatMessageRow::getId).collect(Collectors.toSet());
        List<ChatDraftRow> drafts = named.query("SELECT id, message_id, object_type, summary, "
                        // jsonb_exists() stands in for the "?" operator: the JDBC
                        // driver reserves "?" as a parameter placeholder.
                        + "payload_json, status, target_id, base_version, applied_version, "
                        + "jsonb_exists(context_json, 'rolledBackFrom') AS rolled_back "
                        + "FROM tp_ai_chat_draft WHERE message_id IN (:messageIds) ORDER BY id",
                new MapSqlParameterSource("messageIds", messageIds),
                new BeanPropertyRowMapper<>(ChatDraftRow.class));
        Map<Long, List<ChatDraftRow>> byMessage = drafts.stream()
                .collect(Collectors.groupingBy(ChatDraftRow::getMessageId));
        rows.forEach(row -> row.setDrafts(byMessage.getOrDefault(row.getId(), List.of())));
    }

    /**
 * Credential-screenshot marker fold ( piece E, D2 = delete +
 * placeholder): the original pixels of a booked screenshot are purged
 * at the terminal state, so a reloaded panel must name what happened
 * instead of rendering nothing. One batch read of the frame ledger for
 * the sealed message ids — never a per-row lookup — reusing the
 * governance component's marker-key constant (single source, no second
 * spelling of {@code credLedger}). Messages without a booking keep an
 * honest false; the fold never invents a placeholder for an ordinary
 * attachment.
 */
    private void foldCredentialMarkers(List<ChatMessageRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<Long> messageIds = rows.stream().map(ChatMessageRow::getId).collect(Collectors.toSet());
        List<Long> booked = named.queryForList(
                "SELECT DISTINCT message_id FROM tp_ai_chat_frame "
                        // jsonb_exists() stands in for the "?" operator: the JDBC
                        // driver reserves "?" as a parameter placeholder.
                        + "WHERE message_id IN (:messageIds) "
                        + "AND jsonb_exists(meta_json, :markerKey)",
                new MapSqlParameterSource()
                        .addValue("messageIds", messageIds)
                        .addValue("markerKey", CredentialScreenshotGovernance.META_CRED_KEY),
                Long.class);
        Set<Long> bookedIds = Set.copyOf(booked);
        rows.forEach(row -> row.setCredLedger(bookedIds.contains(row.getId())));
    }

    /**
 * Goal-plan tail fold : the live stream folds the
 * open-tail state frame into the collapsible progress card, but sealed
 * history rows never replay frames through this door — so the session's
 * current ledger joins onto its newest sealed assistant row and the
 * reload view can render the same card (zero-loss refresh). One batch
 * read of the ledger, fail-soft like the ai-service store: an absent,
 * fully-closed or corrupt ledger projects nothing instead of a fake
 * card, and a door error is journaled never thrown. The sentence is the
 * single-source copy of ChatRunOrchestrator.appendGoalPlanResumePointer
 * (ai-service) — the front-end card door recognises the reload fold by
 * that pinned wording, so a drift here degrades to the honest process
 * line, never a silently wrong card.
 */
    private void foldGoalPlanTail(long sessionId, List<ChatMessageRow> rows) {
        ChatMessageRow newestAssistant = null;
        for (ChatMessageRow row : rows) {
            if ("assistant".equals(row.getRole())) {
                newestAssistant = row;
            }
        }
        if (newestAssistant == null) {
            return;
        }
        List<Map<String, Object>> ledgerRows;
        try {
            ledgerRows = named.queryForList("SELECT plan_version, revision, steps_json"
                            + " FROM tp_ai_chat_goal_plan WHERE session_id = :sessionId"
                            + " ORDER BY plan_version DESC, revision DESC LIMIT 1",
                    new MapSqlParameterSource("sessionId", sessionId));
        } catch (RuntimeException doorDown) {
            log.warn("goal-plan fold skipped sessionId={}: {}", sessionId, doorDown.getMessage());
            return;
        }
        if (ledgerRows.isEmpty()) {
            return;
        }
        Map<String, Object> ledger = ledgerRows.get(0);
        List<JsonNode> open = openSteps(String.valueOf(ledger.get("steps_json")));
        if (open.isEmpty()) {
            return;
        }
        List<String> openTitles = open.stream()
                .map(step -> step.path("title").asText("")).toList();
        List<String> openStates = open.stream()
                .map(step -> step.path("state").asText(STATE_PENDING)).toList();
        newestAssistant.setGoalPlan(new com.sixbean.testpike.core.aichat.api.dto.ChatGoalPlanInfo(
                ((Number) ledger.get("plan_version")).intValue(),
                ((Number) ledger.get("revision")).intValue(),
                open.size(), openTitles, openStates, goalResumeSentence(openTitles)));
    }

    /** The still-open steps (pending or blocked) in stored order — each node
     *  keeps its own state, so the reload card renders a blocked step as
     *  blocked instead of flattening every open row to pending. */
    private List<JsonNode> openSteps(String stepsJson) {
        try {
            List<JsonNode> open = new ArrayList<>();
            for (JsonNode step : ledgerReader.readTree(stepsJson)) {
                String state = step.path("state").asText("");
                String title = step.path("title").asText("");
                if ((STATE_PENDING.equals(state) || STATE_BLOCKED.equals(state)) && !title.isBlank()) {
                    open.add(step);
                }
            }
            return open;
        } catch (Exception corrupt) {
            // A corrupt stored list reads as "no ledger" (fail-soft above).
            return List.of();
        }
    }

    /** Verbatim wording of the live open-tail frame (single source: ai-service). */
    private String goalResumeSentence(List<String> openTitles) {
        return "目标计划还有 " + openTitles.size() + " 步未完成（" + String.join("、", openTitles)
                + "）。回复「" + RESUME_COMMAND + "」即可接着执行。";
    }

    private static final String STATE_PENDING = "pending";
    private static final String STATE_BLOCKED = "blocked";
    /** The resume trigger word, single-source copy of GoalPlanLedger.RESUME_COMMAND. */
    private static final String RESUME_COMMAND = "继续";
    /** Ledger payload reader (same fail-soft posture as the usage reader). */
    private final ObjectMapper ledgerReader = new ObjectMapper();

    /**
     * A streamed answer lives in its delta frames, not the message row: only
     * abort writes the partial back. Rebuild the body here (same concat the
     * abort path uses) so a reloaded page shows what the user watched live —
     * rows that already carry a body (user echoes, aborted turns) pass through.
     */
    private ChatMessageRow withRebuiltBody(ChatMessageRow row) {
        if (row.getContent() != null || !"assistant".equals(row.getRole())) {
            return row;
        }
        String rebuilt = named.query("SELECT payload FROM tp_ai_chat_frame "
                        + "WHERE message_id = :messageId AND event_type = 'delta' ORDER BY seq",
                new MapSqlParameterSource("messageId", row.getId()),
                (rs, rowNum) -> rs.getString(1))
                .stream().reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
                .toString();
        row.setContent(rebuilt.isEmpty() ? null : rebuilt);
        return row;
    }

    /**
     * The usage numbers the live view showed ride the terminal done frame; the
     * message row itself keeps zeros until the run writes them back. Rebuild
     * them on read (same ledger-single-source posture as the answer body) so a
     * reloaded page attributes tokens exactly like the live stream did. A done
     * frame without a JSON usage payload (legacy shape) leaves the row as-is.
     */
    private ChatMessageRow withRebuiltUsage(ChatMessageRow row) {
        if (!"assistant".equals(row.getRole())
                || (row.getPromptTokens() != 0 || row.getCompletionTokens() != 0)) {
            return row;
        }
        List<String> payloads = named.query("SELECT payload FROM tp_ai_chat_frame "
                        + "WHERE message_id = :messageId AND event_type = 'done' ORDER BY seq DESC LIMIT 1",
                new MapSqlParameterSource("messageId", row.getId()),
                (rs, rowNum) -> rs.getString(1));
        if (payloads.isEmpty()) {
            return row;
        }
        try {
            JsonNode usage = usageReader.readTree(payloads.get(0));
            row.setPromptTokens(usage.path("promptTokens").asInt(0));
            row.setCompletionTokens(usage.path("completionTokens").asInt(0));
        } catch (JsonProcessingException quiet) {
            // Not a JSON usage payload — the stored row values stand (honest zeros).
        }
        return row;
    }

    @Override
    @Transactional
    public ChatMessageInfo abort(long messageId) {
        ChatMessageRow message = requireMessage(messageId);
        if (STATUS_ABORTED.equals(message.getStatus())) {
            return message.toInfo(); // idempotent: a replayed abort changes nothing
        }
        if (!STATUS_STREAMING.equals(message.getStatus())) {
            throw new ServiceException("该消息已完成，无需中断；「停止回答」只对正在输出的回答生效，可在回答底部点击该按钮", BizErrorCode.STATE_MACHINE_REJECT);
        }
        // Keep the partial honest: rebuild the body from the deltas the user watched.
        String partial = named.query("SELECT payload FROM tp_ai_chat_frame "
                        + "WHERE message_id = :messageId AND event_type = 'delta' ORDER BY seq",
                new MapSqlParameterSource("messageId", messageId),
                (rs, rowNum) -> rs.getString(1))
                .stream().reduce(new StringBuilder(), StringBuilder::append, StringBuilder::append)
                .toString();
        named.update("UPDATE tp_ai_chat_message SET content = :content WHERE id = :messageId AND content IS NULL",
                new MapSqlParameterSource().addValue("content", partial).addValue("messageId", messageId));
        sealWindow(messageId, STATUS_ABORTED);
        return requireMessage(messageId).toInfo();
    }

    @Override
    public String messageStatus(long messageId) {
        return requireMessage(messageId).getStatus();
    }

    /**
 * One readable WARN line when a pre-generation-anchored assistant turn
 * (its session context_json carries the analysisId) seals without ever
 * landing a draft card: the model answered in plain text and a reviewer
 * watching the disposition view would otherwise see pure silence
 * . Scoped hard — plain
 * dialog turns never carry this noise, and the terminal close door
 * above is the single emission seam (never double-emit on abort/replay).
 */
    private void observePregenSilence(long sessionId, long messageId, String status) {
        List<String> anchors = named.queryForList(
                "SELECT context_json->>'analysisId' FROM tp_ai_chat_session WHERE id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId), String.class);
        if (anchors.isEmpty() || anchors.get(0) == null) {
            return;
        }
        Integer drafts = named.queryForObject(
                "SELECT count(1) FROM tp_ai_chat_draft WHERE message_id = :messageId",
                new MapSqlParameterSource("messageId", messageId), Integer.class);
        if (drafts != null && drafts > 0) {
            return; // the healthy path: a card really landed — stay quiet
        }
        log.warn("pregen draft card never landed: analysisId={} sessionId={} messageId={} status={}"
                        + " — the model answered in plain text; check prompt reachability",
                anchors.get(0), sessionId, messageId, status);
    }

    /** Row-locked increment of the session cursor (no gaps under concurrency). */
    private long allocateSeq(long sessionId) {
        // The locked read is deliberately a list: with queryForObject an unknown
        // session id raised the framework "no rows" result, which the global
        // handler folds into the generic internal code whose only on-screen
        // advice is to retry later — a retry that can never succeed. An empty
        // read therefore goes through the session read gate, the single source
        // that names the missing conversation and the way back to it; rewording
        // that sentence here would give one judgement two sources.
        List<Long> locked = named.queryForList(
                "SELECT last_seq FROM tp_ai_chat_session WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", sessionId), Long.class);
        long current = locked.isEmpty() ? sessionService.findById(sessionId).lastSeq()
                : (locked.get(0) == null ? 0L : locked.get(0));
        long next = current + 1L;
        named.update("UPDATE tp_ai_chat_session SET last_seq = :next, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("next", next).addValue("id", sessionId));
        return next;
    }

    private void sealWindow(long messageId, String status) {
        named.update("UPDATE tp_ai_chat_message m SET status = :status, seq_to = ("
                        + "SELECT COALESCE(MAX(f.seq), m.seq_from) FROM tp_ai_chat_frame f WHERE f.message_id = m.id) "
                        + "WHERE m.id = :messageId",
                new MapSqlParameterSource().addValue("status", status).addValue("messageId", messageId));
    }

    private ChatMessageRow findStreamingMessage(long sessionId) {
        List<ChatMessageRow> rows = named.query(MESSAGE_COLUMNS + " WHERE session_id = :sessionId "
                        + "AND status = '" + STATUS_STREAMING + "' ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource("sessionId", sessionId),
                new BeanPropertyRowMapper<>(ChatMessageRow.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Row-existence read for the frame door: true while the message lives. */
    private boolean messageRowExists(long messageId) {
        Integer rows = named.queryForObject("SELECT count(*) FROM tp_ai_chat_message WHERE id = :id",
                new MapSqlParameterSource("id", messageId), Integer.class);
        return rows != null && rows > 0;
    }

    private ChatMessageRow requireMessage(long messageId) {
        List<ChatMessageRow> rows = named.query(MESSAGE_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", messageId),
                new BeanPropertyRowMapper<>(ChatMessageRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("对话消息不存在，请在「历史对话」面板打开该会话确认消息是否仍在，若找不到请点击「新建对话」重新提问", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }
}
