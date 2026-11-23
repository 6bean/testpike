package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionProposalView;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionSignalView;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.EvolutionNegativeGate;
import com.sixbean.testpike.core.aichat.domain.service.ChatFrameService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.EvolutionProposalService;
import com.sixbean.testpike.core.aichat.domain.service.EvolutionSignalService;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual self-evolution proposal run (AF-i phase 2b-ii).
 * <p>One trigger, three generators, one ledger: an admin fires a run for a
 * month window, the observation rollup decides which families have evidence
 * to complain about, and each generator assembles AT MOST one draft card —
 * a prompt-template revision, a tool-definition draft for a failing
 * integration that has no registered row, or a grounding-document revision.
 * Cards land as pending EVOLUTION_PROPOSAL rows on a dedicated evolution
 * session owned by the triggering human, reached through the same frame
 * ledger every dialog card uses, so the confirmation door, the ticket and
 * the audit stay single-sourced (red line 6, no parallel draft path).
 * <p>Refusals held fail-closed:
 * <ul>
 *   <li>platform-wide runs (null project) — a proposal must land in a real
 *       project lane; the H1 session anchoring has nothing to anchor to;</li>
 *   <li>a second run while the same project+month batch still has pending
 *       cards — stale-card exclusion (AM-batch ledger rule: at most one
 *       open batch per target), the refusal writes nothing;</li>
 *   <li>any candidate surface name on the four-class negative list — the
 *       gate runs at assembly time, so a forbidden proposal never even
 *       becomes a confirmable row (no escape hatch: the list is code).</li>
 * </ul>
 * <p>Honesty rules: an empty window produces zero cards and no session row;
 * triggers come only from recomputed ledgers (thumbs-down, follow-ups, error
 * turns, tool failures) — the loop never fabricates a proposal to justify
 * itself, and it never auto-lands or auto-publishes anything.
 */
@Service
public class EvolutionProposalServiceImpl implements EvolutionProposalService {

    private static final Logger log = LoggerFactory.getLogger(EvolutionProposalServiceImpl.class);

    /** Proposal families carried in the payload's subKind discriminator. */
    static final String SUBKIND_PROMPT_TPL = "PROMPT_TPL";
    static final String SUBKIND_TOOL_DEF = "TOOL_DEF";
    static final String SUBKIND_GROUNDING = "GROUNDING";

    /** Prompt family the revision proposal targets (dialog answer quality). */
    private static final String PROMPT_NAME = "dialog-answer-prompt";
    private static final String PROMPT_DOMAIN = "对话";

    private static final int MAX_COMPLAINT_SAMPLES = 5;
    private static final int SUMMARY_MAX = 500;
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final EvolutionSignalService signalService;
    private final ChatSessionService sessionService;
    private final ChatFrameService frameService;
    private final PromptTemplateService promptTemplateService;
    private final AuditLogService auditLogService;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvolutionProposalServiceImpl(EvolutionSignalService signalService,
                                        ChatSessionService sessionService,
                                        ChatFrameService frameService,
                                        PromptTemplateService promptTemplateService,
                                        AuditLogService auditLogService,
                                        NamedParameterJdbcTemplate named) {
        this.signalService = signalService;
        this.sessionService = sessionService;
        this.frameService = frameService;
        this.promptTemplateService = promptTemplateService;
        this.auditLogService = auditLogService;
        this.named = named;
    }

