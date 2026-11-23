package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftInput;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftMintInput;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftSeqAllocator;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ledger door for system-origin proposals (AP batch , first and
 * only consumer = the distilled project-memory channel).
 * <p>Guards before anything becomes confirmable, all fail-closed with plain
 * human-readable refusals:
 * <ul>
 * <li>a real project anchor (an unanchored row would die at the door's
 * system branch — refuse at posting instead of leaving a dead card);</li>
 * <li>a kind registered for landing (mirrors the door's dispatch table:
 * collecting the same {@link DraftWriteStrategy} beans, so a proposal
 * without a write strategy can never be filed as "pending");</li>
 * <li>non-blank entry text.</li>
 * </ul>
 * <p>The write is a pure ledger insert: session_id/message_id stay NULL
 * (relaxed in V363), project_id carries the anchor, status starts pending.
 * Promotion itself stays exclusively behind the human confirmation door —
 * posting an entry never injects it.
 */
@Service
public class SystemDraftServiceImpl implements SystemDraftService {

    /** Card summary ceiling (same posture as the frame-ledger projection). */
    private static final int SUMMARY_MAX_CHARS = 500;

    /** Draft ledger state that already landed (1=pending 2=applied 3=voided,
     *  transitions guarded service-side per the K8 convention of V302). */
    private static final int DRAFT_STATUS_APPLIED = 2;

    private final NamedParameterJdbcTemplate named;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final DraftSeqAllocator draftSeqAllocator;
    private final Set<String> landableKinds;

    public SystemDraftServiceImpl(NamedParameterJdbcTemplate named,
                                  ProjectService projectService,
                                  ObjectMapper objectMapper,
                                  DraftSeqAllocator draftSeqAllocator,
                                  List<DraftWriteStrategy> writeStrategies) {
        this.named = named;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.draftSeqAllocator = draftSeqAllocator;
        this.landableKinds = new HashSet<>();
        for (DraftWriteStrategy strategy : writeStrategies) {
            this.landableKinds.add(strategy.objectType());
        }
    }

