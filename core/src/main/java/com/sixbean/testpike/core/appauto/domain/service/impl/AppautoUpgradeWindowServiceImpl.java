package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeWindowView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoUpgradeWindowService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class AppautoUpgradeWindowServiceImpl implements AppautoUpgradeWindowService {

    
    private static final int LOOKBACK_DAYS = 7;

    private final AppautoConfigReader configReader;
    private final JdbcTemplate jdbcTemplate;

    public AppautoUpgradeWindowServiceImpl(AppautoConfigReader configReader,
                                           JdbcTemplate jdbcTemplate) {
        this.configReader = configReader;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AppautoUpgradeWindowView> recommend() {
        if (!configReader.upgradeWindowEnabled()) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("智能排窗",
                    AppautoConfigReader.KEY_UPGRADE_WINDOW), BizErrorCode.FORBIDDEN);
        }
        Integer running = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_upgrade_plan WHERE status IN ('RUNNING', 'PAUSED')
                """, Integer.class);
        if (running != null && running > 0) {
            throw new ServiceException("该窗有在跑计划——当前有 " + running
                    + " 个升级计划处于执行/暂停中，排完这批再排新窗（红线：升级窗与在跑计划冲突拒答）",
                    BizErrorCode.VALIDATION_ERROR);
        }

        Map<Integer, Integer> busyByHour = busyCurve();
        int slots = configReader.upgradeWindowHours();
        int nodes = nodeCount();
        String canaryHint = canaryHint(nodes);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);

        List<AppautoUpgradeWindowView> out = new ArrayList<>();
        boolean picked = false;
        for (int h = 1; h <= slots; h++) {
            LocalDateTime slot = now.plusHours(h);
            int busy = busyByHour.getOrDefault(slot.getHour(), 0);
            boolean recommended = !picked && busy == 0;
            picked |= recommended;
            out.add(new AppautoUpgradeWindowView(slot, busy, recommended, canaryHint));
        }
        return List.copyOf(out);
    }

    
    private Map<Integer, Integer> busyCurve() {
        Map<Integer, Integer> busy = new HashMap<>();
        jdbcTemplate.query("""
                SELECT started_at, COALESCE(finished_at, CURRENT_TIMESTAMP) AS fin
                FROM tp_exec_batch
                WHERE started_at IS NOT NULL
                  AND started_at >= CURRENT_TIMESTAMP - (? || ' days')::interval
                """, rs -> {
            LocalDateTime from = rs.getTimestamp(1).toLocalDateTime();
            LocalDateTime to = rs.getTimestamp(2).toLocalDateTime();
            LocalDateTime cursor = from.truncatedTo(ChronoUnit.HOURS);
            while (cursor.isBefore(to)) {
                busy.merge(cursor.getHour(), 1, Integer::sum);
                cursor = cursor.plusHours(1);
            }
        }, LOOKBACK_DAYS);
        return busy;
    }

    
    private int nodeCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT node_id) FROM tp_cluster_component", Integer.class);
        return count == null ? 0 : count;
    }

 
    private String canaryHint(int nodes) {
        if (nodes <= 0) {
            return "集群暂无部件台账节点——先接入执行器心跳再排升级窗";
        }
        if (nodes == 1) {
            return "仅 1 节点：无金丝雀可言，直接单台升级+升级后自查通过率";
        }
        return "建议金丝雀 1 台先升（通过率达标再分批），每批不超 "
                + Math.max(1, nodes / 3) + " 台、批间冷却按金丝雀线自动刹车";
    }
}
