package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeGate;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeOutcome;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeStatus;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftLandContext;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requirement-write landing strategy behind the generic draft door (AL batch
 * ): one confirmed dialog proposal creates OR edits a requirement point
 * through the SAME RequirementService chain the human UI uses — field
 * validation, version-chain snapshots, soft-delete visibility and audit all
 * come free, never a parallel write path (red line: no second implementation
 * of the requirement save; the optimistic 40901 wording is single-sourced by
 * RequirementServiceImpl and surfaces from there untouched).
 * <p>Two shapes keyed by the ledger row itself:
 * <ul>
 * <li>{@code target_id} NULL = create. The project is taken from the
 * draft's own session, never from the proposal body (H1), and the
 * source is always the manual one (3): the dialog never impersonates
 * the document-extraction or Jira channels;</li>
 * <li>{@code target_id} set = field-level edit. The optimistic anchor
 * base_version (server re-read at landing when the V330 anchor switch
 * is on) rides into RequirementServiceImpl.update — a still-stale echo
 * is refused there with the plain-word version conflict.</li>
 * </ul>
 * <p>Dual-payload drafts : the card carries BOTH a
 * {@code create} and an anchored {@code update} branch because the
 * duplicate gate had already shown the human a known hit at card time. The
 * door never guesses — a missing or unknown choice refuses with 40001 and
 * nothing lands. The human's {@code create} choice against that known hit is
 * a respected override: it rides the shared chain's dedupOverride channel,
 * so the single-source DedupGate writes the force-new audit naming the
 * blocked match (no second gate implementation here, R40). A plain
 * single-payload create keeps the write-instant TOCTOU re-check honoured by
 * the shared chain: a duplicate that appeared after card time refuses the
 * insert and names the existing requirement in plain words.
 * <p>Attribution guard: an edit proposal aimed at a requirement outside the
 * session's project is refused (40301) before anything moves — same rule as
 * the case-write strategy (AF-g horizontal spectrum).
 */
@Service
public class RequirementWriteDraftStrategy implements DraftWriteStrategy {

    /** Dialog-created requirements are manual-source rows (3), never Jira (1). */
    private static final int SOURCE_MANUAL = 3;

    /** Either-or choices accepted on a dual-payload card (create vs update). */
    private static final String ACTION_CREATE = "create";
    private static final String ACTION_UPDATE = "update";

    /** Editable payload keys of a requirement proposal (same spelling as the snapshot map). */
    private static final List<String> EDITABLE_KEYS =
            List.of("title", "acceptanceCriteria", "chapter");

