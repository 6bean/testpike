package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;


@Component
public class AppautoLeaseRunRecorder {

    
    public static final String KIND_DEVICE = "device";

 
    public static final String KIND_EXPLORE = "explore";

    
    static final String EXPIRED_REASON = "占用超时自动回收（持有者未续租超窗）";

    private final JdbcTemplate jdbcTemplate;
    private final ResourceLeaseService resourceLeaseService;

    public AppautoLeaseRunRecorder(JdbcTemplate jdbcTemplate, ResourceLeaseService resourceLeaseService) {
        this.jdbcTemplate = jdbcTemplate;
        this.resourceLeaseService = resourceLeaseService;
    }

    
    @Transactional
    public void acquire(long deviceId, long projectId, String serial, Long batchId, String holderId) {
        acquire(deviceId, projectId, serial, batchId, holderId, KIND_DEVICE);
    }

    
    @Transactional
    public void acquire(long deviceId, long projectId, String serial, Long batchId, String holderId,
                        String kind) {
        resourceLeaseService.acquire(kind, serial, projectId, null, batchId, holderId);
        List<LocalDateTime[]> window = jdbcTemplate.query(
                "SELECT acquired_at, expires_at FROM tp_resource_lease"
                        + " WHERE kind = ? AND resource_ref = ? AND state = 'active'"
                        + " AND holder_executor_id = ?",
                (rs, i) -> new LocalDateTime[] {
                        rs.getTimestamp("acquired_at").toLocalDateTime(),
                        rs.getTimestamp("expires_at").toLocalDateTime() },
                kind, serial, holderId);
        LocalDateTime[] times = window.isEmpty()
                ? new LocalDateTime[] { LocalDateTime.now(), LocalDateTime.now() }
                : window.get(0);
        jdbcTemplate.update("INSERT INTO tp_appauto_lease_run"
                        + " (device_id, project_id, batch_id, holder_executor_id, state,"
                        + " acquired_at, expires_at)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)",
                deviceId, projectId, batchId, holderId, times[0], times[1]);
    }

    
    @Transactional
    public void release(long deviceId, String serial, String holderId, String reason) {
        release(deviceId, serial, holderId, reason, KIND_DEVICE);
    }

    
    @Transactional
    public void release(long deviceId, String serial, String holderId, String reason, String kind) {
        resourceLeaseService.release(kind, serial, holderId);
        jdbcTemplate.update("UPDATE tp_appauto_lease_run"
                        + " SET state = 'RELEASED', released_at = now(), release_reason = ?"
                        + " WHERE device_id = ? AND holder_executor_id = ? AND state = 'ACTIVE'",
                trimReason(reason), deviceId, holderId);
    }

    
    @Transactional
    public boolean renew(String serial, String holderId) {
        return renew(serial, holderId, KIND_DEVICE);
    }

    
    @Transactional
    public boolean renew(String serial, String holderId, String kind) {
        return resourceLeaseService.renew(kind, serial, holderId);
    }

    
    public Long deviceIdBySerial(String serial) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_appauto_device WHERE serial = ?",
                Long.class, serial);
        return ids.isEmpty() ? null : ids.get(0);
    }

    
    @Transactional
    public boolean bindOnDispatch(long projectId, String serial, Long batchId, String holderId) {
        return bindOnDispatch(projectId, serial, batchId, holderId, KIND_DEVICE);
    }

 
    @Transactional
    public boolean bindOnDispatch(long projectId, String serial, Long batchId, String holderId,
                                  String kind) {
        if (resourceLeaseService.renew(kind, serial, holderId)) {
            return true;
        }
        Long deviceId = deviceIdBySerial(serial);
        if (deviceId == null) {
            return false;
        }
        acquire(deviceId, projectId, serial, batchId, holderId, kind);
        return true;
    }

    
    @Transactional
    public int sweepExpired() {
        List<String> sweptSerials = new java.util.ArrayList<>(
                resourceLeaseService.sweepExpired(KIND_DEVICE));
        sweptSerials.addAll(resourceLeaseService.sweepExpired(KIND_EXPLORE));
        for (String serial : sweptSerials) {
            jdbcTemplate.update("UPDATE tp_appauto_lease_run r"
                            + " SET state = 'EXPIRED', released_at = now(), release_reason = ?"
                            + " FROM tp_appauto_device d"
                            + " WHERE r.device_id = d.id AND d.serial = ? AND r.state = 'ACTIVE'",
                    EXPIRED_REASON, serial);
        }
        return sweptSerials.size();
    }

    
    public String activeHolder(String serial) {
        return resourceLeaseService.activeHolder(KIND_DEVICE, serial);
    }

    
    public String activeHolder(String serial, String kind) {
        return resourceLeaseService.activeHolder(kind, serial);
    }

    
    public String activeHolderAnyTier(String serial) {
        String deviceHolder = resourceLeaseService.activeHolder(KIND_DEVICE, serial);
        return deviceHolder != null
                ? deviceHolder
                : resourceLeaseService.activeHolder(KIND_EXPLORE, serial);
    }

    
    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未填写归还原因";
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }
}
