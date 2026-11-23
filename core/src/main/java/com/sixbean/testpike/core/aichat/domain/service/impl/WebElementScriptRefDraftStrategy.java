package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Script reference-replacement landing strategy behind the generic draft
 * door (WE-S6 census migration, piece 3b): a confirmed card lifts the
 * script one new version through {@code ScriptService.saveRepaired} — the
 * SAME landing chain the repair card uses, so the version line snapshot,
 * the static validation refusal semantics ("invalid payload = original
 * script untouched") and the review state machine neutrality all stay
 * single-sourced (R40). No second write path exists for this kind.
 * <p>Owns the kind-specific gates: the element must still live in the page
 * element library (a retired or stale anchor would ship a script whose
 * reference resolves to nothing), and the optimistic script-version anchor
 * card's own pre-replacement payload is what an operator replays to roll
 * the script back per case — same door, no privileged reverse path.
 */
@Service
public class WebElementScriptRefDraftStrategy implements DraftWriteStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ScriptService scriptService;
    private final NamedParameterJdbcTemplate jdbc;

    public WebElementScriptRefDraftStrategy(ScriptService scriptService,
                                            NamedParameterJdbcTemplate jdbc) {
        this.scriptService = scriptService;
        this.jdbc = jdbc;
    }

    @Override
    public String objectType() {
        return DraftObjectType.WEB_ELEMENT_SCRIPT_REF.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        return land(draftRow, operatorId, null);
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        Object caseAnchor = draftRow.get("target_id");
        Object projectAnchor = draftRow.get("project_id");
        if (caseAnchor == null || projectAnchor == null) {
            throw new ServiceException("该引用替换草案缺少用例或项目归属，确认落版已拒绝（请重新发起存量普查）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long caseId = ((Number) caseAnchor).longValue();
        long projectId = ((Number) projectAnchor).longValue();
        JsonNode body = DraftPayloadReads.parseOrRefuse(MAPPER,
                String.valueOf(draftRow.get("payload_json")),
                "页面元素引用替换草案内容无法解析，确认落版已拒绝（请重新发起存量普查）");
        String actionJson = body.path("actionJson").asText("");
        String elementName = body.path("elementName").asText("");
        if (actionJson.isBlank() || elementName.isBlank()) {
            throw new ServiceException("该引用替换草案缺少替换后的脚本内容或元素名，确认落版已拒绝（请重新发起存量普查）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM tp_web_element
                WHERE project_id = :projectId AND name = :name AND state IN (0, 1)
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId).addValue("name", elementName),
                Integer.class);
        if (live == null || live == 0) {
            throw new ServiceException("页面元素「" + elementName
                    + "」已停用或失效，本次引用替换未写入——请在页面元素库恢复该元素，或重新发起存量普查",
                    BizErrorCode.FORBIDDEN);
        }
        ScriptInfo current = scriptService.getByCaseId(caseId);
        if (current == null) {
            throw new ServiceException("该用例当前没有脚本，引用替换草案无法落版（请先生成脚本）",
                    BizErrorCode.NOT_FOUND);
        }
        int baseVersion = body.path("baseScriptVersion").asInt(-1);
        if (current.version() != baseVersion
                && !DraftWriteStrategy.ACTION_FORCE.equals(selectedAction)) {
            throw new ServiceException("脚本已被他人修改（草案基于版本 " + baseVersion
                    + "，当前版本 " + current.version() + "），本次引用替换未写入——"
                    + "请重新发起存量普查，或选择「以本次为准」让替换方案直接落版",
                    BizErrorCode.VERSION_CONFLICT);
        }
        ScriptInfo saved = scriptService.saveRepaired(caseId,
                body.path("schemaVersion").asText("v2"), actionJson);
        return saved.version();
    }
}
