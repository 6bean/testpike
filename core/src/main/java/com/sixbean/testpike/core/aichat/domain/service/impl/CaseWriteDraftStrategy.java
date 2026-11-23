package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeGate;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeOutcome;
import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeStatus;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseSnapshotStep;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Case-write landing strategy behind the generic draft door (AO batch
 * stage 2): one confirmed dialog proposal creates OR edits a test case
 * through the SAME CaseService save chain the human UI uses — field
 * validation, step renumbering, version snapshots and audit all come free,
 * never a parallel write path (red line: no second implementation of the
 * case save).
 * <p>Two shapes keyed by the ledger row itself:
 * <ul>
 * <li>{@code target_id} NULL = create. The project is taken from the
 * draft's own origin — its chat session for a dialog card, the stored
 * project anchor column for a system-origin card (page write-risk
 * converter and other mint doors) — never from the proposal body, so
 * a crafted payload cannot aim the write at another project (H1).
 * The dual-source read lives in {@link DraftProjectAnchor}, the one
 * place every landing strategy and the confirmation door agree on;</li>
 * <li>{@code target_id} set = field-level edit. When the row version moved
 * past the draft's optimistic anchor the write is not refused wholesale:
 * the revision-merge gate diffs the proposal's fields against
 * the concurrent edits between base and current snapshots — disjoint
 * proposals rebase onto the current version through the same chained
 * update (overlay merge keeps the counterpart's untouched steps),
 * intersecting ones are refused naming every moved field/step, and an
 * unreconstructable baseline refuses conservatively.</li>
 * </ul>
 * <p>Attribution guard: an edit proposal aimed at a case outside the
 * draft's own project is refused (40301) before anything moves — the ownership
 * door of the confirmation chain covers WHO may confirm, this covers WHAT the
 * draft may touch (AF-g horizontal spectrum, same rule as the tool assembly).
 * <p>Only the kind's own shape is validated here; every business rule
 * (title length, step bounds, priority values...) stays single-sourced in
 * CaseServiceImpl — a payload CaseService rejects surfaces as CaseService's
 * own human-readable error, never reworded into a second guard spectrum.
 */
@Service
public class CaseWriteDraftStrategy implements DraftWriteStrategy {

    /** Proposal payload keys that map onto a version-snapshot scalar field. */
    private static final List<String> SCALAR_KEYS =
            List.of("title", "precondition", "expectedResult", "priority", "confidence", "endType", "phase");

    private final CaseService caseService;
    private final ChatSessionService sessionService;
    private final JdbcTemplate jdbcTemplate;
    private final RevisionMergeGate revisionMergeGate;
    private final DraftProjectAnchor draftProjectAnchor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Step-anchor dialect single source . */
    private final com.sixbean.testpike.core.cases.domain.component.CaseStepAnchorNormalizer anchorNormalizer =
            new com.sixbean.testpike.core.cases.domain.component.CaseStepAnchorNormalizer(objectMapper);

    public CaseWriteDraftStrategy(CaseService caseService,
                                  ChatSessionService sessionService,
                                  JdbcTemplate jdbcTemplate,
                                  RevisionMergeGate revisionMergeGate,
                                  DraftProjectAnchor draftProjectAnchor) {
        this.caseService = caseService;
        this.sessionService = sessionService;
        this.jdbcTemplate = jdbcTemplate;
        this.revisionMergeGate = revisionMergeGate;
        this.draftProjectAnchor = draftProjectAnchor;
    }

    @Override
    public String objectType() {
        return DraftObjectType.CASE_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        long draftId = DraftPayloadReads.requireDraftId(draftRow);
        JsonNode body = DraftPayloadReads.parseOrRefuse(objectMapper,
                String.valueOf(draftRow.get("payload_json")),
                "AI 用例草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
        // Origin-aware project read (single source with the confirmation door):
        // session cards keep the session segment byte-identical, system-origin
        // cards resolve from their stored anchor column, and a row with
        // neither origin refuses in actionable words — never a raw NPE.
        long projectId = draftProjectAnchor.resolveForLanding(draftRow);
        Object targetRaw = draftRow.get("target_id");
        return targetRaw == null
                ? landCreate(draftId, projectId, body, operatorId)
                : landEdit(draftRow, projectId, body, operatorId,
                        DraftWriteStrategy.ACTION_FORCE.equals(selectedAction));
    }

    /** Create proposal: lands in the draft's own project; case id pinned back onto the ledger row. */
    private Integer landCreate(long draftId, long projectId, JsonNode body, long operatorId) {
        String title = DraftPayloadReads.text(body, "title");
        if (title == null || title.isBlank()) {
            throw new ServiceException("AI 用例草案缺少标题，无法创建用例（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        CaseInfo created = caseService.create(operatorId, new CaseCreateInput(
                projectId, title.trim(), DraftPayloadReads.text(body, "precondition"), DraftPayloadReads.text(body, "expectedResult"),
                intOrNull(body, "priority"), intOrNull(body, "confidence"),
                DraftPayloadReads.text(body, "endType"), null, intOrNull(body, "phase"), 1,
                // Conversation-landed cases carry the chat provenance marker so the review
                // queue facet can tell in-product AI drafts apart from external agents.
                com.sixbean.testpike.core.cases.domain.component.CaseSourceFacet.CHAT,
                readSteps(body)));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        return created.version();
    }

    /** Edit proposal: attribution guard, then anchor check with the revision-merge gate. */
    private Integer landEdit(Map<String, Object> draftRow, long projectId,
                             JsonNode body, long operatorId, boolean forceLatest) {
        long caseId = ((Number) draftRow.get("target_id")).longValue();
        int baseVersion = ((Number) draftRow.get("base_version")).intValue();
        CaseInfo current = caseService.get(caseId);
        if (current.projectId() != projectId) {
            throw new ServiceException("该草案指向的用例不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        int anchor = baseVersion;
        List<CaseStepInput> steps = readSteps(body);
        if (current.version() != baseVersion) {
            // Stale anchor is no longer a blanket refusal: the gate decides
            // whether the proposal and the concurrent edits intersect.
            RevisionMergeOutcome outcome = revisionMergeGate.assess(caseId, baseVersion,
                    current.version(), proposalFields(body), snapshotSteps(steps));
            if (outcome.status() == RevisionMergeStatus.BASELINE_MISSING) {
                throw new ServiceException("无法核对草案的基线内容（草案基于版本 " + baseVersion
                        + " 的历史快照缺失或不可读），为避免覆盖他人的修改，本次编辑未写入——请基于最新版本重新确认",
                        BizErrorCode.VERSION_CONFLICT);
            }
            if (outcome.status() == RevisionMergeStatus.CONFLICT && !forceLatest) {
                throw new ServiceException("用例的" + String.join("、", outcome.conflictItems())
                        + "已被他人修改（草案基于版本 " + baseVersion + "，当前版本 " + current.version()
                        + "），本次编辑未写入——请基于最新版本重新确认，或选择「以本次为准」让本提案覆盖对方对这些字段的修改",
                        BizErrorCode.VERSION_CONFLICT);
            }
            if (outcome.status() == RevisionMergeStatus.REBASE) {
                anchor = outcome.targetVersion();
                if (outcome.mergedSteps() != null) {
                    steps = toStepInputs(outcome.mergedSteps());
                }
            } else {
                // Force exit: replay the proposal as-is on the current version —
                // the human explicitly chose this proposal over the counterpart.
                anchor = current.version();
            }
        }
        CaseInfo saved = caseService.update(operatorId, caseId, new CaseUpdateInput(
                DraftPayloadReads.text(body, "title"), DraftPayloadReads.text(body, "precondition"), DraftPayloadReads.text(body, "expectedResult"),
                intOrNull(body, "priority"), intOrNull(body, "confidence"),
                DraftPayloadReads.text(body, "endType"), intOrNull(body, "phase"), steps),
                anchor);
        return saved.version();
    }

    @Override
    public java.util.Optional<RevisionMergeStatus> renewalVerdict(Map<String, Object> siblingRow,
                                                                  int currentVersion) {
        // Same reading the landing path uses (R40 single source): the gate
        // decides whether this pending proposal still fits the moved case.
        long caseId = ((Number) siblingRow.get("target_id")).longValue();
        int baseVersion = ((Number) siblingRow.get("base_version")).intValue();
        JsonNode body = parseBody(String.valueOf(siblingRow.get("payload_json")));
        RevisionMergeOutcome outcome = revisionMergeGate.assess(caseId, baseVersion, currentVersion,
                proposalFields(body), snapshotSteps(readSteps(body)));
        return java.util.Optional.of(outcome.status());
    }

    /** Keys the payload actually carries (same null-means-untouched rule as the field update). */
    private static Set<String> proposalFields(JsonNode body) {
        Set<String> out = new LinkedHashSet<>();
        for (String key : SCALAR_KEYS) {
            JsonNode node = body.get(key);
            if (node != null && !node.isNull()) {
                out.add(key);
            }
        }
        JsonNode steps = body.get("steps");
        if (steps != null && steps.isArray()) {
            out.add("steps");
        }
        return out;
    }

    /** Proposal steps in snapshot shape for the gate; null stays null = step region untouched. */
    private static List<CaseSnapshotStep> snapshotSteps(List<CaseStepInput> steps) {
        if (steps == null) {
            return null;
        }
        List<CaseSnapshotStep> out = new ArrayList<>();
        for (CaseStepInput step : steps) {
            out.add(new CaseSnapshotStep(step.stepNo(), step.description(),
                    step.isAssert() == null ? 0 : step.isAssert()));
        }
        return out;
    }

    /** Merged snapshot steps back into input shape for the shared save chain. */
    private static List<CaseStepInput> toStepInputs(List<CaseSnapshotStep> steps) {
        List<CaseStepInput> out = new ArrayList<>();
        for (CaseSnapshotStep step : steps) {
            out.add(new CaseStepInput(step.stepNo(), step.description(), step.isAssert()));
        }
        return out;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI 用例草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }

    /** Steps array → input list in given order (server renumbers 1..n per CaseService). */
    private List<CaseStepInput> readSteps(JsonNode body) {
        JsonNode steps = body.get("steps");
        if (steps == null || !steps.isArray()) {
            return null; // create: no steps given; edit: null = leave steps untouched
        }
        List<CaseStepInput> list = new ArrayList<>();
        int no = 1;
        for (JsonNode step : steps) {
            String desc = step.path("description").asText(null);
            list.add(new CaseStepInput(step.hasNonNull("stepNo")
                            ? step.get("stepNo").asInt(no) : no,
                    desc,
                    step.hasNonNull("isAssert") ? step.get("isAssert").asInt() : 0,
                    // Draft anchors go through the single-source normalizer; the
                    // {{ELEM:name}} dialect in the description derives the anchor
                    // when the draft carries no explicit evidence .
                    anchorNormalizer.normalize(
                            step.hasNonNull("anchor") ? step.get("anchor").asText(null) : null, desc)));
            no++;
        }
        return list;
    }

    private static Integer intOrNull(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || !node.isNumber() ? null : node.asInt();
    }
}
