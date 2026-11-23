package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyItem;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.api.dto.PendingGroupCardView;
import com.sixbean.testpike.core.aichat.domain.component.DraftConfirmTokenStore;
import com.sixbean.testpike.core.aichat.domain.component.DraftGroupScope;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.component.DraftQueueRenewalPolicy;
import com.sixbean.testpike.core.aichat.domain.component.RenewalPlan;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeStatus;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftConfirmationService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake;
import com.sixbean.testpike.core.behavior.domain.component.OutcomeChannel;
import com.sixbean.testpike.core.linkage.domain.service.LinkageImpactConsumptionService;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generic draft confirmation chain implementation .
 * <p>Guard order per the seam contract, shared by every proposal kind:
 * draft exists (40401, zero trace) → origin-aware project door (session
 * drafts: ownership through the single AF-g door {@code requireOwned},
 * 40301 horizontal-access refusal; system-origin drafts without a session:
 * login → project anchor → membership → memory permission key) → still
 * pending (40904) → ticket → per-kind strategy (its own optimistic anchor,
 * e.g. base_version for scripts) → write. The draft row is the trusted
 * snapshot landed core-side at the frame ledger write point, so the client
 * never carries the proposal body through this door — a forged request cannot
 * aim the write.
 * <p>Ticket semantics carried over from the repair chain untouched: issuing
 * is auditable but writes nothing; applying consumes the ticket first
 * (GETDEL, one-shot). A stale-proposal refusal (40901) also burns the ticket,
 * forcing a fresh human confirmation against the new entity version rather
 * than letting a held ticket retry blindly.
 * <p>Dispatch: strategies are collected by their object_type token at
 * construction (red line 6 — one door, one guard order; a new kind adds a
 * strategy bean, never a chain). A draft whose kind has no registered service
 * (rows written by hand or by a rolled-back deploy) refuses loudly instead of
 * half-applying.
 * <p>The group door is built INTO this chain, not
 * next to it: it runs the very same guards row by row through the same
 * private methods, then applies the write set in one transaction. Two-phase
 * shape per the ruling: phase A adjudicates every checked card against
 * the target's CURRENT version (disjoint proposals silently re-anchor, true
 * collisions turn stale, are named and leave the write set), phase B lands
 * the remaining set under the same CAS/audit/outcome machinery as the single
 * door; the sibling renewal then runs once per touched target excluding the
 * whole checked set, so a group's own cards can never double-flip (recon
 * pitfall 14).
 */
@Service
public class DraftConfirmationServiceImpl implements DraftConfirmationService {

    /** Draft row states (tp_ai_chat_draft.status, service-guarded transitions). */
    static final int STATUS_PENDING = 1;
    static final int STATUS_APPLIED = 2;
    static final int STATUS_VOIDED = 3;
    /** Anchor moved and the proposal would collide: one-click-regenerate card. */
    static final int STATUS_STALE = 4;

    /** Audit actions: who opened the door / who walked through it (H3 traceability). */
    static final String AUDIT_ACTION_CONFIRM_ISSUE = "draft-confirm-issue";
    static final String AUDIT_ACTION_CONFIRM_APPLY = "draft-confirm-apply";
    static final String AUDIT_ACTION_GROUP_CONFIRM_ISSUE = "draft-group-confirm-issue";
    static final String AUDIT_ACTION_GROUP_CONFIRM_APPLY = "draft-group-confirm-apply";

    /** Anchor sentinel: replaced by a real project id through the per-row door. */
    private static final long NO_PROJECT = -1L;

    private static final String SELECT_DRAFT =
            "SELECT id, session_id, message_id, project_id, object_type, target_id, base_version, schema_version, "
                    + "payload_json, status "
                    + "FROM tp_ai_chat_draft WHERE id = ?";

    /** The same projection grouped by answer message, issue order first. */
    private static final String SELECT_PENDING_GROUP =
            "SELECT id, session_id, message_id, project_id, object_type, target_id, base_version, schema_version, "
                    + "summary, payload_json, status "
                    + "FROM tp_ai_chat_draft WHERE message_id = ? AND status = ? "
                    + "ORDER BY frame_seq NULLS LAST, id";

    /**
 * The same projection resolved for a system-origin group (AN batch
 * piece B): the ledger columns already pin the group — project + kind +
 * origin — so no new column and no copied grouping table are needed.
 */
    private static final String SELECT_PENDING_GROUP_SYSTEM =
            "SELECT id, session_id, message_id, project_id, object_type, target_id, base_version, schema_version, "
                    + "summary, payload_json, status "
                    + "FROM tp_ai_chat_draft "
                    + "WHERE project_id = :projectId AND object_type = :objectType "
                    + "AND message_id IS NULL AND session_id IS NULL AND status = :status "
                    + "ORDER BY frame_seq NULLS LAST, id";

