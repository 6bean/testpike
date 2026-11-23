package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseRunRecorder;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoDeviceLabelFilter;
import com.sixbean.testpike.core.appauto.domain.component.AppautoPlatformWhitelist;
import com.sixbean.testpike.core.appauto.domain.component.RemoteAdbConnectivityProbe;
import com.sixbean.testpike.core.appauto.infrastructure.dataobject.AppautoDevice;
import com.sixbean.testpike.core.appauto.infrastructure.dataobject.AppautoLeaseRun;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class AppautoDeviceServiceImpl implements AppautoDeviceService {

 
    static final Set<String> DEVICE_TYPES = Set.of("EMULATOR", "ADB", "USB", "POS", "PDA");

 
    static final String KEY_QUARANTINE_FAILURES = "appauto.device-quarantine-failures";

    static final int DEFAULT_QUARANTINE_FAILURES = 3;

    private static final String STATE_QUARANTINED = "QUARANTINED";

    private static final Logger log = LoggerFactory.getLogger(AppautoDeviceServiceImpl.class);

    private static final String SELECT_COLUMNS = """
            id, project_id, name, serial, device_type, adb_address, model, os_version,
            resolution, dpi, labels::text AS labels_json, health_state,
            consecutive_failures, last_seen_at, last_overheat_at, created_at, updated_at,
            platform
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final AppautoLeaseRunRecorder leaseRunRecorder;
    private final AuditLogService auditLogService;
    private final AppautoPlatformWhitelist platformWhitelist;
    private final AppautoConfigReader configReader;
    private final RemoteAdbConnectivityProbe connectivityProbe;
    private final AppautoDeviceLabelFilter labelFilter;

    public AppautoDeviceServiceImpl(NamedParameterJdbcTemplate jdbc, ConfigService configService,
                                    ObjectMapper objectMapper, AppautoLeaseRunRecorder leaseRunRecorder,
                                    AuditLogService auditLogService,
                                    AppautoPlatformWhitelist platformWhitelist,
                                    AppautoConfigReader configReader,
                                    RemoteAdbConnectivityProbe connectivityProbe,
                                    AppautoDeviceLabelFilter labelFilter) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.leaseRunRecorder = leaseRunRecorder;
        this.auditLogService = auditLogService;
        this.platformWhitelist = platformWhitelist;
        this.configReader = configReader;
        this.connectivityProbe = connectivityProbe;
        this.labelFilter = labelFilter;
    }

    @Override
    @Audit(objectType = "appauto_device", action = "create", objectId = "#result")
    public long register(Long projectId, AppautoDeviceInput input) {
        validate(input);
        MapSqlParameterSource params = writeParams(projectId, input);
        List<Long> inserted = jdbc.queryForList("INSERT INTO tp_appauto_device "
                        + "(project_id, name, serial, device_type, adb_address, model, os_version, "
                        + "resolution, dpi, labels, platform) "
                        + "VALUES (:pid, :name, :serial, :type, :adb, :model, :os, :res, :dpi, "
                        + "CAST(:labels AS jsonb), :platform) "
                        + "ON CONFLICT (serial) DO NOTHING RETURNING id",
                params, Long.class);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        Long existing = jdbc.queryForObject("SELECT id FROM tp_appauto_device "
                        + "WHERE serial = :serial",
                params, Long.class);
        return existing == null ? 0L : existing;
    }

    @Override
    @Audit(objectType = "appauto_device", action = "update", objectId = "#id")
    public void update(long id, AppautoDeviceInput input) {
        AppautoDevice device = findOr404(id);
        validate(input);
        try {
            jdbc.update("UPDATE tp_appauto_device SET name = :name, serial = :serial, "
                    + "device_type = :type, adb_address = :adb, model = :model, os_version = :os, "
                    + "resolution = :res, dpi = :dpi, labels = CAST(:labels AS jsonb), "
                    + "platform = :platform, updated_at = now() WHERE id = :id",
                    writeParams(device.getProjectId(), input).addValue("id", id));
        } catch (DuplicateKeyException e) {
            throw new ServiceException("序列号「" + input.serial().trim() + "」"
                    + serialOwnerSuffix(input.serial().trim(), device.getProjectId(), id)
                    + "——请在「设备墙」的「注册设备」或「编辑设备」表单改用其它序列号",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    public AppautoDeviceInfo get(long id) {
        AppautoDevice device = findOr404(id);
        AppautoDeviceInfo info = toInfo(device);
        if (info.projectId() == null) {
            try {
                auditLogService.record(new AuditLogInput(OperatorContext.currentOperatorId(), null,
                        "appauto_device", String.valueOf(id), "view", null,
                        Map.of("serial", info.serial(), "name", info.name())));
            } catch (Exception e) {
                log.warn("共享位读审计失败（不阻断读）: id={} err={}", id, e.getMessage());
            }
        }
        return info;
    }

    @Override
    public List<AppautoDeviceInfo> list(long projectId, String label) {
        MapSqlParameterSource params = new MapSqlParameterSource("pid", projectId);
        String where = "(project_id = :pid OR project_id IS NULL)";
        if (label != null && !label.isBlank()) {
            where += labelFilter.containmentPredicate();
            params.addValue(labelFilter.paramName(), labelFilter.containsValue(label));
        }
        return findRows(where, params).stream().map(this::toInfo).toList();
    }

    @Override
    public void recordProbeResult(long id, boolean success) {
        AppautoDevice device = findOr404(id);
        if (STATE_QUARANTINED.equals(device.getHealthState())) {
            return;
        }
        if (success) {
            jdbc.update("UPDATE tp_appauto_device SET health_state = 'GREEN', "
                    + "consecutive_failures = 0, last_seen_at = now(), updated_at = now() "
                    + "WHERE id = :id", new MapSqlParameterSource("id", id));
            return;
        }
        int failures = device.getConsecutiveFailures() + 1;
        jdbc.update("UPDATE tp_appauto_device SET health_state = :state, "
                + "consecutive_failures = :f, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("state", healthAfterFailures(failures))
                        .addValue("f", failures).addValue("id", id));
    }

    @Override
    public int probeRemoteDevices() {
        List<AppautoDevice> rows = jdbc.query("SELECT " + SELECT_COLUMNS
                        + " FROM tp_appauto_device WHERE device_type = 'ADB'"
                        + " AND adb_address IS NOT NULL AND health_state <> 'QUARANTINED'",
                new MapSqlParameterSource(), new BeanPropertyRowMapper<>(AppautoDevice.class));
        int timeoutMs = configReader.deviceProbeTimeoutMs();
        for (AppautoDevice device : rows) {
            recordProbeResult(device.getId(),
                    connectivityProbe.reachable(device.getAdbAddress(), timeoutMs));
        }
        return rows.size();
    }

    @Override
    @Audit(objectType = "appauto_device", action = "unquarantine", objectId = "#id")
    public void unquarantine(long id) {
        findOr404(id);
        jdbc.update("UPDATE tp_appauto_device SET health_state = 'UNKNOWN', "
                + "consecutive_failures = 0, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    @Audit(objectType = "appauto_device", action = "delete", objectId = "#id")
    @Transactional
    public void delete(long id) {
        AppautoDevice device = findOr404(id);
        String holder = leaseRunRecorder.activeHolderAnyTier(device.getSerial());
        if (holder != null) {
            throw new ServiceException("设备「" + device.getName() + "」正被执行器「" + holder
                    + "」占用中——请等占用任务结束归还租约后，再到「设备墙」对该档案执行删除", BizErrorCode.STATE_MACHINE_REJECT);
        }
        jdbc.update("DELETE FROM tp_appauto_lease_run WHERE device_id = :id",
                new MapSqlParameterSource("id", id));
        jdbc.update("DELETE FROM tp_appauto_device WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    public void acquireLease(long deviceId, Long batchId, String holderExecutorId) {
        acquireLease(deviceId, null, batchId, holderExecutorId);
    }

    @Override
    @Audit(objectType = "appauto_device", action = "lease-acquire", objectId = "#deviceId")
    public void acquireLease(long deviceId, Long borrowerProjectId, Long batchId, String holderExecutorId) {
        AppautoDevice device = findOr404(deviceId);
        Long flowProject = borrowerProjectId != null ? borrowerProjectId : device.getProjectId();
        if (flowProject == null) {
                    throw new ServiceException("共享位设备占用需指明归属项目（哪个项目在用这台机）"
                            + "——请从该项目的「设备墙」发起占用，或在「执行批次」派发时携带归属项目",
                    BizErrorCode.VALIDATION_ERROR);
        }
        leaseRunRecorder.acquire(deviceId, flowProject, device.getSerial(),
                batchId, holderExecutorId);
    }

    @Override
    @Audit(objectType = "appauto_device", action = "lease-release", objectId = "#deviceId")
    public void releaseLease(long deviceId, String holderExecutorId, String reason) {
        AppautoDevice device = findOr404(deviceId);
        leaseRunRecorder.release(deviceId, device.getSerial(), holderExecutorId, reason);
    }

    @Override
    @Audit(objectType = "appauto_device", action = "lease-renew", objectId = "#deviceId")
    public boolean renewLease(long deviceId, String holderExecutorId) {
        AppautoDevice device = findOr404(deviceId);
        return leaseRunRecorder.renew(device.getSerial(), holderExecutorId);
    }

    @Override
    public int sweepExpiredDeviceLeases() {
        return leaseRunRecorder.sweepExpired();
    }

    @Override
    public List<AppautoLeaseRunView> listLeaseRuns(long deviceId) {
        findOr404(deviceId);
        return jdbc.query("SELECT id, device_id, project_id, batch_id, holder_executor_id, state,"
                        + " acquired_at, expires_at, released_at, release_reason"
                        + " FROM tp_appauto_lease_run WHERE device_id = :deviceId"
                        + " ORDER BY id DESC",
                new MapSqlParameterSource("deviceId", deviceId),
                new BeanPropertyRowMapper<>(AppautoLeaseRun.class))
                .stream()
                .map(r -> new AppautoLeaseRunView(r.getId(), r.getDeviceId(), r.getProjectId(),
                        r.getBatchId(), r.getHolderExecutorId(), r.getState(), r.getAcquiredAt(),
                        r.getExpiresAt(), r.getReleasedAt(), r.getReleaseReason()))
                .toList();
    }


 
    String healthAfterFailures(int failures) {
        if (failures >= quarantineThreshold()) {
            return STATE_QUARANTINED;
        }
        return failures >= 2 ? "RED" : "YELLOW";
    }

    
    String serialOwnerSuffix(String serial, Long ownerProjectId, long selfId) {
        List<AppautoDevice> rows = findRows("serial = :serial AND id <> :selfId",
                new MapSqlParameterSource("serial", serial).addValue("selfId", selfId));
        if (rows.isEmpty()) {
            return "已被其他设备登记（归属查询时已变更），请先核实资产台账";
        }
        Long holder = rows.get(0).getProjectId();
        if (holder == null) {
            return "已被跨项目共享资产「" + rows.get(0).getName() + "」占用，共享位由管理员管理";
        }
        if (holder.equals(ownerProjectId)) {
            return "已被本项目其他设备登记，请先核实资产台账";
        }
        List<String> names = jdbc.queryForList("SELECT name FROM tp_project WHERE id = :pid",
                new MapSqlParameterSource("pid", holder), String.class);
        return "已被项目「" + (names.isEmpty() ? holder : names.get(0))
                + "」的设备登记；如需跨项目共用请把档案改为共享位（联系管理员）";
    }

    
    int quarantineThreshold() {
        String raw = configService.getValue(KEY_QUARANTINE_FAILURES,
                String.valueOf(DEFAULT_QUARANTINE_FAILURES));
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : DEFAULT_QUARANTINE_FAILURES;
        } catch (NumberFormatException e) {
            return DEFAULT_QUARANTINE_FAILURES;
        }
    }

    private void validate(AppautoDeviceInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("设备名称必填——请在「设备墙」的「注册设备」或「编辑设备」表单填写「设备名称」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.serial() == null || input.serial().isBlank()) {
            throw new ServiceException("设备序列号（serial）必填，是设备全局唯一登记锚"
                    + "——请在「设备墙」的「注册设备」或「编辑设备」表单填写「序列号」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.deviceType() == null || !DEVICE_TYPES.contains(normalizeType(input.deviceType()))) {
            throw new ServiceException("设备类型仅支持 EMULATOR(模拟器)/ADB(远程调试桥)/USB(直连)/POS(收银终端)/PDA(手持盘点)，收到「"
                    + input.deviceType() + "」——请在表单的「设备类型」重新选择", BizErrorCode.VALIDATION_ERROR);
        }
        if ("ADB".equals(normalizeType(input.deviceType()))
                && (input.adbAddress() == null || input.adbAddress().isBlank())) {
            throw new ServiceException("ADB 远程设备必须填写接入地址（ip:port，探活与断线重连的寻址源）"
                    + "——请在「注册设备」或「编辑设备」表单填写「ADB 接入地址」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("ADB".equals(normalizeType(input.deviceType()))
                && input.adbAddress() != null && !input.adbAddress().isBlank()
                && !RemoteAdbConnectivityProbe.isValidHostPort(input.adbAddress())) {
            throw new ServiceException("ADB 接入地址需为「主机:端口」形态（如 192.168.1.50:5555，"
                    + "端口 1~65535），收到「" + input.adbAddress()
                    + "」——请在「ADB 接入地址」改成主机:端口形态（示例 192.168.1.50:5555）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.dpi() != null && input.dpi() <= 0) {
            throw new ServiceException("屏幕 DPI 需为正数（留空=未知）——请在「屏幕 DPI」填写正整数或留空",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private MapSqlParameterSource writeParams(Long projectId, AppautoDeviceInput input) {
        return new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("name", input.name().trim())
                .addValue("serial", input.serial().trim())
                .addValue("type", normalizeType(input.deviceType()))
                .addValue("adb", trimToNull(input.adbAddress()))
                .addValue("model", trimToNull(input.model()))
                .addValue("os", trimToNull(input.osVersion()))
                .addValue("res", trimToNull(input.resolution()))
                .addValue("dpi", input.dpi())
                .addValue("labels", writeLabels(input.labels()))
                .addValue("platform", platformWhitelist.normalize(input.platform()));
    }

    private String writeLabels(List<String> labels) {
        try {
            return objectMapper.writeValueAsString(labels == null ? List.of() : labels);
        } catch (JsonProcessingException e) {
            throw new ServiceException("设备组标签序列化失败——请在「组标签」减少特殊字符后重试；持续出现请联系管理员",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private List<String> readLabels(String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(labelsJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AppautoDevice findOr404(long id) {
        List<AppautoDevice> rows = findRows("id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("设备不存在（可能已退役删除）——请刷新「设备墙」列表确认档案",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private List<AppautoDevice> findRows(String where, MapSqlParameterSource params) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_appauto_device WHERE " + where,
                params, new BeanPropertyRowMapper<>(AppautoDevice.class));
    }

    private AppautoDeviceInfo toInfo(AppautoDevice d) {
        return new AppautoDeviceInfo(d.getId(), d.getProjectId(), d.getName(), d.getSerial(),
                d.getDeviceType(), d.getAdbAddress(), d.getModel(), d.getOsVersion(),
                d.getResolution(), d.getDpi(), readLabels(d.getLabelsJson()), d.getHealthState(),
                d.getConsecutiveFailures(), d.getLastSeenAt(), d.getLastOverheatAt(),
                d.getCreatedAt(), d.getUpdatedAt(), d.getPlatform());
    }
}
