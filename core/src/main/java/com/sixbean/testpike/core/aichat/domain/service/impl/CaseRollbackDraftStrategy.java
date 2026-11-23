package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackResult;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Case-rollback landing strategy behind the generic draft door: a confirmed
 * dialog card ("roll the case back to vN", "undo the last accept") replays
 * the case through the SAME CaseService.rollback chain the case page uses —
 * append-only snapshots, optimistic version lock, script restore and audit
 * all come free, never a parallel rollback path. Undo of a just-accepted
 * change is itself a rollback onto the version that was live before the
 * accept, so the history keeps rolling forward (an event, never an erasure).
 * <p>Attribution guard: a card aimed at a case outside the session's project
 * is refused (40301) before anything moves — same rule as every other case
 * kind. A stale optimistic anchor is refused naming both versions, unless
 * the human answers the shared "apply this version" choice, which replays
 * the rollback onto the row's current version.
 * <p>Business rules (unknown target version, rollback-to-current no-op,
 * corrupted snapshot) stay single-sourced in CaseServiceImpl: whatever the
 * chain refuses surfaces as the chain's own human-readable error.
 */
@Service
public class CaseRollbackDraftStrategy implements DraftWriteStrategy {

    private final CaseService caseService;
    private final ChatSessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CaseRollbackDraftStrategy(CaseService caseService,
                                     ChatSessionService sessionService) {
        this.caseService = caseService;
        this.sessionService = sessionService;
    }

    @Override
    public String objectType() {
        return DraftObjectType.CASE_ROLLBACK.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        long sessionId = ((Number) draftRow.get("session_id")).longValue();
        long projectId = sessionService.findById(sessionId).projectId();
        Object targetRaw = draftRow.get("target_id");
        if (!(targetRaw instanceof Number caseAnchor)) {
            throw new ServiceException("回滚草案缺少目标用例，无法确认写入（请重新让 AI 定位要回滚的用例）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long caseId = caseAnchor.longValue();
        CaseInfo current = caseService.get(caseId);
        if (current.projectId() != projectId) {
            throw new ServiceException("该草案指向的用例不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        int targetVersion = readTargetVersion(draftRow);
        Object anchorRaw = draftRow.get("base_version");
        int base = anchorRaw instanceof Number anchor ? anchor.intValue() : current.version();
        boolean force = DraftWriteStrategy.ACTION_FORCE.equals(selectedAction);
        // A stale card anchor is a REAL race between showing the card and
        // confirming it (the anchor is re-read server-side at card time), so
        // it refuses naming both versions and offers the shared one-key
        // "apply this version" override — same posture as the other kinds.
        if (current.version() != base && !force) {
            throw new ServiceException("用例已被他人修改（草案基于版本 " + base
                    + "，当前版本 " + current.version() + "），本次回滚未写入——"
                    + "请基于最新版本重新确认，或选择「以本次为准」让本次回滚直接生效",
                    BizErrorCode.VERSION_CONFLICT);
        }
        // The rollback itself is the shared chain's call to make: optimistic
        // lock, append-only snapshot with change_type=4, script restore and
        // audit all ride CaseService.rollback untouched (no second rollback
        // path), including its no-op and unknown-version refusals.
        CaseRollbackResult landed = caseService.rollback(operatorId, caseId, targetVersion,
                force ? current.version() : base);
        return landed.newVersion();
    }

    /**
     * The confirmed card line named a target version for the human to read;
     * anything missing, unparsable or below 1 refuses here because the
     * shared chain's contract needs the number and guessing one would land
     * a rollback the human never saw.
     */
    private int readTargetVersion(Map<String, Object> draftRow) {
        JsonNode body = null;
        Object raw = draftRow.get("payload_json");
        if (raw != null) {
            try {
                body = objectMapper.readTree(String.valueOf(raw));
            } catch (Exception e) {
                body = null; // falls into the single refusal below
            }
        }
        JsonNode target = body == null ? null : body.get("targetVersion");
        int version = target == null || target.isNull() ? 0 : target.asInt(0);
        if (version < 1) {
            throw new ServiceException("回滚草案缺少有效的目标版本号（需 ≥1），无法确认写入",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return version;
    }
}
