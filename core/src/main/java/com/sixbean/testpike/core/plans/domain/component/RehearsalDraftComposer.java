package com.sixbean.testpike.core.plans.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.plans.api.dto.RehearsalDraftView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles one regression-rehearsal plan draft, read-only .
 * <p>The project segment is carried by the caller (the analysis door re-checks
 * visibility before this runs), so nothing here is steerable by the model:
 * exactly one source is named — a regression suite (explicit case list copied
 * from the saved set) or an existing plan template (scope rule and entry/exit
 * keys copied verbatim, never re-defaulted) — and the row must belong to the
 * segment or the refusal names the id.
 * <p>Producing this view writes nothing: the conversation turns the payload
 * into a PLAN_WRITE draft card and the plan row only lands after a human
 * confirms the card, through the same plan-create chain the wizard uses.
 */
@Component
public class RehearsalDraftComposer {

    /** Exit-criteria defaults mirroring the plan-create contract (0.95 / no open defects). */
    private static final Map<String, Object> DEFAULT_EXIT =
            Map.of("minPassRate", 0.95, "noOpenDefects", true);
    private static final Map<String, Object> DEFAULT_ENTRY = Map.of("requireSmokePass", true);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RehearsalDraftComposer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
 * Build the draft from exactly one source of the current project segment.
 *
 * @param projectId the server-carried segment
 * @param suiteId regression suite id, or null
 * @param planId existing plan template id, or null
 */
    public RehearsalDraftView compose(long projectId, Long suiteId, Long planId) {
        if (suiteId != null && planId != null) {
            throw new ServiceException("回归演练草案一次只接受一个来源：suiteId（回归集）或 planId"
                    + "（既有计划）二选一，当前两个都指定了，请只保留其一（格式：suiteId 或 planId 只传一个）");
        }
        if (suiteId == null && planId == null) {
            throw new ServiceException("装配回归演练草案需要指定来源：suiteId=回归集编号，"
                    + "或 planId=既有计划编号（两者其一，可在「/suites」与「/plans」页确认编号）");
        }
        return suiteId != null ? fromSuite(projectId, suiteId) : fromPlan(projectId, planId);
    }

    private RehearsalDraftView fromSuite(long projectId, long suiteId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name, case_ids_json::text AS cases, env_ids_json::text AS envs"
                        + " FROM tp_regression_suite WHERE project_id = ? AND id = ?",
                projectId, suiteId);
        if (rows.isEmpty()) {
            throw new ServiceException("回归集 " + suiteId + " 在当前项目内不存在"
                    + "（回归集列表见「/suites」页，可先核对编号）");
        }
        Map<String, Object> row = rows.get(0);
        String suiteName = String.valueOf(row.get("name"));
        List<Long> caseIds = readIdList((String) row.get("cases"));
        if (caseIds.isEmpty()) {
            throw new ServiceException("回归集「" + suiteName + "」未包含用例，无法装配演练草案"
                    + "——请先在「/suites」页为该回归集保存用例清单");
        }
        List<Long> envIds = readIdList((String) row.get("envs"));
        String envIdsNote = envIds.isEmpty()
                ? "回归集未保存执行环境：确认草案前请在计划创建参数 envIds 中补充当前项目的执行环境编号"
                : null;
        String planName = rehearsalName(suiteName);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("phases", List.of());
        scope.put("priorities", List.of());
        scope.put("tagIds", List.of());
        scope.put("explicitCaseIds", caseIds);
        Map<String, Object> payload = basePayload(planName, envIds, scope,
                DEFAULT_ENTRY, DEFAULT_EXIT);
        return new RehearsalDraftView("SUITE", suiteId, suiteName, caseIds.size(),
                planName, envIds, envIdsNote, payload,
                "演练草案已就绪：确认后按这份用例清单走计划创建链落为草稿态计划，"
                        + "发布与启动仍在 /plans 页人工操作");
    }

    private RehearsalDraftView fromPlan(long projectId, long planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name, env_ids_json::text AS envs, case_scope_json::text AS scope,"
                        + " entry_criteria_json::text AS entry, exit_criteria_json::text AS exit"
                        + " FROM tp_exec_plan WHERE project_id = ? AND id = ?",
                projectId, planId);
        if (rows.isEmpty()) {
            throw new ServiceException("计划 " + planId + " 在当前项目内不存在"
                    + "（计划列表见「/plans」页，可先核对编号）");
        }
        Map<String, Object> row = rows.get(0);
        String planSourceName = String.valueOf(row.get("name"));
        List<Long> envIds = readIdList((String) row.get("envs"));
        Map<String, Object> scope = readMap((String) row.get("scope"), Map.of());
        String envIdsNote = envIds.isEmpty()
                ? "来源计划未保存执行环境：确认草案前请在计划创建参数 envIds 中补充当前项目的执行环境编号"
                : null;
        Map<String, Object> entry = readMap((String) row.get("entry"), DEFAULT_ENTRY);
        Map<String, Object> exit = readMap((String) row.get("exit"), DEFAULT_EXIT);
        Integer caseCount = null;
        Object explicit = scope.get("explicitCaseIds");
        if (explicit instanceof List<?> list && !list.isEmpty()) {
            caseCount = list.size();
        }
        String planName = rehearsalName(planSourceName);
        Map<String, Object> payload = basePayload(planName, envIds, scope, entry, exit);
        return new RehearsalDraftView("PLAN", planId, planSourceName, caseCount,
                planName, envIds, envIdsNote, payload,
                caseCount == null
                        ? "演练草案已就绪：范围规则原样复制自计划 " + planId
                                + "，确认后走计划创建链按规则实拉用例并落为草稿态计划"
                        : "演练草案已就绪：确认后走计划创建链落为草稿态计划，"
                                + "发布与启动仍在 /plans 页人工操作");
    }

    private Map<String, Object> basePayload(String planName, List<Long> envIds,
                                            Map<String, Object> scope,
                                            Map<String, Object> entry, Map<String, Object> exit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", planName);
        payload.put("envIds", envIds);
        payload.put("caseScope", scope);
        payload.put("entryCriteria", entry);
        payload.put("exitCriteria", exit);
        payload.put("lockMode", 1);
        return payload;
    }

    /** Rehearsal name = prefix + source (length-capped) + date, kept under the 100-char plan-name ceiling. */
    private static String rehearsalName(String sourceName) {
        String trimmed = sourceName.length() > 60 ? sourceName.substring(0, 60) : sourceName;
        return "演练·" + trimmed + "·" + LocalDate.now();
    }

    private List<Long> readIdList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = objectMapper.readValue(json, List.class);
            List<Long> ids = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item instanceof Number number) {
                    ids.add(number.longValue());
                }
            }
            return ids;
        } catch (Exception e) {
            throw new ServiceException("来源行的用例/环境清单不是合法的 ID 列表，已停止装配，请到「/suites」或「/plans」页重新保存该清单后再装配");
        }
    }

    private Map<String, Object> readMap(String json, Map<String, Object> fallback) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>(fallback);
        }
        try {
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new ServiceException("来源行的范围/准出键不是合法的 JSON 对象，已停止装配，请到「/plans」页重新保存范围/准出配置后再装配");
        }
    }
}
