package com.sixbean.testpike.core.perf.domain.service.impl;

import com.sixbean.testpike.core.perf.domain.service.PerfReportNotifyService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportService;
import com.sixbean.testpike.core.notification.domain.component.ApprovalNotifierSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class PerfReportNotifyServiceImpl implements PerfReportNotifyService {

    private static final String EVENT_TYPE = "PERF_REPORT_CARD";

    /** Automatic SLA red-light alert (GB-3); separate dedup spectrum from manual card. */
    private static final String SLA_EVENT_TYPE = "PERF_SLA_BREACH";

    private final PerfReportService perfReportService;
    private final ApprovalNotifierSupport approvalNotifierSupport;
    private final NamedParameterJdbcTemplate jdbc;

    public PerfReportNotifyServiceImpl(PerfReportService perfReportService,
                                       ApprovalNotifierSupport approvalNotifierSupport,
                                       NamedParameterJdbcTemplate jdbc) {
        this.perfReportService = perfReportService;
        this.approvalNotifierSupport = approvalNotifierSupport;
        this.jdbc = jdbc;
    }

    @Override
    public int sendCard(long projectId, long runId) {
        Map<String, Object> report = perfReportService.build(projectId, runId);
        Map<String, Object> run = asMap(report.get("run"));
        Map<String, Object> card = asMap(report.get("card"));
        Map<String, Object> verdict = asMap(report.get("verdict"));

        Set<Long> recipients = recipientsOf(projectId, run.get("operator_id"));
        boolean passed = Boolean.TRUE.equals(verdict.get("passed"));
        String title = "压测运行 #" + runId + " 结论：" + (passed ? "通过" : "未通过");
        approvalNotifierSupport.send(EVENT_TYPE, projectId, recipients, title,
                cardBody(report, card, verdict, passed, projectId),
                EVENT_TYPE + ":" + runId);
        return recipients.size();
    }

    @Override
    public int sendSlaBreachCard(long projectId, long runId) {
        // Verdict consumed from the report assembly single source (never a second
        // judgment spectrum here); cross-project/missing run 404s at the same gate.
        Map<String, Object> report = perfReportService.build(projectId, runId);
        Map<String, Object> run = asMap(report.get("run"));
        Map<String, Object> card = asMap(report.get("card"));
        Map<String, Object> verdict = asMap(report.get("verdict"));
        boolean passed = Boolean.TRUE.equals(verdict.get("passed"));
        // Green run (incl. no-SLA-gate judge skip) = honest silence, zero sends.
        if (passed) {
            return 0;
        }
        Set<Long> recipients = recipientsOf(projectId, run.get("operator_id"));
        String title = "压测运行 #" + runId + " SLA 门禁越线告警";
        // dedup prefix keeps the PERF_SLA_BREACH spectrum — sharing the manual-card
        // prefix would let an earlier automatic alert swallow the user's card.
        approvalNotifierSupport.send(SLA_EVENT_TYPE, projectId, recipients, title,
                cardBody(report, card, verdict, false, projectId),
                SLA_EVENT_TYPE + ":" + runId);
        return recipients.size();
    }

    
    private Set<Long> recipientsOf(long projectId, Object operatorId) {
        Set<Long> recipients = new LinkedHashSet<>();
        addIfValid(recipients, operatorId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT test_owner_id, created_by FROM tp_project WHERE id = :p",
                new MapSqlParameterSource("p", projectId));
        for (Map<String, Object> row : rows) {
            addIfValid(recipients, row.get("test_owner_id"));
            addIfValid(recipients, row.get("created_by"));
        }
        return recipients;
    }

    private static void addIfValid(Set<Long> recipients, Object raw) {
        if (raw instanceof Number n && n.longValue() > 0) {
            recipients.add(n.longValue());
        }
    }

    
    private String cardBody(Map<String, Object> report, Map<String, Object> card,
                            Map<String, Object> verdict, boolean passed, long projectId) {
        StringBuilder body = new StringBuilder();
        body.append(approvalNotifierSupport.projectLabel(reportProjectId(report, projectId)))
                .append("压测运行结论").append(passed ? "：通过" : "：未通过").append("。")
                .append(String.valueOf(verdict.get("conclusion"))).append("。");
        List<?> breached = verdict.get("breachedGates") instanceof List<?> list
                ? list : List.of();
        if (!breached.isEmpty()) {
            body.append("超阈项：").append(joinChinese(breached)).append("。");
        }
        body.append("关键指标：采样 ").append(intOf(card.get("sampledSeconds"))).append(" 秒，")
                .append("平均 RPS ").append(fmt(card.get("avgRps")))
                .append("，峰值 RPS ").append(fmt(card.get("maxRps")))
                .append("，峰值秒 P95 ").append(fmt(card.get("peakSecondP95Ms"))).append("ms")
                .append("，平均错误率 ").append(pct(card.get("avgErrorRate"))).append("。");
        int authCount = intOf(card.get("authRequestCount"));
        if (authCount > 0) {
            body.append("认证请求 ").append(authCount).append(" 次");
            Object authAvg = card.get("authAvgMs");
            if (authAvg != null) {
                body.append("，平均耗时 ").append(fmt(authAvg)).append("ms");
            }
            body.append("（认证耗时不入业务三线）。");
        } else {
            body.append("本次运行无认证请求。");
        }
        body.append("详情见压测报告页。");
        return body.toString();
    }

    
    private static Long reportProjectId(Map<String, Object> report, long fallback) {
        Object run = report.get("run");
        if (run instanceof Map<?, ?> map && map.get("project_id") instanceof Number n) {
            return n.longValue();
        }
        return fallback;
    }

    private static String joinChinese(List<?> items) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                joined.append("、");
            }
            joined.append(items.get(i));
        }
        return joined.toString();
    }

    private static int intOf(Object raw) {
        return raw instanceof Number n ? n.intValue() : 0;
    }

    
    private static String pct(Object raw) {
        if (!(raw instanceof Number n)) {
            return "无数据";
        }
        return String.format("%.2f", n.doubleValue() * 100) + "%";
    }

    
    private static String fmt(Object raw) {
        if (!(raw instanceof Number n)) {
            return "无数据";
        }
        double value = n.doubleValue();
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format("%.2f", value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return (Map<String, Object>) raw;
    }
}
