package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Mock-stub rule strategy behind the generic draft door (tool-surface
 * clearing batch stage 8, fourteenth cleared domain). One confirmed
 * dialog proposal files or edits a stub rule through the SAME
 * {@code MockStubService.create/update} chain the endpoint page's editor
 * uses — the rule validator (name length, method one-of, path shape with the
 * regex-compile and template-brace pairing gates, matcher/response JSON
 * shape, fault-parameter domain gate), the space-existence check, the
 * scenario/state declaration kernel and the optimistic version conflict all
 * re-run at landing time, so the dialog is structurally unable to bypass one
 * gate (red line: the save chain owns the decision, the draft card only
 * states it).
 * <p>Attribution guard (H1): the owning project is read from the chat
 * session and the target space is re-checked against it — a create names a
 * space id in the payload, so the space's own project must equal the
 * session's project before anything is filed; an edit's row is read first
 * and its space's project compared the same way. A model-forged id can never
 * file or edit a rule in another project's lane.
 * <p>One bean serves both verbs (target_id = 0 means create, > 0 means edit)
 * exactly like the case write strategy: the two built-in tools
 * (mock.stub.create / mock.stub.update) share the MOCK_STUB_WRITE kind and
 * the card anchors already pin which side of the door the row lands on.
 * A created rule only serves traffic while its owning space is published
 * and the rule enabled — publishing and the enabled switch stay deliberate
 * human decisions the landing never smuggles (create defaults enabled to the
 * chain's own default, the payload may explicitly file a disabled rule but
 * can never publish a space).
 * <p>Edit concurrency: the card pinned base_version = the row version the
 * model read; a stale version on the row refuses the landing with the
 * plain-word conflict before the save chain is even dialed (same anchor
 * reasoning as {@code CaseWriteDraftStrategy}).
 */
@Service
public class MockStubWriteDraftStrategy implements DraftWriteStrategy {

    private final MockStubService mockStubService;
    private final MockSpaceService mockSpaceService;
    private final DraftProjectAnchor draftProjectAnchor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MockStubWriteDraftStrategy(MockStubService mockStubService,
                                      MockSpaceService mockSpaceService,
                                      DraftProjectAnchor draftProjectAnchor,
                                      JdbcTemplate jdbcTemplate) {
        this.mockStubService = mockStubService;
        this.mockSpaceService = mockSpaceService;
        this.draftProjectAnchor = draftProjectAnchor;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.MOCK_STUB_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        // H1 / AN batch piece 4a: the project segment comes from the
        // origin-aware anchor — dialog cards still read the session,
        // linkage-minted system cards read their stored project column
        // (single source: DraftProjectAnchor).
        long projectId = draftProjectAnchor.resolveForLanding(draftRow);
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        long targetId = ((Number) draftRow.get("target_id")).longValue();
        return targetId > 0
                ? landEdit(draftId, draftRow, projectId, body, operatorId,
                        DraftWriteStrategy.ACTION_FORCE.equals(selectedAction))
                : landCreate(draftId, projectId, body, operatorId);
    }

    /** Create proposal: the named space must live in the session's project, then the shared save chain files the row. */
    private Integer landCreate(long draftId, long projectId, JsonNode body, long operatorId) {
        long spaceId = body.path("spaceId").asLong(0L);
        if (spaceId <= 0) {
            throw new ServiceException(
                    "AI Mock 规则草案缺少所属空间编号，无法创建（请重新让 AI 生成提案）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // The space-existence check and every shape decision belong to the
        // chain; this door only adds the cross-project refusal the payload
        // cannot be trusted for.
        long spaceProjectId = mockSpaceService.get(spaceId).projectId();
        if (spaceProjectId != projectId) {
            throw new ServiceException("该草案指向的 Mock 空间不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        long stubId = mockStubService.create(spaceId, readInput(body, null, false));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", stubId, draftId);
        // The fresh row's version is the ledger value the human echoes next.
        return mockStubService.get(stubId).version();
    }

    /** Edit proposal: read the row, verify its space against the session project and the optimistic anchor, then the shared update chain. */
    private Integer landEdit(long draftId, Map<String, Object> draftRow, long projectId,
                             JsonNode body, long operatorId, boolean forceLatest) {
        long stubId = ((Number) draftRow.get("target_id")).longValue();
        int baseVersion = ((Number) draftRow.get("base_version")).intValue();
        var current = mockStubService.get(stubId);
        long spaceProjectId = mockSpaceService.get(current.spaceId()).projectId();
        if (spaceProjectId != projectId) {
            throw new ServiceException("该草案指向的规则不属于当前会话所在项目，确认写入已拒绝",
                    BizErrorCode.FORBIDDEN);
        }
        if (current.version() != null && current.version() != baseVersion) {
            // The card-time anchor is server-side since , so this
            // mismatch is a real race: refuse with the gate-baseline wording
            // proposal onto the LIVE version when the human chose force —
            // the same posture as the case-write conflict exit.
            if (!forceLatest) {
                throw new ServiceException("规则已被他人修改（草案基于版本 " + baseVersion
                        + "，当前版本 " + current.version() + "），本次编辑未写入——"
                        + "请基于最新版本重新确认，或选择「以本次为准」让本提案覆盖对方对这些字段的修改",
                        BizErrorCode.VERSION_CONFLICT);
            }
            baseVersion = current.version();
        }
        var saved = mockStubService.update(stubId, readInput(body, baseVersion, true));
        return saved.version();
    }

    /**
     * Payload → the canonical save input. Field legality (method one-of, path
     * shape, JSON payload shapes, scenario states) is NOT re-decided here —
     * MockRuleValidator owns that spectrum inside the chain; this reader only
     * transports what the model named, letting the chain default the rest.
     */
    private MockStubInput readInput(JsonNode body, Integer expectedVersion, boolean edit) {
        return new MockStubInput(
                body.path("name").asText("").trim(),
                body.hasNonNull("priority") ? body.path("priority").asInt(100) : null,
                body.path("method").asText("").trim(),
                body.hasNonNull("pathMatchType") ? body.path("pathMatchType").asText(null) : null,
                body.path("pathPattern").asText("").trim(),
                DraftPayloadReads.textOrNull(body, "matchersJson"),
                DraftPayloadReads.textOrNull(body, "responseJson"),
                DraftPayloadReads.textOrNull(body, "scenarioName"),
                DraftPayloadReads.textOrNull(body, "requiredState"),
                DraftPayloadReads.textOrNull(body, "newState"),
                // Provenance stays the chain's default "manual" unless the
                // dialog explicitly says otherwise; source_ref never rides
                // from a model body (a forged origin badge is refused here —
                // the edit chain never touches provenance columns anyway).
                DraftPayloadReads.textOrNull(body, "source"),
                null,
                body.hasNonNull("enabled") ? body.path("enabled").asBoolean(true) : null,
                edit ? expectedVersion : null);
    }

    /** Blank or missing optional text reads as "not stated" (the chain defaults it). */
    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "AI Mock 规则草案内容无法解析，确认写入已拒绝（请重新让 AI 生成提案）");
    }
}