    /**
     * Enumeration form of the system read (piece D4d): every non-empty
     * pending system group of one project, cards of each kind in issue
     * order. The bus mints kinds the panel could never name (a pinned case
     * row carries no SCRIPT_REPAIR token), so the read lists the ledger's
     * own kinds and every card carries its group token back.
     * Group order is pinned with COLLATE "C": locale collations fold case
     * differently across databases (ctx_memory vs SCRIPT_REPAIR flip),
     * and a byte order keeps the answer deterministic on every slot.
     */
    private static final String SELECT_PENDING_GROUP_SYSTEM_ALL =
            "SELECT id, session_id, message_id, project_id, object_type, target_id, base_version, schema_version, "
                    + "summary, payload_json, status "
                    + "FROM tp_ai_chat_draft "
                    + "WHERE project_id = :projectId "
                    + "AND message_id IS NULL AND session_id IS NULL AND status = :status "
                    + "ORDER BY object_type COLLATE \"C\", frame_seq NULLS LAST, id";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate named;
    private final ChatSessionService sessionService;
    private final ProjectAccessService projectAccessService;
    private final RoleService roleService;
    private final DraftConfirmTokenStore tokenStore;
    private final AuditLogService auditLogService;
    private final BehaviorOutcomeIntake outcomeIntake;
    private final DraftQueueRenewalPolicy renewalPolicy;
    /** Keyed settle of the linkage ledger row behind an accepted card. */
    private final LinkageImpactConsumptionService impactConsumption;
    /** Anchor column read + refusal wording shared with the landing strategies (R40). */
    private final DraftProjectAnchor draftProjectAnchor;
    private final Map<String, DraftWriteStrategy> strategies = new TreeMap<>();

    /** Payload reader/writer for the checked-subset door . */
    private final ObjectMapper payloadReader = new ObjectMapper();

    public DraftConfirmationServiceImpl(JdbcTemplate jdbcTemplate,
                                        NamedParameterJdbcTemplate named,
                                        ChatSessionService sessionService,
                                        ProjectAccessService projectAccessService,
                                        RoleService roleService,
                                        DraftConfirmTokenStore tokenStore,
                                        AuditLogService auditLogService,
                                        BehaviorOutcomeIntake outcomeIntake,
                                        DraftQueueRenewalPolicy renewalPolicy,
                                        LinkageImpactConsumptionService impactConsumption,
                                        DraftProjectAnchor draftProjectAnchor,
                                        java.util.List<DraftWriteStrategy> writeStrategies) {
        this.jdbcTemplate = jdbcTemplate;
        this.named = named;
        this.sessionService = sessionService;
        this.projectAccessService = projectAccessService;
        this.roleService = roleService;
        this.tokenStore = tokenStore;
        this.auditLogService = auditLogService;
        this.outcomeIntake = outcomeIntake;
        this.renewalPolicy = renewalPolicy;
        this.impactConsumption = impactConsumption;
        this.draftProjectAnchor = draftProjectAnchor;
        for (DraftWriteStrategy strategy : writeStrategies) {
            this.strategies.put(strategy.objectType(), strategy);
        }
    }

