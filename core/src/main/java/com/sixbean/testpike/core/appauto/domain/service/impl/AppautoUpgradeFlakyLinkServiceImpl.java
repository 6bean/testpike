package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeFlakyLinkView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeFlakyLinkService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class AppautoUpgradeFlakyLinkServiceImpl implements AppautoUpgradeFlakyLinkService {

    
    private static final double RATE_SCALE = 10000.0;

    private final AppautoConfigReader configReader;
    private final JdbcTemplate jdbcTemplate;

    public AppautoUpgradeFlakyLinkServiceImpl(AppautoConfigReader configReader,
                                              JdbcTemplate jdbcTemplate) {
        this.configReader = configReader;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AppautoUpgradeFlakyLinkView> correlate() {
        int windowHours = configReader.upgradeFlakyWindowHours();
        double dropLinePct = configReader.upgradeFlakyDropPct();
        List<AppautoUpgradeFlakyLinkView> links = new ArrayList<>();
        for (Map.Entry<String, UpgradeStepRow> entry : latestSuccessSteps().entrySet()) {
            UpgradeStepRow step = entry.getValue();
            RateWindowView before = rateWindow(entry.getKey(), step.upgradedAt().minusHours(windowHours),
                    step.upgradedAt());
            RateWindowView after = rateWindow(entry.getKey(), step.upgradedAt(),
                    step.upgradedAt().plusHours(windowHours).isAfter(LocalDateTime.now())
                            ? LocalDateTime.now()
                            : step.upgradedAt().plusHours(windowHours));
            if (before.samples() == 0 || after.samples() == 0) {
                continue;
            }
            double dropPoints = (before.passRate() - after.passRate()) * 100.0;
            if (dropPoints < dropLinePct) {
                continue;
            }
            links.add(new AppautoUpgradeFlakyLinkView(
                    Long.parseLong(entry.getKey()), step.targetVersion(), step.upgradedAt(),
                    before.samples(), before.passRate(), after.samples(), after.passRate(),
                    round(dropPoints),
                    "疑似升级引入：节点 " + entry.getKey() + " 升级到 " + step.targetVersion()
                            + " 后 " + windowHours + " 小时内通过率由 "
                            + percent(before.passRate()) + "% 跌至 " + percent(after.passRate())
                            + "%（-" + (long) dropPoints + " 个百分点）——建议回滚或先看失败归因分布"));
        }
        return List.copyOf(links);
    }

    
    private Map<String, UpgradeStepRow> latestSuccessSteps() {
        Map<String, UpgradeStepRow> latest = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT node_id, target_version, updated_at
                FROM tp_upgrade_plan_step
                WHERE status = 'SUCCESS'
                ORDER BY updated_at ASC
                """, rs -> {
            latest.put(rs.getString(1), new UpgradeStepRow(
                    rs.getString(2), rs.getTimestamp(3).toLocalDateTime()));
        });
        return latest;
    }

    
    private RateWindowView rateWindow(String nodeId, LocalDateTime from, LocalDateTime to) {
        return jdbcTemplate.query("""
                SELECT count(*) FILTER (WHERE status = 3) AS pass, count(*) AS total
                FROM tp_exec_task
                WHERE task_type = 6 AND status IN (3, 4, 5)
                  AND executor_id = ?
                  AND COALESCE(finished_at, updated_at) >= ?
                  AND COALESCE(finished_at, updated_at) < ?
                """, rs -> {
            rs.next();
            int total = rs.getInt(2);
            return new RateWindowView(total, total == 0 ? 0 : round(rs.getInt(1) / (double) total));
        }, Long.parseLong(nodeId), Timestamp.valueOf(from), Timestamp.valueOf(to));
    }

    private static double round(double value) {
        return Math.round(value * RATE_SCALE) / RATE_SCALE;
    }

    private static String percent(double rate) {
        return String.valueOf(Math.round(rate * 1000) / 10.0);
    }
}
