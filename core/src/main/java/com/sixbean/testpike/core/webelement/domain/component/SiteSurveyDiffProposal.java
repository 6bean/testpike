package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.webelement.api.dto.SiteSurveyDiffView;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Re-survey review proposal for one whole-site survey run :
 * compares the run's per-page write tally against the newest finished
 * earlier run of the same profile and reports new pages, vanished pages
 * and per-page candidate deltas. Read-only by contract — it never touches
 * tp_web_element; demoting vanished pages stays a human approval (the
 * one-way-door precedent from the flaky-storm batch).
 */
@Component
public class SiteSurveyDiffProposal {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SiteSurveyDiffProposal(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Proposal for {@code runId} within {@code profileId}. A run with no
     * earlier finished (non-cancelled) run of the same profile is honest
     * about being the first survey: everything counts as new.
     */
    public SiteSurveyDiffView proposal(long profileId, long runId) {
        List<Map<String, Object>> current = jdbc.queryForList(
                "SELECT page_writes FROM tp_site_survey_run"
                        + " WHERE id = :runId AND profile_id = :profileId",
                new MapSqlParameterSource("runId", runId).addValue("profileId", profileId));
        if (current.isEmpty()) {
            throw new ServiceException(
                    "该测绘记录不属于这个站点档案（或记录过旧，尚未包含逐页入账明细），"
                            + "无法生成差异复核提案。请从「站点档案」抽屉的测绘记录清单"
                            + "中选择本次档案下的一条测绘记录；旧记录可重新发起一次整站测绘。",
                    BizErrorCode.NOT_FOUND);
        }
        List<Map<String, Object>> baselineRows = jdbc.queryForList(
                "SELECT id, page_writes FROM tp_site_survey_run"
                        + " WHERE profile_id = :profileId AND id < :runId"
                        + " AND status NOT IN ('running', 'cancelled')"
                        + " AND finished_at IS NOT NULL"
                        + " ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource("runId", runId).addValue("profileId", profileId));
        Map<String, Integer> currentTally =
                readTally(String.valueOf(current.get(0).get("page_writes")));
        Long baselineRunId = null;
        Map<String, Integer> baselineTally = new TreeMap<>();
        if (!baselineRows.isEmpty()) {
            baselineRunId = ((Number) baselineRows.get(0).get("id")).longValue();
            baselineTally = readTally(String.valueOf(baselineRows.get(0).get("page_writes")));
        }
        List<String> newPages = new ArrayList<>();
        List<String> vanishedPages = new ArrayList<>();
        List<SiteSurveyDiffPageDelta> changedPages = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : currentTally.entrySet()) {
            Integer before = baselineTally.get(entry.getKey());
            if (before == null) {
                newPages.add(entry.getKey());
            } else if (before.intValue() != entry.getValue().intValue()) {
                changedPages.add(new SiteSurveyDiffPageDelta(
                        entry.getKey(), before, entry.getValue()));
            }
        }
        for (String key : baselineTally.keySet()) {
            if (!currentTally.containsKey(key)) {
                vanishedPages.add(key);
            }
        }
        java.util.Collections.sort(newPages);
        java.util.Collections.sort(vanishedPages);
        return new SiteSurveyDiffView(runId, baselineRunId, baselineRows.isEmpty(),
                List.copyOf(newPages), List.copyOf(vanishedPages),
                List.copyOf(changedPages));
    }

    /** page_writes JSONB -> folded page key -> accumulated ledger writes. */
    private Map<String, Integer> readTally(String json) {
        Map<String, Integer> tally = new TreeMap<>();
        if (json == null || json.isBlank()) {
            return tally;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            root.fields().forEachRemaining(field ->
                    tally.put(field.getKey(), field.getValue().asInt(0)));
        } catch (Exception malformed) {
            throw new ServiceException(
                    "该测绘记录的逐页明细无法解析，差异复核暂不可用。"
                            + "请到「站点档案」重新发起一次整站测绘生成新明细。",
                    BizErrorCode.INTERNAL_ERROR);
        }
        return tally;
    }
}