    @Override
    @Transactional
    public int updateCheckedSubset(Long operatorId, long draftId, List<String> selectedNames) {
        Map<String, Object> row = requireDraft(draftId);
        resolveProject(row, operatorId);
        requirePending(row);
        JsonNode payload;
        try {
            payload = payloadReader.readTree(String.valueOf(row.get("payload_json")));
        } catch (Exception unreadable) {
            payload = null;
        }
        if (payload == null || !payload.isObject() || !payload.path("entries").isArray()) {
            throw new ServiceException(
                    "该草案没有可勾选的候选条目，勾选清单无法修改——请回到对话中重新让 AI 生成提案，再点「确认草案」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> wanted = new LinkedHashSet<>();
        if (selectedNames != null) {
            for (String name : selectedNames) {
                if (name != null && !name.isBlank()) {
                    wanted.add(name.trim());
                }
            }
        }
        Set<String> current = new LinkedHashSet<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (JsonNode entry : payload.path("entries")) {
            String name = entry.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            candidates.add(name);
            if (entry.path("selected").asBoolean(false)) {
                current.add(name);
            }
        }
        for (String name : wanted) {
            if (!candidates.contains(name)) {
                // Fail-closed whole-edit refusal: the authorization list may
                // only ever name what the server-side snapshot carries.
                throw new ServiceException(
                        "勾选清单包含草案候选之外的条目「" + name + "」，本次修改已拒绝"
                                + "（勾选以服务端解析结果为唯一依据；请重新让 AI 生成提案后在卡上勾选，再点「确认草案」）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if (wanted.equals(current)) {
            // R28: an unchanged check list is a no-op, not a timestamp churn.
            return wanted.size();
        }
        for (JsonNode entry : payload.path("entries")) {
            if (entry.isObject() && entry.has("name")) {
                ((ObjectNode) entry).put("selected",
                        wanted.contains(entry.path("name").asText("").trim()));
            }
        }
        String updated;
        try {
            updated = payloadReader.writeValueAsString(payload);
        } catch (JsonProcessingException impossibleForATreeBuiltHere) {
            throw new ServiceException("勾选清单无法保存——请在卡上重新保存一次；若仍失败，请重新让 AI 生成提案后再点「确认草案」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int flipped = named.update("""
                UPDATE tp_ai_chat_draft SET payload_json = :payload, updated_at = now()
                WHERE id = :id AND status = :pending
                """, new MapSqlParameterSource()
                .addValue("payload", updated)
                .addValue("id", draftId)
                .addValue("pending", STATUS_PENDING));
        if (flipped == 0) {
            throw new ServiceException(
                    "草案状态已变化，勾选清单未保存——请刷新对话查看最新状态；卡上仍是待确认时，可重新点「确认草案」",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return wanted.size();
    }

    @Override
    public DraftTicket issueConfirmToken(Long operatorId, long draftId) {
        Map<String, Object> row = requireDraft(draftId);
        // Ownership re-check on the draft itself: the caller must be the human
        // who watched the answer stream (session drafts) or a project member
        // holding the memory key (system-origin drafts) — never just anyone
        // holding a draft id.
        long projectId = resolveProject(row, operatorId);
        requirePending(row);
        String token = tokenStore.issue(draftId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("draftId", draftId);
        after.put("objectType", row.get("object_type"));
        after.put("expiresInSeconds", tokenStore.ttlSeconds());
        auditLogService.record(new AuditLogInput(operatorId, projectId, "aichat_draft",
                String.valueOf(draftId), AUDIT_ACTION_CONFIRM_ISSUE, null, after));
        return new DraftTicket(token, tokenStore.ttlSeconds());
    }

    @Override
    @Transactional
    public DraftApplyResult apply(Long operatorId, long draftId, String confirmToken) {
        return apply(operatorId, draftId, confirmToken, null);
    }

    @Override
    @Transactional
    public DraftApplyResult apply(Long operatorId, long draftId, String confirmToken,
                                  String selectedAction) {
        Map<String, Object> row = requireDraft(draftId);
        long projectId = resolveProject(row, operatorId);
        requirePending(row);
        // One-shot consume before anything moves: without a live ticket the
        // door stays shut (proposal never self-applies — red line).
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new ServiceException("AI 草案需人工确认后才会写入——请先在对话卡上点「确认草案」申请确认票，再携票点「应用草案」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        tokenStore.consume(confirmToken, draftId);

        String objectType = (String) row.get("object_type");
        DraftWriteStrategy strategy = strategies.get(objectType);
        if (strategy == null) {
            // Loud refusal beats a silent half-apply: the ticket is spent and
            // nothing was written, an operator investigates the kind mismatch.
            throw new ServiceException("该草案类型暂不支持确认写入（object_type=" + objectType
                    + "），本次应用未生效——请重新让 AI 生成提案后再点「确认草案」", BizErrorCode.RESOURCE_CONFLICT);
        }
        // The human's either-or choice (dual-payload requirement cards, )
        // rides through the SAME door; kinds without a dual shape ignore it.
        // A refused choice burns the ticket on purpose — the same posture as
        // the stale-version refusal, forcing a fresh confirmation.
        Integer appliedVersion = strategy.land(row, requireUser(operatorId), selectedAction);
        // Create-style kinds have no target until the write lands: the strategy
        // pins the new entity id back onto the ledger row, so the receipt and
        // the audit must be read AFTER landing, never from the pre-write snapshot.
        Long targetId = readBackTarget(draftId);

        if (flipToApplied(draftId, appliedVersion, operatorId) == 0) {
            // CAS lost (same draft applied concurrently): the ticket is spent,
            // the target row was written by the winning caller — refuse loudly.
            throw new ServiceException("该草案已被处理，本次应用未生效——请刷新对话查看最新状态；卡上仍显示待确认时，可再点「应用草案」",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        // The same won CAS settles the linkage ledger row this card was
        // minted for (keyed by the draft context; no anchor = no-op).
        impactConsumption.consumeForDraft(draftId, operatorId);

        // The won CAS is the human verdict behind the one-click confirmation
        // door: exactly one draft-channel row per applied draft (a concurrent
        // loser throws above and never records). Voided drafts stay uncounted
        // — retirement is a lifecycle state, not a verdict.
        outcomeIntake.recordReviewVerdict(OutcomeChannel.DRAFT, draftId, "adopted",
                targetId == null ? null : String.valueOf(targetId), projectId);
        // Same-target pending queue renews itself right behind this confirmed
        // write, inside the same transaction .
        if (targetId != null) {
            renewSiblingQueueAfterLanding(objectType, targetId, List.of(draftId));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("draftId", draftId);
        after.put("objectType", objectType);
        after.put("targetId", targetId);
        after.put("appliedVersion", appliedVersion);
        auditLogService.record(new AuditLogInput(operatorId, projectId, "aichat_draft",
                String.valueOf(draftId), AUDIT_ACTION_CONFIRM_APPLY, null, after));
        return new DraftApplyResult(draftId, objectType, targetId, appliedVersion);
    }

    @Override
    public DraftTicket issueGroupConfirmToken(Long operatorId, long messageId) {
        return issueGroupConfirmTokenByScope(operatorId,
                DraftGroupScope.forMessage(messageId).wire());
    }

    @Override
    public DraftTicket issueGroupConfirmTokenByScope(Long operatorId, String groupScope) {
        DraftGroupScope scope = DraftGroupScope.parse(groupScope);
        List<Map<String, Object>> group = pendingGroupRows(scope);
        long projectId = NO_PROJECT;
        for (Map<String, Object> row : group) {
            // Every card walks the SAME origin-aware door as the single chain
            // — a group ticket is issued only when every row is confirmable
            // by this human (red line 6: one door, one guard order).
            projectId = resolveProject(row, operatorId);
        }
        String token = tokenStore.issueGroup(scope.wire());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("groupScope", scope.wire());
        if (scope.dialogScoped()) {
            after.put("messageId", scope.messageId());
        }
        after.put("pendingCards", group.size());
        after.put("expiresInSeconds", tokenStore.ttlSeconds());
        auditLogService.record(new AuditLogInput(operatorId, projectId, "aichat_draft",
                scope.wire(), AUDIT_ACTION_GROUP_CONFIRM_ISSUE, null, after));
        return new DraftTicket(token, tokenStore.ttlSeconds());
    }

    @Override
    @Transactional
    public DraftGroupApplyResult applyGroup(Long operatorId, long messageId, String confirmToken,
                                            List<Long> selectedDraftIds) {
        return applyGroupByScope(operatorId, DraftGroupScope.forMessage(messageId).wire(),
                confirmToken, selectedDraftIds);
    }

    @Override
    @Transactional
    public DraftGroupApplyResult applyGroupByScope(Long operatorId, String groupScope,
                                                    String confirmToken,
                                                    List<Long> selectedDraftIds) {
        DraftGroupScope scope = DraftGroupScope.parse(groupScope);
        List<Map<String, Object>> group = pendingGroupRows(scope);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        long projectId = NO_PROJECT;
        for (Map<String, Object> row : group) {
            projectId = resolveProject(row, operatorId);
            byId.put(((Number) row.get("id")).longValue(), row);
        }
        if (confirmToken == null || confirmToken.isBlank()) {
            throw new ServiceException("AI 草案组需人工确认后才会写入——请先在对话卡上点「一次全部接受」申请组确认票，再携票点「确认应用」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<Long> selected = new LinkedHashSet<>();
        if (selectedDraftIds != null) {
            for (Long id : selectedDraftIds) {
                if (id == null || !byId.containsKey(id)) {
                    // Shape error before the one-shot consume: a mistyped or
                    // cross-answer selection burns nothing, the human corrects
                    // the check-list and retries with the SAME ticket.
                    throw new ServiceException("勾选的草案不在本回答的待确认清单中（id=" + id
                            + "），本次组确认未生效，确认票未消耗——请在组卡上按卡面标题重新勾选，再点「一次全部接受」", BizErrorCode.VALIDATION_ERROR);
                }
                if (!selected.add(id)) {
                    throw new ServiceException("同一张草案卡被重复勾选（id=" + id
                            + "），本次组确认未生效，确认票未消耗——请在组卡上取消重复勾选后重新点「一次全部接受」", BizErrorCode.VALIDATION_ERROR);
                }
            }
        }
        if (selected.isEmpty()) {
            throw new ServiceException("组确认未勾选任何草案卡——请在对话卡上勾选要接受的提案，再点「一次全部接受」",
                    BizErrorCode.VALIDATION_ERROR);
        }

        tokenStore.consumeGroup(confirmToken, scope.wire());

        // Phase A — anchor adjudication against each target's CURRENT version
        // before anything is written (IT-ITR-05): disjoint proposals silently
        // re-anchor onto the fresh version, truly colliding ones turn into the
        // stale one-click-regenerate card and are NAMED (object, both versions,
        // the exit), leaving the write set. Same-object multi-check is a shape
        // error the renewal queue cannot rebase inside one apply either.
        List<Map<String, Object>> writeSet = new ArrayList<>();
        List<DraftGroupApplyItem> receipt = new ArrayList<>();
        List<DraftGroupApplyItem> keptLines = new ArrayList<>();
        Map<Long, List<Long>> checkedByTarget = new LinkedHashMap<>();
        for (Map<String, Object> row : group) {
            long draftId = ((Number) row.get("id")).longValue();
            if (!selected.contains(draftId)) {
                // An unticked card gets its own receipt line (IT-ITR-05: the
                // group answers the whole answer, never silently dropping a
                // row) — it stays pending and the human can confirm it later.
                Long keptTarget = row.get("target_id") == null
                        ? null : ((Number) row.get("target_id")).longValue();
                String keptKind = (String) row.get("object_type");
                String keptName = targetNameOf(keptKind, keptTarget);
                keptLines.add(new DraftGroupApplyItem(draftId, keptKind, keptName, keptTarget,
                        null, DraftGroupApplyItem.OUTCOME_NOT_TICKED,
                        (keptName == null ? "本卡" : "「" + keptName + "」")
                                + "本次未勾选，未写入——仍是待确认卡，之后可勾选再确认。"));
                continue;
            }
            String objectType = (String) row.get("object_type");
            DraftWriteStrategy strategy = strategies.get(objectType);
            if (strategy == null) {
                throw new ServiceException("该草案类型暂不支持确认写入（object_type=" + objectType
                        + "），本次组确认未生效——请重新让 AI 生成提案后再点「一次全部接受」", BizErrorCode.RESOURCE_CONFLICT);
            }
            Long targetId = row.get("target_id") == null
                    ? null : ((Number) row.get("target_id")).longValue();
            if (targetId != null) {
                checkedByTarget.computeIfAbsent(targetId, k -> new ArrayList<>()).add(draftId);
            }
            Integer base = toInt(row.get("base_version"));
            Integer current = targetId == null ? null : currentVersionOfTarget(objectType, targetId);
            if (base != null && current != null && base.intValue() != current.intValue()) {
                RevisionMergeStatus verdict = strategy.renewalVerdict(row, current)
                        .orElse(RevisionMergeStatus.BASELINE_MISSING);
                if (verdict == RevisionMergeStatus.REBASE) {
                    named.update("""
                            UPDATE tp_ai_chat_draft SET base_version = :v, updated_at = now()
                            WHERE id = :id AND status = :pending
                            """, new MapSqlParameterSource()
                            .addValue("v", current).addValue("id", draftId)
                            .addValue("pending", STATUS_PENDING));
                    row.put("base_version", current);
                } else {
                    String objectName = targetNameOf(objectType, targetId);
                    String note = "「" + objectName + "」已被其他改动更新到版本 " + current
                            + "（本卡基于版本 " + base + "），本卡改动与其重叠，未写入——"
                            + "可在该卡上一键重新生成新提案后再确认。";
                    named.update("""
                            UPDATE tp_ai_chat_draft SET status = :stale, status_note = :note,
                                   updated_at = now()
                            WHERE id = :id AND status = :pending
                            """, new MapSqlParameterSource()
                            .addValue("stale", STATUS_STALE).addValue("note", note)
                            .addValue("id", draftId).addValue("pending", STATUS_PENDING));
                    receipt.add(new DraftGroupApplyItem(draftId, objectType, objectName,
                            targetId, null, DraftGroupApplyItem.OUTCOME_STALE_EXCLUDED, note));
                    continue;
                }
            }
            writeSet.add(row);
        }
        for (Map.Entry<Long, List<Long>> entry : checkedByTarget.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new ServiceException("本次组确认在同一对象上勾选了多张草案卡（对象 id=" + entry.getKey()
                        + "，卡 " + entry.getValue() + "）——一个对象一次组确认只落一张提案，"
                        + "请取消其一后重新点「一次全部接受」发起确认", BizErrorCode.VALIDATION_ERROR);
            }
        }

        // Phase B — the whole remaining write set lands in THIS transaction;
        // any landing exception rolls the group back and names nothing half,
        long user = requireUser(operatorId);
        for (Map<String, Object> row : writeSet) {
            long draftId = ((Number) row.get("id")).longValue();
            String objectType = (String) row.get("object_type");
            Integer appliedVersion = strategies.get(objectType).land(row, user, null);
            Long landedTarget = readBackTarget(draftId);
            if (flipToApplied(draftId, appliedVersion, operatorId) == 0) {
                throw new ServiceException("该草案已被处理，本次组确认整组回滚——请刷新对话查看最新状态；仍是待确认的卡可重新点「一次全部接受」",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
            // Each won line settles its own keyed ledger row inside the same
            // group transaction: a later refusal rolls the whole write set —
            // ledger and business content together, never half.
            impactConsumption.consumeForDraft(draftId, operatorId);
            outcomeIntake.recordReviewVerdict(OutcomeChannel.DRAFT, draftId, "adopted",
                    landedTarget == null ? null : String.valueOf(landedTarget), projectId);
            receipt.add(new DraftGroupApplyItem(draftId, objectType,
                    targetNameOf(objectType, landedTarget), landedTarget, appliedVersion,
                    DraftGroupApplyItem.OUTCOME_LANDED, null));
        }

        // Unified sibling renewal AFTER the whole write set landed: once per
        // touched target, excluding EVERY checked card (pitfall 14 — running
        // the single-door renewal per item would re-flip the group's own
        // cards). Out-of-answer pending siblings still renew/retire as usual.
        Map<String, Long> touchedTargets = new LinkedHashMap<>();
        for (Map<String, Object> row : writeSet) {
            Long landedTarget = row.get("target_id") == null
                    ? null : ((Number) row.get("target_id")).longValue();
            if (landedTarget != null) {
                touchedTargets.put((String) row.get("object_type"), landedTarget);
            }
        }
        for (Map.Entry<String, Long> touched : touchedTargets.entrySet()) {
            renewSiblingQueueAfterLanding(touched.getKey(), touched.getValue(), selected);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("groupScope", scope.wire());
        if (scope.dialogScoped()) {
            after.put("messageId", scope.messageId());
        }
        after.put("checked", selected.size());
        after.put("landed", writeSet.size());
        after.put("staleExcluded", receipt.size() - writeSet.size());
        auditLogService.record(new AuditLogInput(operatorId, projectId, "aichat_draft",
                scope.wire(), AUDIT_ACTION_GROUP_CONFIRM_APPLY, null, after));
        // The kept-pending lines join the receipt only AFTER the audit read
        // the checked-card arithmetic (staleExcluded = checked lines − write
        // set), so the audit keeps its original meaning.
        receipt.addAll(keptLines);
        receipt.sort(Comparator.comparingLong(DraftGroupApplyItem::draftId));
        return new DraftGroupApplyResult(scope.wire(),
                scope.dialogScoped() ? scope.messageId() : 0, List.copyOf(receipt));
    }

    /**
 * Read side of one group's pending cards .
 * Shares the rowset resolution AND the per-row origin-aware guards with
 * the issue door — one door, one guard order, zero second implementation
 * (red line 6) — and differs only in the empty-group posture: a panel
 * legitimately reads "nothing pending" as an empty list.
 */
    @Override
    public List<PendingGroupCardView> pendingGroupCards(Long operatorId, String groupScope) {
        DraftGroupScope scope = DraftGroupScope.parse(groupScope);
        List<PendingGroupCardView> views = new ArrayList<>();
        for (Map<String, Object> row : resolveGroupRowsForRead(scope)) {
            resolveProject(row, operatorId);
            views.add(new PendingGroupCardView(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("project_id")).longValue(),
                    (String) row.get("object_type"),
                    (String) row.get("summary"),
                    row.get("target_id") == null
                            ? null : ((Number) row.get("target_id")).longValue(),
                    row.get("base_version") == null
                            ? null : ((Number) row.get("base_version")).intValue(),
                    String.valueOf(row.get("schema_version")),
                    String.valueOf(row.get("payload_json")),
                    ((Number) row.get("status")).intValue()));
        }
        return views;
    }

    /**
 * Pending cards of one group in issue order; empty group = loud refusal.
 * Dialog scope keeps the answer-message key ; system scope
 * resolves straight from the ledger columns that already pin the group
 * — project + kind + origin, never a copied grouping
 * state that could drift the ledger.
 */
    private List<Map<String, Object>> pendingGroupRows(DraftGroupScope scope) {
        if (scope.systemEnumerated()) {
            // A ticket binds ONE explicit group (its wire is the bound
            // value), so the read-all enumeration never opens the door.
            throw new ServiceException(
                    "确认票只能针对单个对象类型的分组发起（形如 system:项目:对象类型 或 message:编号），"
                            + "请在「待处置的未提交改动」中选中该分组后再点「一次全部接受」，本次确认未生效，确认票未消耗",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Map<String, Object>> rows = resolveGroupRows(scope);
        if (rows.isEmpty()) {
            throw new ServiceException(scope.dialogScoped()
                    ? "该回答下已没有待确认的草案卡（可能已处理或过期），本次确认未生效——请重新让 AI 生成提案后再点「一次全部接受」"
                    : "该分组下已没有待确认的草案卡（可能已处理或过期），本次确认未生效——请回到「待处置的未提交改动」刷新后重新选择该分组",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return rows;
    }

    /** Shared rowset resolution of the group door and its read side (single source). */
    private List<Map<String, Object>> resolveGroupRows(DraftGroupScope scope) {
        return scope.dialogScoped()
                ? jdbcTemplate.queryForList(SELECT_PENDING_GROUP, scope.messageId(), STATUS_PENDING)
                : named.queryForList(SELECT_PENDING_GROUP_SYSTEM,
                        new MapSqlParameterSource()
                                .addValue("projectId", scope.projectId())
                                .addValue("objectType", scope.objectType())
                                .addValue("status", STATUS_PENDING));
    }

    /**
 * Read-side resolution (piece D4d): the explicit-group rowset, or every
 * non-empty pending system group of the segment when the caller names the
 * project alone. The enumeration exists because the read door must see
 * what the bus mints, not only what the caller could prename — a panel
 * holding pinned case rows still has to surface SCRIPT_REPAIR cards.
 */
    private List<Map<String, Object>> resolveGroupRowsForRead(DraftGroupScope scope) {
        return scope.systemEnumerated()
                ? named.queryForList(SELECT_PENDING_GROUP_SYSTEM_ALL,
                        new MapSqlParameterSource()
                                .addValue("projectId", scope.projectId())
                                .addValue("status", STATUS_PENDING))
                : resolveGroupRows(scope);
    }

    /**
     * Read the target id AFTER landing (create-style kinds only have one from
     * here on) — shared by the single and the group door so the post-write
     * snapshot read can never drift between them.
     */
    private Long readBackTarget(long draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT target_id FROM tp_ai_chat_draft WHERE id = ?", Long.class, draftId);
    }

    /** The CAS flip pending -> applied with the landing trace; 0 rows = concurrent winner. */
    private int flipToApplied(long draftId, Integer appliedVersion, Long operatorId) {
        return named.update("""
                UPDATE tp_ai_chat_draft
                SET status = :applied, applied_version = :ver, applied_by = :by,
                    applied_at = now(), updated_at = now()
                WHERE id = :id AND status = :pending
                """, new MapSqlParameterSource()
                .addValue("applied", STATUS_APPLIED)
                .addValue("pending", STATUS_PENDING)
                .addValue("ver", appliedVersion)
                .addValue("by", operatorId)
                .addValue("id", draftId));
    }

    /**
 * On-screen name of a draft's target object for receipts and refusal
 * sentences . Families
 * without a version ledger have no name machine here — null falls back
 * to the kind token in the view, never a fabricated label.
 */
    private String targetNameOf(String objectType, Long targetId) {
        if (targetId == null) {
            return null;
        }
        if (DraftObjectType.CASE_WRITE.wireName.equals(objectType)
                || DraftObjectType.FLAKY_ISOLATION.wireName.equals(objectType)) {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT title FROM tp_test_case WHERE id = ?", String.class, targetId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (DraftObjectType.REQUIREMENT_WRITE.wireName.equals(objectType)) {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT title FROM tp_requirement WHERE id = ? AND deleted = 0",
                    String.class, targetId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    /**
 * Origin-aware project resolution , shared by both door
 * entries so the order can never drift between issue and apply:
 * <ul>
 * <li>session draft — unchanged AF-g door {@code requireOwned} (40301);</li>
 * <li>system-origin draft (distilled project memory, no session) — human
 * identity first, then a real project anchor (an unanchored row
 * governs nothing and must refuse instead of crashing), then the
 * project-membership door reused from {@code ProjectAccessService}
 * (no second membership check anywhere — R40), then the
 * project-memory permission key from {@code RoleService.permsOfUser}
 * (refusal names the key, same shape as the admin edge).</li>
 * </ul>
 */
    private long resolveProject(Map<String, Object> row, Long operatorId) {
        Object sessionId = row.get("session_id");
        if (sessionId != null) {
            return sessionService.requireOwned(
                    ((Number) sessionId).longValue(), requireUser(operatorId)).projectId();
        }
        long uid = requireUser(operatorId);
        // Anchor read + refusal wording single-sourced in DraftProjectAnchor
        // (the same words the landing strategies refuse with, R40); the door
        // keeps membership and the project-memory permission key on top.
        long projectId = draftProjectAnchor.requireStoredAnchor(row);
        projectAccessService.requireProjectMember(projectId, uid);
        if (!roleService.permsOfUser(uid).contains(PermissionCatalog.AICHAT_PROJECT_MEMORY.key())) {
            throw new ServiceException("缺少权限：项目记忆账本（"
                    + PermissionCatalog.AICHAT_PROJECT_MEMORY.key() + "）——请前往「角色管理」为该角色开通对应权限后重试", BizErrorCode.FORBIDDEN);
        }
        return projectId;
    }

    /** 40401 pre-guard: unknown draft id = zero trace, same posture as the tool door. */
    private Map<String, Object> requireDraft(long draftId) {
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(SELECT_DRAFT, draftId);
        } catch (RuntimeException e) {
            throw new ServiceException("AI 草案不存在或已失效——请回到对话中重新让 AI 生成提案，再点「确认草案」",
                    BizErrorCode.NOT_FOUND);
        }
        return row;
    }

    /** The door is human-only: a caller without a login identity cannot open it. */
    private long requireUser(Long operatorId) {
        if (operatorId == null) {
            throw new ServiceException("确认操作需登录身份，匿名请求无法开门——请登录后回到对话中点「确认草案」再应用", BizErrorCode.FORBIDDEN);
        }
        return operatorId;
    }

    /** Only a pending draft is confirmable (applied/voided/stale = loud 40904, zero writes). */
    private void requirePending(Map<String, Object> row) {
        int status = ((Number) row.get("status")).intValue();
        if (status != STATUS_PENDING) {
            throw new ServiceException(status == STATUS_APPLIED
                    ? "该草案已应用过，不可重复确认——请刷新对话查看已生效结果；如需继续修改，请重新让 AI 生成提案后再点「确认草案」"
                    : status == STATUS_STALE
                            ? "该草案指向的对象已被其他改动更新，本卡已陈旧——请在卡上一键重新生成新提案，再点「确认草案」确认"
                            : "该草案已作废，不可确认应用——请重新让 AI 生成提案，再点「确认草案」", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    /**
 * Same-target queue renewal after a confirmed write (AM batch piece
 * 2b, acceptance IT-ITR-02): every still-pending sibling of the target is
 * re-planned through {@link DraftQueueRenewalPolicy} inside this very
 * transaction. Disjoint proposals silently move their anchor onto the
 * freshly landed version (no manual renew click), proposals that would
 * collide with what just landed become the stale one-click-regenerate
 * card, and the exclusivity gate keeps at most one stale card per target
 * (older losers are voided with a reason naming the surviving card).
 * Nothing here writes a business row — the human confirmation door is
 * never bypassed; a sibling whose anchor did not move is not touched at
 * all (idempotent, zero writes).
 * <p>{@code excludeDraftIds} is the identity set this landing pass already
 * handled: one id for the single door, the WHOLE checked group for the
 * group door ( pitfall 14 — a group's own cards must never be
 * renewal-flipped by a sibling it just landed alongside).
 */
    private void renewSiblingQueueAfterLanding(String objectType, long targetId,
                                               java.util.Collection<Long> excludeDraftIds) {
        Integer current = currentVersionOfTarget(objectType, targetId);
        if (current == null) {
            // Family without a version ledger (or the row is gone): the
            // queue has no version semantics to renew — leave it untouched.
            return;
        }
        List<Map<String, Object>> queue = named.queryForList(
                "SELECT id, object_type, target_id, base_version, payload_json FROM tp_ai_chat_draft"
                        + " WHERE target_id = :target AND status = :pending AND id NOT IN (:exclude)"
                        + " ORDER BY seq NULLS LAST, id",
                new MapSqlParameterSource()
                        .addValue("target", targetId)
                        .addValue("pending", STATUS_PENDING)
                        .addValue("exclude", excludeDraftIds));
        if (queue.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        Map<Long, Integer> bases = new HashMap<>();
        Map<Long, RevisionMergeStatus> verdicts = new HashMap<>();
        for (Map<String, Object> sibling : queue) {
            Long id = ((Number) sibling.get("id")).longValue();
            Integer base = toInt(sibling.get("base_version"));
            if (base == null || base.intValue() == current.intValue()) {
                continue; // anchor did not move for this card: zero writes
            }
            ids.add(id);
            bases.put(id, base);
            DraftWriteStrategy strategy = strategies.get((String) sibling.get("object_type"));
            if (strategy != null) {
                strategy.renewalVerdict(sibling, current).ifPresent(v -> verdicts.put(id, v));
            }
            // A sibling without a verdict stays absent from the map: the
            // policy judges it BASELINE_MISSING — conservative stale, never a
            // silent pass (unjudgeable baseline must not keep a live card).
        }
        if (ids.isEmpty()) {
            return;
        }
        RenewalPlan plan = renewalPolicy.renewQueue(ids, bases, verdicts, current);
        plan.renewAnchors().forEach((id, version) -> named.update(
                "UPDATE tp_ai_chat_draft SET base_version = :v, updated_at = now()"
                        + " WHERE id = :id AND status = :pending",
                new MapSqlParameterSource()
                        .addValue("v", version).addValue("id", id)
                        .addValue("pending", STATUS_PENDING)));
        for (Long id : plan.staleIds()) {
            named.update("UPDATE tp_ai_chat_draft SET status = :stale, status_note = :note,"
                            + " updated_at = now() WHERE id = :id AND status = :pending",
                    new MapSqlParameterSource()
                            .addValue("stale", STATUS_STALE)
                            .addValue("note", "同一目标的另一提案已先行确认写入（本卡基于版本 "
                                    + bases.get(id) + "，当前版本 " + current
                                    + "），本卡内容与其冲突，未写入——可一键重新生成新提案。")
                            .addValue("id", id).addValue("pending", STATUS_PENDING));
        }
        plan.voidedByExclusiveGate().forEach((id, reason) -> named.update(
                "UPDATE tp_ai_chat_draft SET status = :voided, status_note = :note,"
                        + " updated_at = now() WHERE id = :id AND status = :pending",
                new MapSqlParameterSource()
                        .addValue("voided", STATUS_VOIDED).addValue("note", reason)
                        .addValue("id", id).addValue("pending", STATUS_PENDING)));
    }

    /**
     * Single-source per-family current-version reader for the renewal queue:
     * only the case family (edit + quarantine cards share one case row) and
     * the requirement family carry a version ledger the gate can replay
     * against; every other kind answers null and its queue stays inert.
     */
    private Integer currentVersionOfTarget(String objectType, long targetId) {
        if (DraftObjectType.CASE_WRITE.wireName.equals(objectType)
                || DraftObjectType.FLAKY_ISOLATION.wireName.equals(objectType)) {
            List<Integer> rows = jdbcTemplate.queryForList(
                    "SELECT version FROM tp_test_case WHERE id = ?", Integer.class, targetId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        if (DraftObjectType.REQUIREMENT_WRITE.wireName.equals(objectType)) {
            List<Integer> rows = jdbcTemplate.queryForList(
                    "SELECT version FROM tp_requirement WHERE id = ? AND deleted = 0",
                    Integer.class, targetId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return null;
    }

    private static Integer toInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }
}