    @Override
    public EvolutionProposalView generate(String month, Long projectId, long operatorId) {
        if (projectId == null) {
            throw new ServiceException(
                    "自进化提案需要选定项目（提案落在具体项目的对话会话里，全平台视图不支持生成），请先点顶栏「选择项目」切到具体项目，再在对话里重新发起生成",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Fail-closed window key: reuse the observation door's own guard by
        // reading the rollup first — an impossible month never reaches a write.
        EvolutionSignalView signals = signalService.observe(month, projectId);
        requireNoPendingBatch(month, projectId);

        YearMonth window = YearMonth.parse(month, MONTH_FORMAT);
        List<String> complaints = recentComplaints(window, projectId);
        List<EvolutionProposalView.ProposalRow> proposals = new ArrayList<>();

        boolean hasComplaint = signals.thumbsDownCount() > 0
                || signals.sessionsWithFollowUp() > 0
                || signals.errorTurns() > 0;
        String monthWindow = month;

        // Assembly-time negative gate (single point, four classes, code-only):
        // the candidate surface names of every generator are checked BEFORE any
        // session/message/draft row is written.
        List<String> candidates = new ArrayList<>(List.of(PROMPT_NAME, PROMPT_DOMAIN,
                "grounding-doc:" + month));
        for (EvolutionSignalView.ToolFailureRow row : signals.toolFailures()) {
            candidates.add(row.toolName());
        }
        EvolutionNegativeGate.requireAllowed(candidates);

        long sessionId = 0L;
        // The run opens only on the project's own complaint evidence: the tool
        // failure ledger is platform-wide (invocations carry no project column),
        // so a project window with nothing to complain about must not have a
        // batch fabricated for it — and the same failing tool would otherwise
        // duplicate across every project's runs. Registered failures ride along
        // an evidence-backed run instead.
        if (hasComplaint) {
            ChatSessionInfo session = sessionService.create(projectId, operatorId,
                    "自进化提案 " + monthWindow, 2, "{}");
            sessionId = session.id();
            proposals.add(landCard(sessionId, SUBKIND_PROMPT_TPL, PROMPT_NAME,
                    promptPayload(monthWindow, signals, complaints),
                    promptSummary(monthWindow, signals)));
            proposals.add(landCard(sessionId, SUBKIND_GROUNDING,
                    "接地文档修订 " + monthWindow,
                    groundingPayload(monthWindow, complaints),
                    groundingSummary(monthWindow, complaints)));
            for (EvolutionSignalView.ToolFailureRow failure : unregisteredFailures(signals)) {
                proposals.add(landCard(sessionId, SUBKIND_TOOL_DEF, failure.toolName(),
                        toolPayload(failure), toolSummary(monthWindow, failure)));
            }
        }
        log.info("evolution run month={} project={} cards={} session={}",
                month, projectId, proposals.size(), sessionId);
        return new EvolutionProposalView(month, projectId, sessionId, proposals);
    }

    /**
     * One-click rollback of a landed prompt-family proposal (AF-i phase 3).
     * <p>Guard order mirrors the apply door: card exists (40401) → session
     * ownership (40301) → applied, never rolled back (40904) → the family
     * has a version chain. The chain itself is borrowed from the prompt
     * domain: the row the proposal landed as carries applied_version; the
     * source to restore is that version minus one inside the same family —
     * {@code rollback(sourceRowId)} copies it forward as the newest version
     * with rollback_to set, exactly the human page's precedent, and the
     * draft row is stamped so a second click refuses instead of double
     * copying. Tool/grounding families have no version chain and honestly
     * refuse (an applied tool card stays as a DRAFT tool the human can
     * delete from the tools page; a grounding card is an ordinary document).
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public PromptTemplateItem rollback(long draftId, long operatorId) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("id", draftId);
        List<Map<String, Object>> rows = named.query("""
                SELECT id, session_id, object_type, target_id,
                       payload_json::json ->> 'subKind' AS sub_kind, status
                FROM tp_ai_chat_draft WHERE id = :id
                """, key, (rs, i) -> Map.of(
                "id", rs.getLong("id"),
                "sessionId", rs.getLong("session_id"),
                "objectType", rs.getString("object_type"),
                "targetId", rs.getObject("target_id") == null
                        ? 0L : rs.getLong("target_id"),
                "subKind", String.valueOf(rs.getString("sub_kind")),
                "status", rs.getInt("status")));
        if (rows.isEmpty()) {
            throw new ServiceException("自进化提案卡不存在或已失效，请在「历史对话」打开原会话核对这张卡片，找不到就在「新建对话」重新发起提案生成", BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> card = rows.get(0);
        if (!DraftObjectType.EVOLUTION_PROPOSAL.wireName.equals(card.get("objectType"))) {
            throw new ServiceException("该卡片不是自进化提案，回滚入口只认提案卡；请在「历史对话」打开提案会话，在「提示词模板修订提案」卡上点「回滚提案」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        sessionService.requireOwned(((Number) card.get("sessionId")).longValue(), operatorId);
        if (((Number) card.get("status")).intValue() != 2) {
            throw new ServiceException("该提案尚未确认生效，没有可回滚的变更；请先在提案卡上点「确认草案」再点「应用草案」，生效后才能回滚",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        String rolledBack = named.getJdbcTemplate().queryForObject(
                "SELECT context_json ->> 'rolledBackFrom' FROM tp_ai_chat_draft WHERE id = ?",
                String.class, draftId);
        if (rolledBack != null && !rolledBack.isBlank()) {
            throw new ServiceException("该提案已回滚，不能重复回滚；模板已恢复到提案前版本，如需再次应用请在「新建对话」重新发起提案生成", BizErrorCode.RESOURCE_CONFLICT);
        }
        if (!SUBKIND_PROMPT_TPL.equals(card.get("subKind"))) {
            throw new ServiceException("该提案类型（工具/接地文档）没有版本链，不支持一键回滚；"
                    + "工具草稿请前往「工具管理」删除，接地文档请前往「知识库」处理",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long appliedTemplateRowId = ((Number) card.get("targetId")).longValue();
        PromptTemplateItem landed = promptTemplateService.detail(appliedTemplateRowId);
        if (landed == null) {
            throw new ServiceException("请先前往「提示词模板」页核对版本链（提案落地的模板版本已不存在，id=" + appliedTemplateRowId
                    + "），确认缺失后可在该页重建所需版本", BizErrorCode.NOT_FOUND);
        }
        // The version the proposal replaced = same family, one step back.
        PromptTemplateItem source = null;
        for (PromptTemplateItem item : promptTemplateService.listVersions(
                landed.name(), landed.domain())) {
            if (item.version() == landed.version() - 1) {
                source = item;
                break;
            }
        }
        if (source == null) {
            throw new ServiceException("该模板族没有可回退的前一版本（提案是第一个版本），无法一键回滚；"
                    + "请前往「提示词模板」页手工重建目标内容作为当前版本", BizErrorCode.RESOURCE_CONFLICT);
        }
        PromptTemplateItem rolled = promptTemplateService.rollback(source.id(), operatorId);
        // Stamp the once-only mark into the draft's context jsonb and record
        // the attribution: who, which run, which versions moved.
        named.update("""
                UPDATE tp_ai_chat_draft
                SET context_json = coalesce(context_json, '{}'::jsonb)
                        || jsonb_build_object('rolledBackFrom', :landedVersion,
                                              'rollbackVersion', :newVersion),
                    updated_at = now()
                WHERE id = :id AND status = 2
                """, new MapSqlParameterSource()
                .addValue("landedVersion", landed.version())
                .addValue("newVersion", rolled.version())
                .addValue("id", draftId));
        ChatSessionInfo runSession = sessionService.findById(
                ((Number) card.get("sessionId")).longValue());
        java.util.Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("draftId", draftId);
        after.put("evolutionSessionId", runSession.id());
        after.put("templateName", landed.name());
        after.put("undoneVersion", landed.version());
        after.put("restoredFromVersion", source.version());
        after.put("newVersion", rolled.version());
        auditLogService.record(new AuditLogInput(operatorId, runSession.projectId(),
                "ai_evolution_proposal", String.valueOf(draftId),
                "evolution-proposal-rollback", null, after));
        log.info("evolution rollback draft={} template={} undone={} new={}",
                draftId, landed.name(), landed.version(), rolled.version());
        return rolled;
    }

    /** Failing tools with no registered row: a proposal to document the integration. */
    private List<EvolutionSignalView.ToolFailureRow> unregisteredFailures(
            EvolutionSignalView signals) {
        List<EvolutionSignalView.ToolFailureRow> out = new ArrayList<>();
        for (EvolutionSignalView.ToolFailureRow row : signals.toolFailures()) {
            Integer registered = named.queryForObject(
                    "SELECT count(*) FROM tp_tool WHERE name = :name",
                    new MapSqlParameterSource().addValue("name", row.toolName()), Integer.class);
            if (registered == null || registered == 0) {
                out.add(row);
            }
        }
        return out;
    }

    /** One open batch per target: pending cards of the same project+month block a re-run. */
    private void requireNoPendingBatch(String month, Long projectId) {
        Integer pending = named.queryForObject("""
                SELECT count(*) FROM tp_ai_chat_draft d
                JOIN tp_ai_chat_session s ON s.id = d.session_id
                WHERE d.object_type = :objectType AND d.status = 1
                  AND s.project_id = :projectId AND s.title = :title
                """, new MapSqlParameterSource()
                .addValue("objectType", DraftObjectType.EVOLUTION_PROPOSAL.wireName)
                .addValue("projectId", projectId)
                .addValue("title", "自进化提案 " + month), Integer.class);
        if (pending != null && pending > 0) {
            throw new ServiceException("请先在「历史对话」处理未完成的自进化提案卡（本月还有 " + pending
                    + " 张待确认卡），在卡上依次点「确认草案」与「应用草案」后再重新生成",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    private List<String> recentComplaints(YearMonth window, Long projectId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("start", window.atDay(1).atStartOfDay())
                .addValue("end", window.plusMonths(1).atDay(1).atStartOfDay())
                .addValue("projectId", projectId)
                .addValue("cap", MAX_COMPLAINT_SAMPLES);
        return named.query("""
                SELECT m.feedback_reason FROM tp_ai_chat_message m
                JOIN tp_ai_chat_session s ON s.id = m.session_id
                WHERE m.feedback = -1 AND m.feedback_reason IS NOT NULL
                  AND m.created_at >= :start AND m.created_at < :end
                  AND s.project_id = :projectId
                ORDER BY m.id DESC LIMIT :cap
                """, params, (rs, i) -> rs.getString(1));
    }

    // ---- payload builders (deterministic recomputation, zero model calls) ----

    private String promptPayload(String month, EvolutionSignalView signals, List<String> complaints) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("subKind", SUBKIND_PROMPT_TPL);
        payload.put("name", PROMPT_NAME);
        payload.put("domain", PROMPT_DOMAIN);
        payload.put("content", answerPromptBody(month, signals, complaints));
        return write(payload);
    }

    private String answerPromptBody(String month, EvolutionSignalView signals,
                                   List<String> complaints) {
        StringBuilder body = new StringBuilder("""
                你是测试平台的在线 AI 助手。回答前先复述用户目标；给出结论时附带可点开的依据。\
                本月的观察信号提示回答未落到点，遵循以下加强条款：
                1. 引用具体对象（用例/批次/文档名称）而不是泛化描述；
                2. 数据类问题先报口径再报数字；
                3. 无法回答时明确说不知道并指出原因，不要即兴编造。
                """);
        if (!complaints.isEmpty()) {
            body.append("\n近一月点踩原因样本（生成于 ").append(month).append("）：\n");
            for (String complaint : complaints) {
                body.append("- ").append(complaint).append('\n');
            }
        }
        body.append("""

                追问率 = """).append(percent(signals.followUpRate()))
                .append("%，点踩 ").append(signals.thumbsDownCount())
                .append(" 次，错误回合 ").append(signals.errorTurns()).append(" 个。");
        return body.toString();
    }

    private String promptSummary(String month, EvolutionSignalView signals) {
        return truncate("提示词模板修订提案（观察窗口 " + month + "）：追问率 "
                + percent(signals.followUpRate()) + "%、点踩 " + signals.thumbsDownCount()
                + " 次、错误回合 " + signals.errorTurns()
                + " 个。确认后将追加为新版本模板，旧版本保留可一键回滚。");
    }

    private String groundingPayload(String month, List<String> complaints) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("subKind", SUBKIND_GROUNDING);
        payload.put("filename", "evolution-grounding-" + month + ".md");
        StringBuilder doc = new StringBuilder(
                "# 接地文档修订建议（" + month + "）\n\n以下主题本月被点踩但未在知识库命中，建议补充接地文档：\n");
        if (complaints.isEmpty()) {
            doc.append("- （本月点踩均未附原因句，按追问率与错误回合触发本提案）\n");
        }
        for (String complaint : complaints) {
            doc.append("- ").append(complaint).append('\n');
        }
        payload.put("content", doc.toString());
        return write(payload);
    }

    private String groundingSummary(String month, List<String> complaints) {
        return truncate("接地文档修订提案（观察窗口 " + month + "）：附 "
                + complaints.size() + " 条点踩原因样本。确认后将生成新的知识文档并进入解析入库链。");
    }

    private String toolPayload(EvolutionSignalView.ToolFailureRow failure) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("subKind", SUBKIND_TOOL_DEF);
        payload.put("name", failure.toolName());
        payload.put("description", "自进化提案：近一月失败 " + failure.failureCount()
                + " 次的未登记工具，先建档纳管再决定实现形态");
        payload.put("body", "# " + failure.toolName() + "\n\n"
                + "失败次数：" + failure.failureCount() + "\n"
                + "最近错误：" + (failure.lastError() == null ? "（留痕未带错误文本）" : failure.lastError()) + "\n\n"
                + "提案内容：把该调用面登记为文档类工具，补齐契约说明与失败处理口径；"
                + "登记后仍需人工评审发布，不会自动生效。");
        return write(payload);
    }

    private String toolSummary(String month, EvolutionSignalView.ToolFailureRow failure) {
        return truncate("工具定义提案（观察窗口 " + month + "）：" + failure.toolName()
                + " 失败 " + failure.failureCount() + " 次且未登记。确认=建档为草稿工具，发布仍需人工评审。");
    }

    /**
     * Land one proposal as a pending draft card through the frame ledger
     * (same door the dialog uses: assistant message + draft frame → the
     * projection in ChatFrameService lands the trusted snapshot row).
     */
    private EvolutionProposalView.ProposalRow landCard(long sessionId, String subKind,
                                                       String targetName, String payloadJson,
                                                       String summary) {
        long messageId = frameService.openMessage(sessionId, "assistant", summary);
        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("objectType", DraftObjectType.EVOLUTION_PROPOSAL.wireName);
        meta.put("targetId", 0L);
        meta.put("actionJson", payloadJson);
        frameService.appendFrame(sessionId, messageId, ChatFrameType.DRAFT,
                summary, meta.toString());
        frameService.closeMessage(messageId, "done");
        Long draftId = named.queryForObject(
                "SELECT id FROM tp_ai_chat_draft WHERE message_id = :messageId",
                new MapSqlParameterSource().addValue("messageId", messageId), Long.class);
        if (draftId == null) {
            // Defensive: the projection never returns null for a well-formed
            // meta, but a lost card must not masquerade as a landed one.
            throw new ServiceException("自进化提案未能落账，请在「历史对话」核对本次运行是否已生成卡片，未生成请在「新建对话」重新发起生成",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return new EvolutionProposalView.ProposalRow(draftId, subKind, targetName, summary);
    }

    private String write(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new ServiceException("自进化提案内容无法序列化，请在「新建对话」精简描述后重新发起生成；持续失败请把提案月份与项目反馈管理员排查", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String percent(double rate) {
        return String.format("%.1f", rate * 100);
    }

    private static String truncate(String summary) {
        return summary.length() <= SUMMARY_MAX ? summary : summary.substring(0, SUMMARY_MAX);
    }
}
