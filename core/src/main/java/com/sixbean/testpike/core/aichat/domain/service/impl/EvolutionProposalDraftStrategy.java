package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.EvolutionNegativeGate;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Landing strategy for self-evolution proposals (AF-i phase 2b-ii): one
 * confirmed card of the EVOLUTION_PROPOSAL kind dispatches onto the SAME
 * chain its human page counterpart uses, per the proposal family — a prompt
 * revision appends a template version through PromptTemplateService.save,
 * a tool definition files a DRAFT tool through ToolService.create (the
 * draft→review→publish gate stays human-owned), and a grounding revision
 * enters the document world through DocumentService.upload (parse + ingest
 * like every other document). The evolution loop is never a bypass (red line
 * 6, no parallel write path per family).
 * <p>Second negative-gate pass: the assembly point already refused forbidden
 * candidates, and the landing re-checks the payload's own surface names
 * before writing. Defense in depth on a fail-closed rule costs one call and
 * closes the hand-inserted-row case (a draft written by SQL or an older
 * deploy cannot smuggle a forbidden surface through the door).
 * <p>Attribution: the owning project comes from the draft's session (read
 * here through the ledger), never from the payload; the confirming human is
 * the author on every produced row — never the model, never a forged id.
 */
@Service
public class EvolutionProposalDraftStrategy implements DraftWriteStrategy {

    private static final Logger log = LoggerFactory.getLogger(EvolutionProposalDraftStrategy.class);

    private final PromptTemplateService promptTemplateService;
    private final ToolService toolService;
    private final DocumentService documentService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvolutionProposalDraftStrategy(PromptTemplateService promptTemplateService,
                                          ToolService toolService,
                                          DocumentService documentService,
                                          JdbcTemplate jdbcTemplate) {
        this.promptTemplateService = promptTemplateService;
        this.toolService = toolService;
        this.documentService = documentService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String objectType() {
        return DraftObjectType.EVOLUTION_PROPOSAL.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        long draftId = ((Number) draftRow.get("id")).longValue();
        JsonNode body = parseBody(String.valueOf(draftRow.get("payload_json")));
        String subKind = DraftPayloadReads.text(body, "subKind");
        if (subKind == null || subKind.isBlank()) {
            throw new ServiceException("自进化提案缺少提案类型（应为「提示词模板」「工具定义」「接地文档」之一），确认写入已拒绝；"
                    + "请在「AI 对话」让助手补全提案类型后重新生成这条提案，或前往「提示词模板」页手动新建",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Re-check the fail-closed negative list at the door itself.
        switch (subKind) {
            case EvolutionProposalServiceImpl.SUBKIND_PROMPT_TPL -> {
                requireAllowed(DraftPayloadReads.text(body, "name"), DraftPayloadReads.text(body, "domain"));
                return landPrompt(draftId, body, operatorId);
            }
            case EvolutionProposalServiceImpl.SUBKIND_TOOL_DEF -> {
                requireAllowed(DraftPayloadReads.text(body, "name"), DraftPayloadReads.text(body, "description"), DraftPayloadReads.text(body, "body"));
                return landTool(draftId, body, operatorId);
            }
            case EvolutionProposalServiceImpl.SUBKIND_GROUNDING -> {
                requireAllowed(DraftPayloadReads.text(body, "filename"));
                return landGrounding(draftId, body, operatorId);
            }
            default -> throw new ServiceException("未知的自进化提案类型：「" + subKind
                    + "」，仅支持「提示词模板」「工具定义」「接地文档」三类；"
                    + "请在「AI 对话」让助手按正确类型重新生成这条提案后再确认", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Prompt family: append a new template version through the human save chain. */
    private Integer landPrompt(long draftId, JsonNode body, long operatorId) {
        String name = require(DraftPayloadReads.text(body, "name"), "模板名");
        String domain = require(DraftPayloadReads.text(body, "domain"), "业务域");
        String content = require(DraftPayloadReads.text(body, "content"), "正文");
        PromptTemplateItem saved = promptTemplateService.save(
                new PromptTemplateSaveInput(name, domain, content, operatorId));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", saved.id(), draftId);
        // The version number is the applied ledger for this family.
        return saved.version();
    }

    /** Tool family: file a DRAFT tool — publishing stays a deliberate human review. */
    private Integer landTool(long draftId, JsonNode body, long operatorId) {
        String name = require(DraftPayloadReads.text(body, "name"), "工具名");
        Long projectId = sessionProjectId(draftId);
        // type=1 DOC (documentation-first onboarding), submitNow=false: the
        // draft→review→publish gate is exactly what the evolution loop must
        // never shortcut, so nothing is submitted for release here.
        var created = toolService.create(operatorId, new ToolCreateInput(
                projectId, name, 1, "自进化提案",
                DraftPayloadReads.text(body, "description"), DraftPayloadReads.text(body, "body"),
                null, null, null, null, null, null, false));
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", created.id(), draftId);
        log.info("evolution tool draft landed toolId={} draftId={}", created.id(), draftId);
        return null;
    }

    /** Grounding family: upload through the document chain (parse + ingest). */
    private Integer landGrounding(long draftId, JsonNode body, long operatorId) {
        String filename = require(DraftPayloadReads.text(body, "filename"), "文件名");
        String content = require(DraftPayloadReads.text(body, "content"), "正文");
        Long projectId = sessionProjectId(draftId);
        DocumentInfo doc = documentService.upload(operatorId, projectId, filename,
                content.getBytes(StandardCharsets.UTF_8), null);
        jdbcTemplate.update("UPDATE tp_ai_chat_draft SET target_id = ?, updated_at = now() "
                + "WHERE id = ?", doc.id(), draftId);
        return null;
    }

    /** The draft's session project — the only trustworthy attribution source. */
    private Long sessionProjectId(long draftId) {
        return jdbcTemplate.queryForObject("""
                SELECT s.project_id FROM tp_ai_chat_draft d
                JOIN tp_ai_chat_session s ON s.id = d.session_id
                WHERE d.id = ?
                """, Long.class, draftId);
    }

    private void requireAllowed(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                EvolutionNegativeGate.requireAllowed(java.util.List.of(candidate));
            }
        }
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ServiceException("自进化提案缺少必填字段「" + label + "」，确认写入已拒绝；"
                    + "请在「AI 对话」让助手补全该字段后重新生成这条提案",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return value;
    }

    private JsonNode parseBody(String payloadJson) {
        return DraftPayloadReads.parseOrRefuse(objectMapper, payloadJson, "自进化提案内容无法解析，确认写入已拒绝（请重新生成提案）");
    }

}
