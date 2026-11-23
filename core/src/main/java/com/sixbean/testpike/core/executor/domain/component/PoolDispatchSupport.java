package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.api.dto.PoolProjectView;
import com.sixbean.testpike.core.executor.api.dto.PoolView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
public class PoolDispatchSupport {

    
    private static final int DEFAULT_OFFLINE_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCacheHolder configCacheHolder;

    public PoolDispatchSupport(JdbcTemplate jdbcTemplate, ConfigCacheHolder configCacheHolder) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCacheHolder = configCacheHolder;
    }

    
    public boolean isMemberOfPool(String groupIds, Long poolTagId) {
        if (poolTagId == null) {
            return true;
        }
        if (groupIds == null || groupIds.isBlank()) {
            return false;
        }
        String target = String.valueOf(poolTagId);
        for (String seg : groupIds.split(",")) {
            if (seg.trim().equals(target)) {
                return true;
            }
        }
        return false;
    }

    
    public Integer quotaPctOfProject(long projectId) {
        Integer pct = jdbcTemplate.query("SELECT pool_quota_pct FROM tp_project WHERE id = ?",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, projectId);
        return pct == null || pct <= 0 ? null : pct;
    }

    
    public int poolSlotCap(long poolTagId, int quotaPct) {
        long capacitySum = poolOnlineCapacitySum(poolTagId);
        if (capacitySum <= 0) {
            return 0;
        }
        return (int) Math.max(1, capacitySum * quotaPct / 100);
    }

    
    public long runningInPool(long projectId, long poolTagId) {
        Long n = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_exec_task t
                JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE t.status = 2 AND t.project_id = ? AND b.pool_tag_id = ?
                """, Long.class, projectId, poolTagId);
        return n == null ? 0 : n;
    }

    
    public long poolOnlineCapacitySum(long poolTagId) {
        Long sum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(capacity), 0) FROM tp_executor
                WHERE status = 1 AND last_heartbeat >= now() - (? || ' seconds')::interval
                  AND """ + POOL_MEMBER_MATCH + """
                
                """, Long.class, offlineSeconds(), poolMatchPattern(poolTagId));
        return sum == null ? 0 : sum;
    }

    
    public List<PoolView> listPoolViews() {
        List<Map<String, Object>> pools = jdbcTemplate.queryForList(
                "SELECT id, name, project_id FROM tp_tag_dict WHERE type = 7 ORDER BY id");
        List<PoolView> views = new ArrayList<>(pools.size());
        for (Map<String, Object> pool : pools) {
            long poolId = ((Number) pool.get("id")).longValue();
            int[] stats = memberStats(poolId);
            views.add(new PoolView(poolId, (String) pool.get("name"),
                    ((Number) pool.get("project_id")).longValue(),
                    stats[0], stats[1], stats[2],
                    taskCountByStatus(poolId, 2), taskCountByStatus(poolId, 1),
                    projectRows(poolId)));
        }
        return views;
    }

    
    private int[] memberStats(long poolTagId) {
        return jdbcTemplate.query("""
                SELECT count(*)::int,
                       count(*) FILTER (WHERE status = 1
                           AND last_heartbeat >= now() - (? || ' seconds')::interval)::int,
                       COALESCE(SUM(capacity) FILTER (WHERE status = 1
                           AND last_heartbeat >= now() - (? || ' seconds')::interval), 0)::int
                FROM tp_executor WHERE """ + POOL_MEMBER_MATCH,
                (rs, n) -> new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)},
                offlineSeconds(), offlineSeconds(), poolMatchPattern(poolTagId))
                .stream().findFirst().orElse(new int[3]);
    }

    
    private int taskCountByStatus(long poolTagId, int taskStatus) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_exec_task t
                JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE b.pool_tag_id = ? AND t.status = ? AND b.status IS DISTINCT FROM 5
                """, Integer.class, poolTagId, taskStatus);
        return n == null ? 0 : n;
    }

    
    private List<PoolProjectView> projectRows(long poolTagId) {
        return jdbcTemplate.query("""
                SELECT p.id, p.name, p.pool_quota_pct,
                       (SELECT count(*)::int FROM tp_exec_task t
                          JOIN tp_exec_batch b ON b.id = t.batch_id
                         WHERE b.pool_tag_id = ? AND t.project_id = p.id AND t.status = 2
                           AND b.status IS DISTINCT FROM 5) AS running_in_pool,
                       (SELECT count(*)::int FROM tp_exec_task t
                          JOIN tp_exec_batch b ON b.id = t.batch_id
                         WHERE b.pool_tag_id = ? AND t.project_id = p.id AND t.status = 1
                           AND b.status IS DISTINCT FROM 5) AS pending_count
                FROM tp_project p
                WHERE p.pool_quota_pct IS NOT NULL
                   OR EXISTS (SELECT 1 FROM tp_exec_task t
                                JOIN tp_exec_batch b ON b.id = t.batch_id
                               WHERE b.pool_tag_id = ? AND t.project_id = p.id
                                 AND t.status IN (1, 2) AND b.status IS DISTINCT FROM 5)
                ORDER BY p.id
                """, (rs, n) -> {
            Integer pct = (Integer) rs.getObject("pool_quota_pct");
            int slotCap = (pct == null || pct <= 0) ? -1 : poolSlotCap(poolTagId, pct);
            return new PoolProjectView(rs.getLong("id"), rs.getString("name"),
                    (pct == null || pct <= 0) ? null : pct, slotCap,
                    rs.getInt("running_in_pool"), rs.getInt("pending_count"));
        }, poolTagId, poolTagId, poolTagId);
    }

    
    private static final String POOL_MEMBER_MATCH =
            "(',' || replace(group_ids, ' ', '') || ',') LIKE ?";

    
    private static String poolMatchPattern(long poolTagId) {
        return "%," + poolTagId + ",%";
    }

    
    private int offlineSeconds() {
        try {
            String value = configCacheHolder.get("testpike.executor.offline-seconds");
            return value == null ? DEFAULT_OFFLINE_SECONDS : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return DEFAULT_OFFLINE_SECONDS;
        }
    }
}
