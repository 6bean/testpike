package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.component.DraftPayloadReads;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.component.WebElementSurveyCollector;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Page-element ledger landing strategy behind the generic draft door
 * (WE-S6 census migration, piece 3, design A6): one confirmed card
 * files the surveyed element through the SAME {@code WebElementService
 * .create} chain the ledger page uses — encryption of the write path by
 * construction, the door is structurally the only writer (R40).
 * <p>The owning project comes from the draft row's own project anchor
 * (system-origin cards, same posture as the distilled-memory kind), never
 * from the payload. The produced ledger row id is pinned back onto
 * {@code target_id} so the panel can route the operator to the element.
 * A same-page same-name row already in the ledger refuses through create's
 * own conflict wording; the card stays pending for a re-decision.
 */
@Service
public class WebElementLedgerDraftStrategy implements DraftWriteStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORIGIN_NOTE = "存量普查迁入";

    private final WebElementService webElementService;
    private final NamedParameterJdbcTemplate named;

    public WebElementLedgerDraftStrategy(WebElementService webElementService,
                                         NamedParameterJdbcTemplate named) {
        this.webElementService = webElementService;
        this.named = named;
    }

    @Override
    public String objectType() {
        return DraftObjectType.WEB_ELEMENT_WRITE.wireName;
    }

    @Override
    public Integer land(Map<String, Object> draftRow, long operatorId) {
        Object anchor = draftRow.get("project_id");
        if (anchor == null) {
            throw new ServiceException("该草案缺少所属项目，无法确认写入——请将顶栏切换到目标项目后，到「页面元素库」重新发起存量普查",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long projectId = ((Number) anchor).longValue();
        JsonNode body = DraftPayloadReads.parseOrRefuse(MAPPER,
                String.valueOf(draftRow.get("payload_json")),
                "AI 页面元素草案内容无法解析，确认入账已拒绝（请重新发起存量普查）");
        LinkedHashMap<String, String> anchors = new LinkedHashMap<>();
        for (JsonNode node : body.path("anchors")) {
            String type = node.path("type").asText("");
            if (!type.isBlank()) {
                anchors.put(type, node.path("value").asText(""));
            }
        }
        List<WebElementAnchorInput> anchorInputs =
                WebElementSurveyCollector.ledgerAnchorInputs(anchors, ORIGIN_NOTE);
        WebElementView created = webElementService.create(new WebElementInput(
                projectId, body.path("name").asText(""), body.path("pageKey").asText(""),
                anchorInputs));
        named.update("UPDATE tp_ai_chat_draft SET target_id = :elementId WHERE id = :draftId",
                new MapSqlParameterSource()
                        .addValue("elementId", created.id())
                        .addValue("draftId", ((Number) draftRow.get("id")).longValue()));
        // tp_web_element has no version ledger (same null shape as the
        // in-place kinds); the created row is the object the human confirmed.
        return null;
    }
}