    @Override
    public long postSystemDraft(SystemDraftInput input) {
        if (input == null || input.projectId() == null
                || !projectService.exists(input.projectId())) {
            throw new ServiceException("系统草案缺少有效的项目归属，已拒绝登记"
                    + "——页面发起的草案由系统按顶栏「选择项目」自动携带归属，若顶栏未显示项目请先选择项目再重试；"
                    + "接口对接请改成携带有效的 projectId（正例：projectId=1 且该项目存在）后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String objectType = input.objectType() == null ? "" : input.objectType().trim();
        if (DraftObjectType.resolveOrDefault(objectType).isEmpty()
                || !landableKinds.contains(objectType)) {
            throw new ServiceException("该系统草案类型暂不支持落地（object_type="
                    + objectType + "），已拒绝登记——正确写法：把 object_type 改成下列受支持的对象类型之一后重试，当前支持："
                    + String.join(" / ", new TreeSet<>(landableKinds)),
                    BizErrorCode.VALIDATION_ERROR);
        }
        String content = input.content() == null ? "" : input.content().trim();
        if (content.isEmpty()) {
            throw new ServiceException("系统草案缺少条目正文，已拒绝登记"
                    + "——条目正文来自对话中生成的草案内容；若内容为空请补充诉求后在对话里重新发起一次生成"
                    + "（可从「历史对话」找回原会话，或点「新建对话」重新开始）；接口对接请携带非空 content 后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("content", content);
        payload.put("sourceRef", input.sourceRef() == null ? "[]" : input.sourceRef());
        String summary = content.substring(0, Math.min(content.length(), SUMMARY_MAX_CHARS));
        return insertDraft(input.projectId(), objectType, input.targetId(), summary,
                payload.toString());
    }

    @Override
    public long postStructuredDraft(Long projectId, String objectType, String summary,
                                    String payloadJson, Long targetId) {
        if (projectId == null || !projectService.exists(projectId)) {
            throw new ServiceException("系统草案缺少有效的项目归属，已拒绝登记"
                    + "——页面发起的草案由系统按顶栏「选择项目」自动携带归属，若顶栏未显示项目请先选择项目再重试；"
                    + "接口对接请改成携带有效的 projectId（正例：projectId=1 且该项目存在）后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String kind = objectType == null ? "" : objectType.trim();
        if (DraftObjectType.resolveOrDefault(kind).isEmpty()
                || !landableKinds.contains(kind)) {
            throw new ServiceException("该系统草案类型暂不支持落地（object_type="
                    + kind + "），已拒绝登记——正确写法：把 object_type 改成下列受支持的对象类型之一后重试，当前支持："
                    + String.join(" / ", new TreeSet<>(landableKinds)),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (summary == null || summary.isBlank() || payloadJson == null
                || payloadJson.isBlank()) {
            throw new ServiceException("系统草案缺少卡片摘要或提案内容，已拒绝登记"
                    + "——摘要与提案内容由系统在登记时装配，缺失即中止；请在对话里重新发起一次生成"
                    + "（可从「历史对话」找回原会话，或点「新建对话」重新开始）；接口对接请分别携带非空 summary 与 payloadJson 后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return insertDraft(projectId, kind, targetId, summary, payloadJson);
    }

    /** The one ledger insert every system-origin shape shares (R40). */
    private long insertDraft(long projectId, String objectType, Long targetId,
                             String summary, String payloadJson) {
        Long draftId = named.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (session_id, message_id, project_id, object_type, target_id, schema_version,
                     summary, payload_json, status, seq)
                VALUES (NULL, NULL, :projectId, :objectType, :targetId, '1',
                        :summary, CAST(:payloadJson AS jsonb), 1, :seq)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("objectType", objectType)
                .addValue("targetId", targetId)
                .addValue("seq", draftSeqAllocator.nextSeq(targetId))
                .addValue("summary", summary)
                .addValue("payloadJson", payloadJson), Long.class);
        if (draftId == null) {
            throw new ServiceException("系统草案登记失败（台账未返回编号）"
                    + "——系统侧登记异常，与您的输入无关，请重试一次；若仍失败请稍后再试，"
                    + "并可在「历史对话」中原会话追问查看结果，持续失败请联系管理员核查",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return draftId;
    }

    @Override
    public long mintLinkageDraft(SystemDraftMintInput input) {
        if (input == null || input.impactId() == null || input.projectId() == null
                || input.targetId() == null || !projectService.exists(input.projectId())) {
            throw new ServiceException("联动草案缺少有效的项目或用例锚点，已拒绝登记"
                    + "——该影响项指向的目标可能已被删除；请在「待处置的未提交改动」面板打开对应目标页面确认其仍存在，"
                    + "目标已删除时先重建目标再重新发起联动；页面发起的归属由顶栏「选择项目」自动携带",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String objectType = input.objectType() == null ? "" : input.objectType().trim();
        if (DraftObjectType.resolveOrDefault(objectType).isEmpty()
                || !landableKinds.contains(objectType)) {
            throw new ServiceException("联动草案类型暂不支持落地（object_type="
                    + objectType + "），已拒绝登记——正确写法：把 object_type 改成下列受支持的对象类型之一后重试，当前支持："
                    + String.join(" / ", new TreeSet<>(landableKinds)),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.payloadJson() == null || input.payloadJson().isBlank()) {
            throw new ServiceException("联动草案缺少提案正文，已拒绝登记"
                    + "——提案正文由系统随联动变更装配，缺失即中止；请在「待处置的未提交改动」面板对该影响项重新发起一次联动；"
                    + "接口对接请携带非空 payloadJson 后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String summary = clampSummary(input.summary());
        MapSqlParameterSource keyed = new MapSqlParameterSource("impactId",
                String.valueOf(input.impactId()));
        List<Map<String, Object>> cards = named.queryForList(
                "SELECT id, status FROM tp_ai_chat_draft"
                        + " WHERE context_json->>'linkageImpactId' = :impactId", keyed);
        if (!cards.isEmpty()) {
            Map<String, Object> card = cards.get(0);
            long cardId = ((Number) card.get("id")).longValue();
            int status = ((Number) card.get("status")).intValue();
            if (status == DRAFT_STATUS_APPLIED) {
                // a landed card records what the human already accepted;
                // re-arming it would rewrite history under the operator's feet
                return cardId;
            }
            // An identical replay must be a true no-op: rewriting the row
            // would stamp updated_at and fake a change the operator never
            // made, so the guard only lets a differing payload/version/state
            // through (jsonb equality compares semantics, not formatting).
            named.update("""
                    UPDATE tp_ai_chat_draft
                       SET base_version = :baseVersion, schema_version = :schemaVersion,
                           model_name = :modelName, summary = :summary,
                           payload_json = CAST(:payloadJson AS jsonb),
                           status = 1, updated_at = now()
                     WHERE id = :id
                       AND (base_version IS DISTINCT FROM :baseVersion
                            OR schema_version IS DISTINCT FROM :schemaVersion
                            OR model_name IS DISTINCT FROM :modelName
                            OR summary IS DISTINCT FROM :summary
                            OR status <> 1
                            OR payload_json::jsonb <> CAST(:payloadJson AS jsonb))
                    """, keyed.addValue("id", cardId)
                    .addValue("baseVersion", input.baseVersion())
                    .addValue("schemaVersion", input.schemaVersion())
                    .addValue("modelName", input.modelName())
                    .addValue("summary", summary)
                    .addValue("payloadJson", input.payloadJson()));
            return cardId;
        }
        Long draftId = named.queryForObject("""
                INSERT INTO tp_ai_chat_draft
                    (session_id, message_id, project_id, object_type, target_id, base_version,
                     schema_version, model_name, summary, payload_json, status, seq, context_json)
                VALUES (NULL, NULL, :projectId, :objectType, :targetId, :baseVersion,
                        :schemaVersion, :modelName, :summary, CAST(:payloadJson AS jsonb),
                        1, :seq, CAST(:contextJson AS jsonb))
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("projectId", input.projectId())
                .addValue("objectType", objectType)
                .addValue("targetId", input.targetId())
                .addValue("baseVersion", input.baseVersion())
                .addValue("schemaVersion", input.schemaVersion())
                .addValue("modelName", input.modelName())
                .addValue("summary", summary)
                .addValue("payloadJson", input.payloadJson())
                .addValue("seq", draftSeqAllocator.nextSeq(input.targetId()))
                .addValue("contextJson",
                        "{\"linkageImpactId\": " + input.impactId() + "}"), Long.class);
        if (draftId == null) {
            throw new ServiceException("联动草案登记失败（台账未返回编号）"
                    + "——系统侧登记异常，与您的输入无关，请在「待处置的未提交改动」面板对该影响项重试一次；"
                    + "若仍失败请稍后再试，持续失败请联系管理员核查",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        return draftId;
    }

    private static String clampSummary(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            throw new ServiceException("联动草案缺少摘要文案，已拒绝登记"
                    + "——摘要由系统从变更内容截取生成，空摘要即中止登记；请在「待处置的未提交改动」面板对该影响项重新发起一次联动，"
                    + "或在原变更描述里补充文字后再生成；接口对接请携带非空 summary 后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return content.substring(0, Math.min(content.length(), SUMMARY_MAX_CHARS));
    }
}
