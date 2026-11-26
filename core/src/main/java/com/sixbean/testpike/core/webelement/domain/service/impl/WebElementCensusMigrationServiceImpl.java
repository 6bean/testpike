package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerPosting;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusEntry;
import com.sixbean.testpike.core.webelement.domain.component.WebElementLibraryKeys;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusReport;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusMigrationService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Census migration posting door : folds the
 * read-only survey into one pending confirmation card per element, using
 * the shared system-origin draft ledger seam ({@link SystemDraftService})
 * so the card lands later through the SAME human confirmation door every
 * other proposal rides — filing itself writes no business table (red line:
 * the draft card gate is the only write path).
 * <p>Idempotency is keyed on the ledger, not a time window: an element
 * that already has a pending card in this project is counted and skipped.
 * The card payload carries the survey's own anchor material (page key,
 * anchor identity, typed anchors, referencing scripts) so the landing
 * strategy needs nothing but the card itself.
 */
@Service
public class WebElementCensusMigrationServiceImpl
        implements WebElementCensusMigrationService {

    private static final String KIND = DraftObjectType.WEB_ELEMENT_WRITE.wireName;

    private final WebElementCensusService censusService;
    private final SystemDraftService systemDraftService;
    private final WebElementLibraryKeys libraryKeys;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WebElementCensusMigrationServiceImpl(WebElementCensusService censusService,
                                                SystemDraftService systemDraftService,
                                                WebElementLibraryKeys libraryKeys,
                                                NamedParameterJdbcTemplate jdbc,
                                                ObjectMapper mapper) {
        this.censusService = censusService;
        this.systemDraftService = systemDraftService;
        this.libraryKeys = libraryKeys;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public WebElementLedgerPosting postLedgerDrafts(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new ServiceException("批量成账需要指定具体项目——请先在顶栏项目选择器选择一个项目，再到「页面元素库」页点「批量迁移入库」重新发起",
                    BizErrorCode.VALIDATION_ERROR);
        }
        WebElementCensusReport report = censusService.censusProject(projectId);
        Set<String> pending = pendingElementKeys(projectId);
        Set<String> library = libraryKeys.keysFor(projectId);
        int posted = 0;
        int alreadyPending = 0;
        int alreadyLedgered = 0;
        for (WebElementCensusEntry entry : report.entries()) {
            String key = entry.pageKey() + "\u0000" + entry.anchorIdentity();
            if (library.contains(key)) {
                alreadyLedgered++;
                continue;
            }
            if (!pending.add(key)) {
                alreadyPending++;
                continue;
            }
            systemDraftService.postStructuredDraft(projectId, KIND,
                    summaryOf(entry), payloadOf(entry), null);
            posted++;
        }
        return new WebElementLedgerPosting(report.entries().size(), posted, alreadyPending,
                alreadyLedgered);
    }

    /** Pending cards of this project keyed by page + anchor identity. */
    private Set<String> pendingElementKeys(long projectId) {
        Set<String> keys = new HashSet<>();
        jdbc.query("""
                SELECT payload_json::jsonb->>'pageKey' AS page_key,
                       payload_json::jsonb->>'anchorIdentity' AS anchor_identity
                FROM tp_ai_chat_draft
                WHERE project_id = :projectId AND object_type = :kind AND status = 1
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId).addValue("kind", KIND),
                rs -> {
                    keys.add(rs.getString("page_key") + "\u0000" + rs.getString("anchor_identity"));
                });
        return keys;
    }

    private static String summaryOf(WebElementCensusEntry entry) {
        return "%d 个用例在同一页面内联使用了同一元素（%s）；确认后记入页面元素库（页面 %s）"
                .formatted(entry.scriptCount(), entry.anchorIdentity(), entry.pageKey());
    }

    private String payloadOf(WebElementCensusEntry entry) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("pageKey", entry.pageKey());
        payload.put("anchorIdentity", entry.anchorIdentity());
        payload.put("name", entry.anchorIdentity());
        payload.put("stepCount", entry.stepCount());
        ArrayNode anchors = payload.putArray("anchors");
        for (Map.Entry<String, String> anchor : entry.anchors().entrySet()) {
            ObjectNode node = anchors.addObject();
            node.put("type", anchor.getKey());
            node.put("value", anchor.getValue());
        }
        ArrayNode scriptIds = payload.putArray("scriptIds");
        entry.scriptIds().forEach(scriptIds::add);
        return payload.toString();
    }
}
