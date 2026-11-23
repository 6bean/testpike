package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementScriptRefPosting;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusEntry;
import com.sixbean.testpike.core.webelement.domain.component.WebElementLibraryKeys;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusReport;
import com.sixbean.testpike.core.webelement.domain.component.WebElementScriptRefRewriter;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusScriptMigrationService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Script reference-replacement posting door : one
 * pending card per script whose inline locators duplicate an element already
 * in the page element library, filed through the shared system-origin draft
 * seam so landing rides the SAME human confirmation door as every other AI
 * write proposal (red line: filing itself writes no script).
 * <p>Exclusions are decided before anything is filed: elements still absent
 * from the library (state candidate/confirmed only), scripts whose slots for
 * this element are already reference-form, and scripts with a still-pending
 * card for this element — idempotency is keyed on the ledger, not a window.
 * The card payload is self-contained: reference-form action JSON plus the
 * pre-replacement copy (diff trail and per-case rollback material both ride
 * inside the card, version line snapshots aside).
 */
@Service
public class WebElementCensusScriptMigrationServiceImpl
        implements WebElementCensusScriptMigrationService {

    private static final String KIND = DraftObjectType.WEB_ELEMENT_SCRIPT_REF.wireName;

    private final WebElementCensusService censusService;
    private final SystemDraftService systemDraftService;
    private final WebElementScriptRefRewriter rewriter;
    private final WebElementLibraryKeys libraryKeys;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WebElementCensusScriptMigrationServiceImpl(WebElementCensusService censusService,
                                                      SystemDraftService systemDraftService,
                                                      WebElementScriptRefRewriter rewriter,
                                                      WebElementLibraryKeys libraryKeys,
                                                      NamedParameterJdbcTemplate jdbc,
                                                      ObjectMapper mapper) {
        this.censusService = censusService;
        this.systemDraftService = systemDraftService;
        this.rewriter = rewriter;
        this.libraryKeys = libraryKeys;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public WebElementScriptRefPosting postScriptRefDrafts(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new ServiceException("批量引用替换需要指定具体项目，请先在顶栏选择一个项目",
                    BizErrorCode.VALIDATION_ERROR);
        }
        WebElementCensusReport report = censusService.censusProject(projectId);
        Set<String> library = libraryKeys.keysFor(projectId);
        Set<String> pending = pendingCardKeys(projectId);
        int scanned = 0;
        int posted = 0;
        int notLedgered = 0;
        int alreadyPending = 0;
        for (WebElementCensusEntry entry : report.entries()) {
            String elementKey = entry.pageKey() + "\u0000" + entry.anchorIdentity();
            if (!library.contains(elementKey)) {
                notLedgered++;
                continue;
            }
            for (Map<String, Object> row : loadScripts(entry.scriptIds())) {
                scanned++;
                long caseId = ((Number) row.get("case_id")).longValue();
                if (!pending.add(caseId + "\u0000" + entry.anchorIdentity())) {
                    alreadyPending++;
                    continue;
                }
                String raw = (String) row.get("action_json");
                String replacedJson = rewriter.rewrite(raw, entry.pageKey(),
                        entry.anchorIdentity(), entry.anchorIdentity());
                if (replacedJson == null) {
                    // Nothing of this element is inline any more (the survey
                    // groups inline slots only, so landing a card makes the
                    // entry itself shrink — no separate counter needed).
                    continue;
                }
                systemDraftService.postStructuredDraft(projectId, KIND,
                        summaryOf(row, entry, replacedJson),
                        payloadOf(row, entry, raw, replacedJson), caseId);
                posted++;
            }
        }
        return new WebElementScriptRefPosting(report.entries().size(), scanned, posted,
                notLedgered, alreadyPending);
    }

    /** Still-pending replacement cards keyed by case + element name. */
    private Set<String> pendingCardKeys(long projectId) {
        Set<String> keys = new HashSet<>();
        jdbc.query("""
                SELECT target_id, payload_json::jsonb->>'elementName' AS element_name
                FROM tp_ai_chat_draft
                WHERE project_id = :projectId AND object_type = :kind AND status = 1
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId).addValue("kind", KIND),
                rs -> {
                    Object target = rs.getObject("target_id");
                    String name = rs.getString("element_name");
                    if (target != null && name != null) {
                        keys.add(((Number) target).longValue() + "\u0000" + name);
                    }
                });
        return keys;
    }

    private List<Map<String, Object>> loadScripts(List<Long> scriptIds) {
        return jdbc.queryForList("""
                SELECT s.id, s.case_id, s.schema_version, s.version, c.title,
                       s.action_json::text AS action_json
                FROM tp_script s
                JOIN tp_test_case c ON c.id = s.case_id
                WHERE s.id IN (:scriptIds)
                ORDER BY s.id
                """, new MapSqlParameterSource().addValue("scriptIds", scriptIds));
    }

    private String summaryOf(Map<String, Object> row, WebElementCensusEntry entry,
                             String replacedJson) {
        int slots = rewriter.countReferences(replacedJson, entry.anchorIdentity());
        return "用例「%s」有 %d 处内联定位改用页面元素「%s」（页面 %s）；确认后脚本升引用替换新版本，替换前内容随卡留存，可按用例回滚"
                .formatted(row.get("title"), slots, entry.anchorIdentity(), entry.pageKey());
    }

    private String payloadOf(Map<String, Object> row, WebElementCensusEntry entry,
                             String rawJson, String replacedJson) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("elementName", entry.anchorIdentity());
        payload.put("pageKey", entry.pageKey());
        payload.put("scriptId", ((Number) row.get("id")).longValue());
        payload.put("baseScriptVersion", ((Number) row.get("version")).intValue());
        payload.put("schemaVersion", String.valueOf(row.get("schema_version")));
        payload.put("replacedSlots",
                rewriter.countReferences(replacedJson, entry.anchorIdentity()));
        payload.put("beforeActionJson", rawJson);
        payload.put("actionJson", replacedJson);
        return payload.toString();
    }
}
