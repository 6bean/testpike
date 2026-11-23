package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class AppAutoPreflightGate {

    private static final Logger log = LoggerFactory.getLogger(AppAutoPreflightGate.class);

    private final AppAutoDispatchSupport dispatchSupport;
    private final AppautoConfigReader configReader;
    private final CredentialService credentialService;
    private final JdbcTemplate jdbcTemplate;

    public AppAutoPreflightGate(AppAutoDispatchSupport dispatchSupport,
                                AppautoConfigReader configReader,
                                CredentialService credentialService,
                                JdbcTemplate jdbcTemplate) {
        this.dispatchSupport = dispatchSupport;
        this.configReader = configReader;
        this.credentialService = credentialService;
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public String explainFailure(long projectId, String actionJson) {
        Map<String, Object> envelope = dispatchSupport.parseEnvelopeTolerant(actionJson);
        if (envelope == null
                || !AppAutoDispatchSupport.ENVELOPE_KIND.equals(envelope.get("kind"))) {
            return null;
        }
        String runtime = dispatchSupport.explainNoCapableExecutorForEnvelope(actionJson);
        if (runtime != null) {
            return runtime;
        }
        String device = explainDeviceState(dispatchSupport.leaseDeviceSerial(actionJson));
        if (device != null) {
            return device;
        }
        String archives = explainAppArchives(projectId, envelopeSteps(envelope));
        if (archives != null) {
            return archives;
        }
        return explainCredentials(projectId, envelopeSteps(envelope));
    }


    
    private String explainDeviceState(String serial) {
        if (serial == null || serial.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT health_state,
                       EXTRACT(EPOCH FROM (now() - last_seen_at))     AS seen_age_seconds,
                       EXTRACT(EPOCH FROM (now() - last_overheat_at)) AS overheat_age_seconds
                FROM tp_appauto_device WHERE serial = ?
                """, serial);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if ("QUARANTINED".equals(String.valueOf(row.get("health_state")))) {
            return "设备「" + serial + "」当前处于隔离状态（连续失联自动隔离，需管理员在"
                    + "「APP 自动化 → 设备墙」人工解禁后才会重新接活）——任务派发前秒拒，"
                    + "不烧执行器会话";
        }
        Long seenAge = asEpochSeconds(row.get("seen_age_seconds"));
        int seenWindow = configReader.deviceSeenWindowSeconds();
        if (seenAge == null || seenAge > seenWindow) {
            return "设备「" + serial + "」探活中断——最近一次探活上报"
                    + (seenAge == null ? "缺失" : "已是 " + seenAge + " 秒前")
                    + "，超过允许空窗 " + seenWindow + " 秒（K8 键 appauto.device-seen-window-seconds"
                    + " 可调）——判离线，派发前秒拒不烧执行器会话；请检查设备连接与执行器在线状态";
        }
        Long overheatAge = asEpochSeconds(row.get("overheat_age_seconds"));
        if (overheatAge != null && overheatAge < configReader.deviceHotWindowMinutes() * 60L) {
            return "设备「" + serial + "」刚触发过热保护（末次越阈 " + overheatAge / 60
                    + " 分钟前，冷却窗 " + configReader.deviceHotWindowMinutes()
                    + " 分钟内暂缓派发，过期自愈）——注：电量/存储健康度当前版本未采集，"
                    + "本闸以温度越阈为准；请待设备降温后重跑";
        }
        return null;
    }

    private Long asEpochSeconds(Object epochValue) {
        return epochValue == null ? null : Long.valueOf(Math.round(((Number) epochValue).doubleValue()));
    }


    
    private String explainAppArchives(long projectId, List<Map<String, Object>> steps) {
        for (Map<String, Object> step : steps) {
            String action = String.valueOf(step.getOrDefault("action", ""));
            if ("app.install".equals(action)) {
                String problem = explainInstallArchive(projectId, pkgOf(step));
                if (problem != null) {
                    return problem;
                }
            } else if ("app.verifyVersion".equals(action)) {
                String pkg = pkgOf(step);
                if (pkg != null && !pkg.isBlank() && !archiveExists(projectId, pkg)) {
                    return "步骤要核对包「" + pkg + "」的版本，但本项目未建档该应用包——"
                            + "请先到「APP 自动化 → 被测应用包」建档后重试";
                }
            }
        }
        return null;
    }

    private String explainInstallArchive(long projectId, String pkg) {
        if (pkg == null || pkg.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT apk_artifact_key FROM tp_appauto_app
                WHERE project_id = ? AND package_name = ?
                ORDER BY created_at DESC LIMIT 1
                """, projectId, pkg);
        if (rows.isEmpty()) {
            return "步骤要安装应用「" + pkg + "」，但本项目未建档该应用包——"
                    + "请先到「APP 自动化 → 被测应用包」建档并上传安装包工件后重试"
                    + "（派发前秒拒，不烧执行器会话）";
        }
        Object artifact = rows.get(0).get("apk_artifact_key");
        if (artifact == null || String.valueOf(artifact).isBlank()) {
            return "应用「" + pkg + "」已建档但安装包工件未上传——安装步骤跑起只会空下载，"
                    + "请到「APP 自动化 → 被测应用包」为该档案上传 APK 工件后重试";
        }
        return null;
    }

    private boolean archiveExists(long projectId, String pkg) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_app WHERE project_id = ? AND package_name = ?",
                Integer.class, projectId, pkg);
        return count != null && count > 0;
    }

    
    private String pkgOf(Map<String, Object> step) {
        Object pkg = step.get("pkg");
        if (pkg == null && step.get("target") instanceof Map<?, ?> target) {
            pkg = target.get("pkg");
        }
        return pkg == null ? null : String.valueOf(pkg).trim();
    }


    
    private String explainCredentials(long projectId, List<Map<String, Object>> steps) {
        Set<String> keys = CredPlaceholderInjector.extractCredReferenceKeys(steps);
        for (String key : keys) {
            try {
                credentialService.resolveSecret(projectId, key);
            } catch (ServiceException e) {
                log.debug("健康闸凭据预检拒（键={}）: {}", key, e.getMessage());
                return e.getMessage();
            }
        }
        return null;
    }

    private List<Map<String, Object>> envelopeSteps(Map<String, Object> envelope) {
        Object steps = envelope.get("steps");
        if (!(steps instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> castStep(item))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castStep(Object item) {
        return (Map<String, Object>) item;
    }
}
