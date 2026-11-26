package com.sixbean.testpike.core.cost.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.CaliberQuery;
import com.sixbean.testpike.common.report.CaliberService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.core.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.core.cost.api.dto.BudgetInput;
import com.sixbean.testpike.core.cost.api.dto.BudgetView;
import com.sixbean.testpike.core.cost.api.dto.CostDimItem;
import com.sixbean.testpike.core.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.core.cost.api.dto.CostWaterView;
import com.sixbean.testpike.core.cost.domain.service.CostService;
import com.sixbean.testpike.core.cost.infrastructure.dataobject.CostBudgetRow;
import com.sixbean.testpike.core.cost.infrastructure.dataobject.CostDimRow;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;


@Service
public class CostServiceImpl implements CostService {

    
    static final String KEY_BUDGET_DEFAULT_CENTS = "ai.cost.budget-default-cents";
    static final String KEY_ALERT_PCT = "ai.cost.alert-pct";
    static final String KEY_BREAK_ENABLED = "ai.cost.break-enabled";

 
    static final long BUDGET_CENTS_MAX = 100_000_000_000L;

 
    static final int OVERRIDE_HOURS_MAX = 72;

    
    static final int OVERRIDE_REASON_MAX = 500;

 
    static long toCents(BigDecimal yuan) {
        return yuan == null ? 0L
                : yuan.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private static final RowMapper<CostDimRow> DIM_ROW =
            new BeanPropertyRowMapper<>(CostDimRow.class);
    private static final RowMapper<CostBudgetRow> BUDGET_ROW =
            new BeanPropertyRowMapper<>(CostBudgetRow.class);

    private final NamedParameterJdbcTemplate named;
    private final SystemConfigReader configReader;
    private final ProjectService projectService;

    public CostServiceImpl(NamedParameterJdbcTemplate named,
                           SystemConfigReader configReader,
                           ProjectService projectService) {
        this.named = named;
        this.configReader = configReader;
        this.projectService = projectService;
    }

    @Override
    public CostOverviewView overview(String month, Long projectId) {
        Map<String, Object> params = costParams(month, projectId);
        List<CostDimRow> rows = queryDim("token-cost-month", params);
        CostDimItem total = new CostDimItem(CostOverviewView.TOTAL_LABEL,
                rows.isEmpty() ? BigDecimal.ZERO : rows.get(0).getAmount(),
                rows.isEmpty() ? 0 : rows.get(0).getCalls());
        return new CostOverviewView(month, projectId, total,
                queryItems("token-cost-project", params),
                queryItems("token-cost-model", params),
                queryItems("token-cost-caller", params),
                queryItems("token-cost-day", params));
    }

    @Override
    public CostWaterView waterLevel(long projectId) {
        String currentMonth = named.getJdbcTemplate()
                .queryForObject("SELECT to_char(now(), 'YYYY-MM')", String.class);
        List<CostDimRow> rows = queryDim("token-cost-month", costParams(currentMonth, projectId));
        long costCents = rows.isEmpty() ? 0L : toCents(rows.get(0).getAmount());
        CostBudgetRow row = findRow(projectId);
        long budgetCents = row != null ? row.getBudgetCents()
                : configReader.getLong(KEY_BUDGET_DEFAULT_CENTS, 0L);
        int alertPct = row != null ? row.getAlertPct() : configReader.getInt(KEY_ALERT_PCT, 80);
        Boolean overrideActive = named.queryForObject(
                "SELECT COALESCE(:until::timestamp IS NOT NULL AND :until::timestamp > now(), false)",
                new MapSqlParameterSource("until",
                        row == null ? null : row.getBreakOverrideUntil()), Boolean.class);
        return new CostWaterView(currentMonth, costCents, budgetCents, alertPct,
                configReader.getBoolean(KEY_BREAK_ENABLED, true), Boolean.TRUE.equals(overrideActive));
    }

    @Override
    public BudgetView getBudget(long projectId) {
        assertProjectExists(projectId);
        CostBudgetRow row = findRow(projectId);
        return new BudgetView(projectId, effectiveBudgetCents(row), effectiveAlertPct(row),
                row != null);
    }

    @Override
    public BudgetView putBudget(Long operatorId, long projectId, BudgetInput input) {
        assertProjectExists(projectId);
        validate(input);
        named.update("""
                INSERT INTO tp_cost_budget (project_id, budget_cents, alert_pct, created_by)
                VALUES (:projectId, :budgetCents, :alertPct, :operatorId)
                ON CONFLICT (project_id)
                DO UPDATE SET budget_cents = EXCLUDED.budget_cents,
                              alert_pct = EXCLUDED.alert_pct,
                              created_by = EXCLUDED.created_by,
                              updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("budgetCents", input.budgetCents())
                .addValue("alertPct", input.alertPct())
                .addValue("operatorId", operatorId));
        return new BudgetView(projectId, input.budgetCents(), input.alertPct(), true);
    }

    @Override
    @Audit(objectType = "cost-budget", action = "break-override", objectId = "#projectId", after = "#result")
    public BreakOverrideView breakOverride(Long operatorId, long projectId, BreakOverrideInput input) {
        assertProjectExists(projectId);
        validateOverride(input);
        CostBudgetRow row = findRow(projectId);
        AuditLogContext.setBefore(Map.of(
                "prevOverrideUntil", row == null || row.getBreakOverrideUntil() == null
                        ? "" : row.getBreakOverrideUntil().toString(),
                "prevOverrideReason", row == null || row.getBreakOverrideReason() == null
                        ? "" : row.getBreakOverrideReason()));
        if (row == null) {
            named.update("""
                    INSERT INTO tp_cost_budget
                        (project_id, budget_cents, alert_pct, break_override_until,
                         break_override_reason, created_by)
                    VALUES (:projectId, :budgetCents, :alertPct,
                            now() + make_interval(hours => :hours), :reason, :operatorId)
                    ON CONFLICT (project_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("budgetCents", effectiveBudgetCents(row))
                    .addValue("alertPct", effectiveAlertPct(row))
                    .addValue("hours", input.hours())
                    .addValue("reason", input.reason())
                    .addValue("operatorId", operatorId));
        } else {
            named.update("""
                    UPDATE tp_cost_budget
                       SET break_override_until = now() + make_interval(hours => :hours),
                           break_override_reason = :reason,
                           created_by = :operatorId,
                           updated_at = now()
                     WHERE project_id = :projectId
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("hours", input.hours())
                    .addValue("reason", input.reason())
                    .addValue("operatorId", operatorId));
        }
        LocalDateTime until = named.queryForObject(
                "SELECT break_override_until FROM tp_cost_budget WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), LocalDateTime.class);
        return new BreakOverrideView(projectId, input.reason(), input.hours(), until);
    }

    @Override
    public int clearExpiredOverrides() {
        return named.update("""
                UPDATE tp_cost_budget
                   SET break_override_until = NULL, break_override_reason = NULL, updated_at = now()
                 WHERE break_override_until IS NOT NULL AND break_override_until <= now()
                """, new MapSqlParameterSource());
    }

 
    private static void validateOverride(BreakOverrideInput input) {
        if (input == null || input.reason() == null || input.reason().isBlank()) {
            throw new ServiceException("恢复理由必填（审批式解除留痕，≤" + OVERRIDE_REASON_MAX + " 字），请在「成本中心」的「熔断临时恢复（管理员）」弹窗填写 reason 后提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.reason().length() > OVERRIDE_REASON_MAX) {
            throw new ServiceException("恢复理由超长: " + input.reason().length()
                    + " 字（上限 " + OVERRIDE_REASON_MAX + " 字），请在「成本中心」的「熔断临时恢复（管理员）」弹窗精简 reason 后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.hours() == null || input.hours() < 1 || input.hours() > OVERRIDE_HOURS_MAX) {
            throw new ServiceException("恢复时限非法: " + input.hours()
                    + " 小时（合法域 1~" + OVERRIDE_HOURS_MAX + " 小时），请在「成本中心」的「熔断临时恢复（管理员）」弹窗把 hours 改回合法域内", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private long effectiveBudgetCents(CostBudgetRow row) {
        return row != null ? row.getBudgetCents()
                : configReader.getLong(KEY_BUDGET_DEFAULT_CENTS, 0L);
    }

    private int effectiveAlertPct(CostBudgetRow row) {
        return row != null ? row.getAlertPct() : configReader.getInt(KEY_ALERT_PCT, 80);
    }

 
    private static Map<String, Object> costParams(String month, Long projectId) {
        boolean wellFormed = month != null && month.matches("\\d{4}-\\d{2}");
        if (wellFormed) {
            try {
                YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                wellFormed = false;
            }
        }
        if (!wellFormed) {
            throw new ServiceException(
                    "月份格式非法: " + month + "（应为自然月 yyyy-MM，如 2026-09），可在「成本中心」页顶部的月份选择器重新选定",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("month", month);
        params.put("projectId", projectId);
        params.put("projectIds", null);       // cost dashboard stays non-program scope
        return params;
    }

    private static void validate(BudgetInput input) {
        if (input == null || input.budgetCents() == null || input.alertPct() == null) {
            throw new ServiceException(
                    "预算参数不完整: budgetCents（分，0=不限）与 alertPct（水位 1~100）均必填，请在「成本中心」的「覆写项目预算」弹窗把两栏补齐",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.budgetCents() < 0 || input.budgetCents() > BUDGET_CENTS_MAX) {
            throw new ServiceException(
                    "预算非法: " + input.budgetCents() + " 分（合法域 0~" + BUDGET_CENTS_MAX
                            + "，0=不限额），请在「成本中心」的「覆写项目预算」弹窗把 budgetCents 改到合法域（月度预算（元）×100）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.alertPct() < 1 || input.alertPct() > 100) {
            throw new ServiceException(
                    "预警水位非法: " + input.alertPct() + "（合法域 1~100 百分比），请在「成本中心」的「覆写项目预算」弹窗把 alertPct 改到 1~100",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void assertProjectExists(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new ServiceException("项目不存在: " + projectId + "，请在顶栏切换到有权限的项目，或在「项目集管理」页新建项目后重试", BizErrorCode.NOT_FOUND);
        }
    }

    private CostBudgetRow findRow(long projectId) {
        List<CostBudgetRow> rows = named.query(
                "SELECT * FROM tp_cost_budget WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), BUDGET_ROW);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<CostDimItem> queryItems(String code, Map<String, Object> params) {
        return queryDim(code, params).stream()
                .map(r -> new CostDimItem(r.getValue(), r.getAmount(), r.getCalls()))
                .toList();
    }

    
    private List<CostDimRow> queryDim(String code, Map<String, Object> params) {
        CaliberQuery query = CaliberService.resolve(code, params);
        return named.query(query.sql(), new MapSqlParameterSource(query.params()), DIM_ROW);
    }
}
