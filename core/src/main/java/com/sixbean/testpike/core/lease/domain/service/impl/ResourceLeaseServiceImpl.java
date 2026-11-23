package com.sixbean.testpike.core.lease.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * INSERT ... ON CONFLICT (kind,resource_ref) WHERE state='active'
 */
@Service
public class ResourceLeaseServiceImpl implements ResourceLeaseService {

    private static final Logger log = LoggerFactory.getLogger(ResourceLeaseServiceImpl.class);

 
    static final String TTL_KEY = "lease.lease-ttl-seconds";

 
    static final String DEVICE_TTL_KEY = "appauto.device-lease-ttl-seconds";

 
    static final String EXPLORE_TTL_KEY = "appauto.lease-ttl-explore";

 
    static final String DRAIN_TTL_KEY = "executor.drain-lease-ttl-seconds";

 
    public static final String KIND_EXECUTOR = "executor";

    
    static final String KIND_DEVICE = "device";

    
    static final String KIND_EXPLORE = "explore";

    
    static final int DEFAULT_TTL_SECONDS = 1800;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCacheHolder configCacheHolder;

    public ResourceLeaseServiceImpl(JdbcTemplate jdbcTemplate, ConfigCacheHolder configCacheHolder) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCacheHolder = configCacheHolder;
    }

    @Override
    @Transactional
    public void acquire(String kind, String ref, Long projectId, Long envId, Long batchId, String holderId) {
        int rows = jdbcTemplate.update("""
                INSERT INTO tp_resource_lease
                    (kind, resource_ref, project_id, env_id, batch_id, holder_executor_id,
                     state, acquired_at, expires_at, renew_count)
                VALUES (?, ?, ?, ?, ?, ?, 'active', now(), now() + make_interval(secs => ?), 0)
                ON CONFLICT (kind, resource_ref) WHERE state = 'active'
                DO UPDATE SET holder_executor_id = EXCLUDED.holder_executor_id,
                              project_id = EXCLUDED.project_id,
                              env_id = EXCLUDED.env_id,
                              batch_id = EXCLUDED.batch_id,
                              acquired_at = now(),
                              expires_at = EXCLUDED.expires_at,
                              renew_count = 0,
                              released_at = NULL
                WHERE tp_resource_lease.expires_at < now()
                """, kind, ref, projectId, envId, batchId, holderId, ttlSeconds(kind));
        if (rows == 0) {
            String holder = activeHolder(kind, ref);
            throw new ServiceException(
                    "资源正被「" + (holder == null ? "其他任务" : holder) + "」持有，未归还前无法获取",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
    }

    @Override
    public boolean renew(String kind, String ref, String holderId) {
        int rows = jdbcTemplate.update("""
                UPDATE tp_resource_lease
                SET expires_at = now() + make_interval(secs => ?), renew_count = renew_count + 1
                WHERE kind = ? AND resource_ref = ? AND state = 'active' AND holder_executor_id = ?
                """, ttlSeconds(kind), kind, ref, holderId);
        return rows > 0;
    }

    @Override
    public void release(String kind, String ref, String holderId) {
        jdbcTemplate.update("""
                UPDATE tp_resource_lease SET state = 'released', released_at = now()
                WHERE kind = ? AND resource_ref = ? AND state = 'active' AND holder_executor_id = ?
                """, kind, ref, holderId);
    }

    @Override
    public List<String> sweepExpired(String kind) {
        List<String> refs = jdbcTemplate.queryForList("""
                SELECT resource_ref FROM tp_resource_lease
                WHERE kind = ? AND state = 'active' AND expires_at < now()
                """, String.class, kind);
        if (refs.isEmpty()) {
            return refs;
        }
        int swept = jdbcTemplate.update("""
                UPDATE tp_resource_lease SET state = 'expired', released_at = now()
                WHERE kind = ? AND state = 'active' AND expires_at < now()
                """, kind);
        log.info("租约过期回收 kind={} 共 {} 条", kind, swept);
        return refs;
    }

    
    @Override
    public String activeHolder(String kind, String ref) {
        List<String> holders = jdbcTemplate.queryForList("""
                SELECT holder_executor_id FROM tp_resource_lease
                WHERE kind = ? AND resource_ref = ? AND state = 'active'
                  AND expires_at >= now()
                """, String.class, kind, ref);
        return holders.isEmpty() ? null : holders.get(0);
    }

    
    int ttlSeconds(String kind) {
        if (KIND_EXPLORE.equals(kind)) {
            int exploreTtl = parsePositive(configCacheHolder.get(EXPLORE_TTL_KEY));
            if (exploreTtl > 0) {
                return exploreTtl;
            }
            int deviceTtl = parsePositive(configCacheHolder.get(DEVICE_TTL_KEY));
            if (deviceTtl > 0) {
                return deviceTtl;
            }
        } else if (KIND_DEVICE.equals(kind)) {
            int deviceTtl = parsePositive(configCacheHolder.get(DEVICE_TTL_KEY));
            if (deviceTtl > 0) {
                return deviceTtl;
            }
        } else if (KIND_EXECUTOR.equals(kind)) {
            int drainTtl = parsePositive(configCacheHolder.get(DRAIN_TTL_KEY));
            if (drainTtl > 0) {
                return drainTtl;
            }
        }
        int globalTtl = parsePositive(configCacheHolder.get(TTL_KEY));
        return globalTtl > 0 ? globalTtl : DEFAULT_TTL_SECONDS;
    }

    
    private int parsePositive(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
