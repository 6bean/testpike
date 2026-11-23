package com.sixbean.testpike.core.batch.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class AppAutoBatchCleanSupport {

    private static final Logger log = LoggerFactory.getLogger(AppAutoBatchCleanSupport.class);

    
    static final String EVENT_CLEAN = "APP_BATCH_CLEAN";

    private final JdbcTemplate jdbcTemplate;
    private final AppAutoDispatchSupport appAutoDispatchSupport;
    private final ExecTaskService execTaskService;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final ObjectMapper objectMapper;

    public AppAutoBatchCleanSupport(JdbcTemplate jdbcTemplate,
                                    AppAutoDispatchSupport appAutoDispatchSupport,
                                    ExecTaskService execTaskService,
                                    NotificationService notificationService,
                                    ProjectOwners projectOwners,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.execTaskService = execTaskService;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.objectMapper = objectMapper;
    }

 
    public void onBatchFinalized(long batchId) {
        try {
            cleanBatch(batchId);
        } catch (Exception e) {
            log.warn("跑批自动清机异常（批次 {} 状态零影响）: {}", batchId, e.getMessage());
            noteFailure(batchId, "清机动作执行异常：" + e.getMessage()
                    + "——批次结果不受影响；可稍后在设备墙手动清理或修复后重跑。");
        }
    }

    private void cleanBatch(long batchId) {
        Map<String, Object> batch = fetchBatch(batchId);
        if (batch == null) {
            return;
        }
        long projectId = ((Number) batch.get("project_id")).longValue();
        boolean batchFlag = ((Number) batch.get("clean_app_data")).intValue() == 1;

        List<Map<String, Object>> appTasks = jdbcTemplate.queryForList(
                "SELECT action_json FROM tp_exec_task WHERE batch_id = ? AND task_type = 6"
                        + " ORDER BY id DESC", batchId);
        if (appTasks.isEmpty()) {
            return;
        }
        String engineCode = null;
        for (Map<String, Object> t : appTasks) {
            engineCode = appAutoDispatchSupport.envelopeEngineCode(
                    String.valueOf(t.get("action_json")));
            if (engineCode != null) {
                break;
            }
        }
        if (engineCode == null) {
            return;
        }

        Set<String> pkgs;
        if (batchFlag) {
            pkgs = scriptPkgsOfBatch(batchId, false);
        } else {
            pkgs = scriptPkgsOfBatch(batchId, true);
            if (pkgs.isEmpty()) {
                return;
            }
        }
        if (pkgs.isEmpty()) {
            log.info("跑批 {} 清机旗标开但脚本未显式声明应用包，跳过", batchId);
            return;
        }

        Map<String, Object> lease = fetchLastLease(batchId);
        if (lease == null) {
            log.info("跑批 {} 无设备占用流水，清机诚实跳过", batchId);
            return;
        }
        Long deviceId = ((Number) lease.get("device_id")).longValue();
        String serial = String.valueOf(lease.get("serial"));

        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (String pkg : pkgs) {
            steps.add(Map.of("action", "app.clearData", "pkg", pkg));
        }
        for (String pkg : pkgs) {
            steps.add(Map.of("action", "app.uninstall", "pkg", pkg));
        }
        Map<String, Object> envelope =
                appAutoDispatchSupport.buildEnvelope(projectId, engineCode, null, steps);
        Map<String, Object> leaseRef = new LinkedHashMap<>();
        leaseRef.put("deviceId", deviceId);
        leaseRef.put("serial", serial);
        envelope.put("leaseRef", leaseRef);
        long taskId;
        try {
            taskId = execTaskService.createAppAutoRun(projectId,
                    toJson(envelope));
        } catch (Exception e) {
            noteFailure(batchId, "本批跑完后的自动清数据/卸载未能执行（设备 " + serial
                    + " 派发被拒：" + e.getMessage() + "）——批次结果不受影响；"
                    + "可在设备墙处理该设备后，于用例页手动补跑清理步骤。");
            return;
        }
        jdbcTemplate.update("UPDATE tp_exec_task SET result_ext = ?::jsonb WHERE id = ?",
                "{\"sourceBatchId\":" + batchId + ",\"sourceSerial\":\"" + serial + "\"}",
                taskId);
        log.info("跑批 {} 收口后自动清机任务已派发（task={}，设备 {}，包集 {}）",
                batchId, taskId, serial, pkgs);
    }

    
    private Set<String> scriptPkgsOfBatch(long batchId, boolean onlyFlaggedCases) {
        String sql = """
                SELECT s.action_json FROM tp_script s
                JOIN tp_batch_case bc ON bc.case_id = s.case_id
                JOIN tp_test_case tc ON tc.id = s.case_id
                WHERE bc.batch_id = ? AND s.action_json IS NOT NULL
                """ + (onlyFlaggedCases ? " AND tc.clean_app_data = 1" : "");
        Set<String> pkgs = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql, batchId)) {
            collectPkgs(String.valueOf(row.get("action_json")), pkgs);
        }
        return pkgs;
    }

    
    private void collectPkgs(String scriptJson, Set<String> out) {
        try {
            Map<String, Object> doc = objectMapper.readValue(scriptJson, Map.class);
            if (!(doc.get("steps") instanceof List<?> list)) {
                return;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> step)) {
                    continue;
                }
                String action = step.get("action") == null ? "" : String.valueOf(step.get("action"));
                if (action.endsWith(".install") || action.endsWith(".clearData")
                        || action.endsWith(".uninstall") || action.endsWith(".grantPermission")) {
                    addPkg(out, step.get("pkg"));
                } else if (action.endsWith(".launch") && step.get("target") instanceof Map<?, ?> t) {
                    addPkg(out, t.get("pkg"));
                }
            }
        } catch (Exception e) {
            log.warn("清机脚本包集解析失败（该脚本零贡献，禁猜包名）: {}", e.getMessage());
        }
    }

    private void addPkg(Set<String> out, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.add(String.valueOf(value).trim());
        }
    }

    private Map<String, Object> fetchBatch(long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT project_id, clean_app_data FROM tp_exec_batch WHERE id = ?", batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    
    private Map<String, Object> fetchLastLease(long batchId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT lr.device_id, d.serial FROM tp_appauto_lease_run lr"
                        + " JOIN tp_appauto_device d ON d.id = lr.device_id"
                        + " WHERE lr.batch_id = ? ORDER BY lr.id DESC LIMIT 1", batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

 /** Log a human-readable failure trace (recipients are project owners and the creator, with ProjectOwners as the single source; handle its own exceptions only as warnings and never break the chain). */
    private void noteFailure(long batchId, String content) {
        try {
            Map<String, Object> batch = fetchBatch(batchId);
            if (batch == null) {
                return;
            }
            long projectId = ((Number) batch.get("project_id")).longValue();
            for (Long userId : projectOwners.resolveWithCreator(projectId)) {
                notificationService.create(new NotificationCreateInput(userId, projectId,
                        EVENT_CLEAN, "跑批自动清机未能执行（批次 " + batchId + "）",
                        content, "app-batch-clean:" + batchId + ":" + userId));
            }
        } catch (Exception e) {
            log.warn("清机失败留痕站内信未发出（批次 {}）: {}", batchId, e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("清机信封序列化失败", e);
        }
    }
}
