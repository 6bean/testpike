package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftSeqAllocator;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.UndoCaseDraftService;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Undo behind the dialog is deliberately shaped as another rollback card,
 * never as a second write path :
 * <ol>
 * <li>the session's most recent applied case write (CASE_WRITE or
 * CASE_ROLLBACK, ordered by the door's own applied stamp) supplies the
 * undo target — the version that was live before that accept;</li>
 * <li>the case is re-read at card time: foreign-project attribution and an
 * already-back-at-target case refuse BEFORE anything is filed, the same
 * guards the landing door will re-run;</li>
 * <li>the card is a pure ledger insert (pending, anchored on the session's
 * newest sealed assistant row for read-back, the V363 project anchor)
 * whose payload matches the rollback kind exactly,
 * so confirming it dispatches into {@link CaseRollbackDraftStrategy}
 * untouched — append-only snapshots, optimistic lock, change_type=4
 * audit all come from the shared rollback chain.</li>
 * </ol>
 * A second undo simply finds the undo card itself as the latest applied row
 * and cuts the next card onto ITS pre-accept version; no once-only special
 * case exists, and same-target no-op refusals stay the shared chain's call.
 * <p>Create-style applies carry no pre-accept version anchor: undo refuses
 * there rather than guessing that deleting a case was intended.
 */
@Service
public class UndoCaseDraftServiceImpl implements UndoCaseDraftService {

    /** Undo only ever aims at case writes; other kinds have their own doors. */
    private static final String SELECT_LATEST_APPLIED_CASE_WRITE =
            "SELECT id, object_type, target_id, base_version "
                    + "FROM tp_ai_chat_draft "
                    + "WHERE session_id = :sessionId "
                    + "AND object_type IN (:writeKind, :rollbackKind) AND status = :applied "
                    + "ORDER BY applied_at DESC NULLS LAST, id DESC "
                    + "LIMIT 1";

    /** Ledger insert mirrors the system-origin shape; the presentation
     *  anchor (:messageId) is resolved by the caller — see
     *  {@link #SELECT_NEWEST_SEALED_ASSISTANT}. */
    private static final String INSERT_PENDING_CARD =
            "INSERT INTO tp_ai_chat_draft "
                    + "(session_id, message_id, project_id, object_type, target_id, base_version, "
                    + " schema_version, summary, payload_json, status, seq) "
                    + "VALUES (:sessionId, :messageId, :projectId, :objectType, :targetId, :baseVersion, "
                    + "        '1', :summary, CAST(:payloadJson AS jsonb), :status, :seq) "
                    + "RETURNING id";

    /**
 * Presentation anchor : a pending card nobody can see can
 * never be confirmed, and the sealed-history door folds cards keyed by
 * message_id only. So the undo card joins the session's newest sealed
 * assistant row — the same fold every other confirmable card rides, with
 * zero new read endpoints and no second card UI. Honest absence: no
 * sealed assistant yet means a NULL anchor (the ledger row still files;
 * nothing is invented).
 */
    private static final String SELECT_NEWEST_SEALED_ASSISTANT =
            "SELECT id FROM tp_ai_chat_message "
                    + "WHERE session_id = :sessionId AND role = 'assistant' "
                    + "AND status <> 'streaming' ORDER BY id DESC LIMIT 1";

    private final NamedParameterJdbcTemplate named;
    private final ChatSessionService sessionService;
    private final CaseService caseService;
    private final DraftSeqAllocator draftSeqAllocator;
    private final ObjectMapper objectMapper;

    public UndoCaseDraftServiceImpl(NamedParameterJdbcTemplate named,
                                    ChatSessionService sessionService,
                                    CaseService caseService,
                                    DraftSeqAllocator draftSeqAllocator,
                                    ObjectMapper objectMapper) {
        this.named = named;
        this.sessionService = sessionService;
        this.caseService = caseService;
        this.draftSeqAllocator = draftSeqAllocator;
        this.objectMapper = objectMapper;
    }