    private final RequirementService requirementService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final RevisionMergeGate revisionMergeGate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RequirementWriteDraftStrategy(RequirementService requirementService,
                                         ChatSessionService sessionService,
                                         JdbcTemplate jdbcTemplate,
                                         RevisionMergeGate revisionMergeGate) {
        this.requirementService = requirementService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
        this.revisionMergeGate = revisionMergeGate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.REQUIREMENT_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        DraftLandContext ctx = DraftLandContext.of(draftRow, sessionService);
        long draftId = ctx.draftId();
        long sessionId = ctx.sessionId();
        long projectId = ctx.projectId();
        boolean forceLatest = DraftWriteStrategy.ACTION_FORCE.equals(selectedAction);
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        JsonNode createBranch = body.get("create");
        JsonNode updateBranch = body.get("update");
        if (createBranch != null && createBranch.isObject()
                && updateBranch != null && updateBranch.isObject()) {
            if (ACTION_CREATE.equals(selectedAction)) {
                // The card showed the duplicate; the human still chose create.
                return landCreate(draftId, projectId, createBranch, operatorId, true);
            }
            if (ACTION_UPDATE.equals(selectedAction) || forceLatest) {
                // The apply-this-version choice on a dual card lands the
                // update branch with the override replay .
                return landUpdateBranch(draftId, projectId, updateBranch, operatorId, forceLatest);
            }
            throw new ServiceException(
                    "该草案同时提供「新建」与「变更」两种写入，需要人工明确二选一后再确认"
                            + "（请在确认卡片上选择对应操作）；本次未写入任何数据",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Object targetRaw = draftRow.get("target_id");
        return targetRaw == null
                ? landCreate(draftId, projectId, body, operatorId, false)
                : landEdit(draftRow, projectId, body, operatorId, forceLatest);
    }

    /**
     * Create proposal: lands in the session's project; requirement id pinned
     * back. An informed override rides the shared chain's dedupOverride
     * channel so the force-new audit stays single-sourced by the DedupGate.
     */
    private Integer landCreate(long draftId, long projectId, JsonNode body, long operatorId,
                               boolean informedOverride) {
        String title = DraftPayloadReads.text(body, "title");
        if (title == null || title.isBlank()) {
            throw new ServiceException("AI 需求草案缺少标题，无法创建需求点（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        RequirementInfo created = requirementService.create(operatorId, new RequirementCreateInput(
                projectId, SOURCE_MANUAL, null, title.trim(),
                DraftPayloadReads.text(body, "acceptanceCriteria"), DraftPayloadReads.text(body, "chapter"),
                informedOverride ? Boolean.TRUE : null));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        return created.version();
    }

    /**
     * The anchored update branch of a dual-payload draft: the target and the
     * optimistic anchor were pre-installed at card time (the duplicate the
     * gate found), so the ledger row itself stays create-kind until this
     * choice lands — the receipt pin writes the target back at landing.
     */
    private Integer landUpdateBranch(long draftId, long projectId, JsonNode branch,
                                     long operatorId, boolean forceLatest) {
        JsonNode target = branch.get("targetId");
        JsonNode baseVersion = branch.get("baseVersion");
        if (target == null || !target.canConvertToLong()
                || baseVersion == null || !baseVersion.canConvertToInt()) {
            throw new ServiceException("该变更草案缺少目标需求点或版本锚点，无法写入（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long requirementId = target.asLong();
        int anchor = baseVersion.asInt();
        Integer version = applyEdit(requirementId, anchor, projectId, branch, operatorId, forceLatest);
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, base_version = ?, "
                + "updated_at = now() WHERE id = ?", requirementId, anchor, draftId);
        return version;
    }

    /** Edit proposal keyed by the ledger row (single-payload, shape). */
    private Integer landEdit(Map<String, Object> draftRow, long projectId,
                             JsonNode body, long operatorId, boolean forceLatest) {
        long requirementId = ((Number) draftRow.get("target_id")).longValue();
        int baseVersion = ((Number) draftRow.get("base_version")).intValue();
        return applyEdit(requirementId, baseVersion, projectId, body, operatorId, forceLatest);
    }

    @Override
    public java.util.Optional<RevisionMergeStatus> renewalVerdict(Map<String, Object> siblingRow,
                                                                  int currentVersion) {
        long requirementId = ((Number) siblingRow.get("target_id")).longValue();
        int baseVersion = ((Number) siblingRow.get("base_version")).intValue();
        JsonNode body = parseBody(String.valueOf(siblingRow.get("payload_json")));
        RevisionMergeOutcome outcome = revisionMergeGate.assessRequirement(
                requirementId, baseVersion, currentVersion, proposalFields(body));
        return java.util.Optional.of(outcome.status());
    }

    /**
 * Shared edit landing: attribution door first, then the stale-anchor check
 * through the SAME revision-merge gate the case path uses ( stage 1b,
 * R40 single source): a moved version is not a blanket refusal — disjoint
 * proposals rebase onto the current version, intersecting ones are refused
 * naming every moved field, and the human's one-key apply-this-version
 * answer (force) replays the proposal over the counterpart on the current
 * version. A missing baseline always refuses conservatively, force included.
 */
    private Integer applyEdit(long requirementId, int baseVersion, long projectId,
                              JsonNode body, long operatorId, boolean forceLatest) {
        RequirementInfo current = requirementService.get(requirementId);
        if (current.projectId() != projectId) {
            throw new ServiceException("该草案指向的需求点不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        int anchor = baseVersion;
        if (current.version() != baseVersion) {
            RevisionMergeOutcome outcome = revisionMergeGate.assessRequirement(
                    requirementId, baseVersion, current.version(), proposalFields(body));
            if (outcome.status() == RevisionMergeStatus.BASELINE_MISSING) {
                throw new ServiceException("无法核对草案的基线内容（草案基于版本 " + baseVersion
                        + " 的历史快照缺失或不可读），为避免覆盖他人的修改，本次编辑未写入——请基于最新版本重新确认",
                        BizErrorCode.VERSION_CONFLICT);
            }
            if (outcome.status() == RevisionMergeStatus.CONFLICT && !forceLatest) {
                throw new ServiceException("需求点的" + String.join("、", outcome.conflictItems())
                        + "已被他人修改（草案基于版本 " + baseVersion + "，当前版本 " + current.version()
                        + "），本次编辑未写入——请基于最新版本重新确认，或选择「以本次为准」让本提案覆盖对方对这些字段的修改",
                        BizErrorCode.VERSION_CONFLICT);
            }
            anchor = current.version();
        }
        RequirementInfo saved = requirementService.update(operatorId, requirementId,
                new RequirementUpdateInput(DraftPayloadReads.text(body, "title"), null, null,
                        DraftPayloadReads.text(body, "acceptanceCriteria"), DraftPayloadReads.text(body, "chapter"), null,
                        anchor));
        return saved.version();
    }

    /** Keys the payload actually carries (null-means-untouched, same rule as the case path). */
    private static Set<String> proposalFields(JsonNode body) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : EDITABLE_KEYS) {
            JsonNode node = body.get(key);
            if (node != null && !node.isNull()) {
                out.add(key);
            }
        }
        return out;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 需求草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }

}