    @Override
    public long undoLastAcceptedWrite(long sessionId) {
        long projectId = sessionService.findById(sessionId).projectId();
        Map<String, Object> applied = findLatestApplied(sessionId);
        Object targetRaw = applied.get("target_id");
        Object anchorRaw = applied.get("base_version");
        if (!(targetRaw instanceof Number caseAnchor) || !(anchorRaw instanceof Number base)) {
            throw new ServiceException(
                    "撤销已拒绝：最近一次已接受的用例写入缺少可回退的版本锚点（targetId 或 baseVersion 为空——"
                            + "新建用例没有前置版本，撤销不会代为删除用例）——"
                            + "如需弃用该用例，请到左侧菜单「用例管理」搜索用例编号后点行操作「删除」；"
                            + "如需回到更早版本，请在右侧对话坞说「回滚用例 <编号> 到版本 <版本号>」"
                            + "（示例：回滚用例 1024 到版本 2；入口：左侧菜单「用例管理」 / 右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long caseId = caseAnchor.longValue();
        int preAcceptVersion = base.intValue();
        CaseInfo current = caseService.get(caseId);
        if (current.projectId() != projectId) {
            throw new ServiceException(
                    "用例 " + caseId + " 不属于当前会话所在项目 " + projectId
                            + "（该用例归属项目 " + current.projectId() + "），回滚草案登记已拒绝——"
                            + "请在顶栏「选择项目」切换到用例归属的项目后，在右侧对话坞重新发起撤销"
                            + "（入口：顶栏「选择项目」 → 右侧对话坞 → 草案卡）",
                    BizErrorCode.FORBIDDEN);
        }
        if (current.version() == preAcceptVersion) {
            throw new ServiceException(
                    "用例 " + caseId + " 当前已是接受前的版本 " + preAcceptVersion
                            + "，没有需要撤销的内容，本次未登记回滚草案——"
                            + "如需回滚到其他版本，请在右侧对话坞说「回滚用例 " + caseId
                            + " 到版本 <版本号>」（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("caseId", caseId);
        payload.put("targetVersion", preAcceptVersion);
        payload.put("baseVersion", current.version());
        payload.put("reason", "撤销会话内最近一次已接受的用例写入（草案 #"
                + ((Number) applied.get("id")).longValue() + "）");
        Long cardId = named.queryForObject(INSERT_PENDING_CARD,
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("messageId", newestSealedAssistantId(sessionId))
                        .addValue("projectId", projectId)
                        .addValue("objectType", DraftObjectType.CASE_ROLLBACK.wireName)
                        .addValue("targetId", caseId)
                        .addValue("baseVersion", current.version())
                        .addValue("summary", "回滚用例到版本 " + preAcceptVersion
                                + "（撤销上一次已接受的写入）")
                        .addValue("payloadJson", payload.toString())
                        .addValue("status", DraftConfirmationServiceImpl.STATUS_PENDING)
                        .addValue("seq", draftSeqAllocator.nextSeq(caseId)),
                Long.class);
        if (cardId == null) {
            throw new ServiceException("撤销草案登记失败（未生成草案卡片编号，用例数据未被改动）——"
                    + "请在右侧对话坞重新说「撤销上一次改动」重试（入口：右侧对话坞 → 草案卡）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return cardId;
    }

    /** The newest applied case write of this session, or a loud refusal. */
    private Map<String, Object> findLatestApplied(long sessionId) {
        List<Map<String, Object>> rows = named.queryForList(SELECT_LATEST_APPLIED_CASE_WRITE,
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("writeKind", DraftObjectType.CASE_WRITE.wireName)
                        .addValue("rollbackKind", DraftObjectType.CASE_ROLLBACK.wireName)
                        .addValue("applied", DraftConfirmationServiceImpl.STATUS_APPLIED));
        if (rows.isEmpty()) {
            throw new ServiceException(
                    "当前会话还没有已接受的用例写入，没有可撤销的内容——"
                            + "请先让 AI 生成用例草案并在草案卡点「应用草案」"
                            + "（入口：右侧对话坞 → 草案卡 → 「应用草案」），有写入后即可撤销",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    /** The session's presentation anchor; honest null when nothing is sealed. */
    private Long newestSealedAssistantId(long sessionId) {
        List<Long> ids = named.queryForList(SELECT_NEWEST_SEALED_ASSISTANT,
                new MapSqlParameterSource("sessionId", sessionId), Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
