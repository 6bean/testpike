package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;


@Component
public class DevicePerfIngestor {

    private static final Logger log = LoggerFactory.getLogger(DevicePerfIngestor.class);
    private static final DateTimeFormatter DAY_WINDOW = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;
    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoConfigReader appautoConfigReader;

    public DevicePerfIngestor(NotificationService notificationService,
                              AdminAlertRecipients adminAlertRecipients,
                              NamedParameterJdbcTemplate jdbc,
                              AppautoConfigReader appautoConfigReader) {
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
        this.jdbc = jdbc;
        this.appautoConfigReader = appautoConfigReader;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ingest(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int tempMax = appautoConfigReader.deviceTempMaxC();
        int hotWindow = appautoConfigReader.deviceHotWindowMinutes();
        int cpuFullPct = appautoConfigReader.deviceCpuFullPct();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            noteCrashEvents(map);
            String serial = text(map.get("serial"));
            if (serial.isBlank()) {
                continue;
            }
            Double last = num(map.get("lastTempC"));
            Double peak = num(map.get("maxTempC"));
            double breach = Math.max(last == null ? Double.NEGATIVE_INFINITY : last,
                    peak == null ? Double.NEGATIVE_INFINITY : peak);
            if (Double.isInfinite(breach) || breach < tempMax) {
                continue;
            }
            Double maxCpu = num(map.get("maxCpuPct"));
            Long taskId = id(map.get("taskId"));
            if (taskId != null && maxCpu != null && maxCpu >= cpuFullPct) {
                String throttleNote = "设备热节流嫌疑：采样温度峰值 " + display(peak)
                        + "℃ 越阈 " + tempMax + "℃ 且 CPU 峰值 " + display(maxCpu)
                        + "% 满载同现，跑测结果存在假阳可能，建议设备降温后重跑确认。";
                appendTaskNote(taskId, "热节流嫌疑", throttleNote);
            }
            jdbc.update("UPDATE tp_appauto_device SET last_overheat_at = now(), updated_at = now() "
                            + "WHERE serial = :serial",
                    new MapSqlParameterSource("serial", serial));
            String title = "设备过热，已暂停派发";
            String content = "设备 " + serial + " 移动端温度越阈：末帧 " + display(last)
                    + "℃、采样峰值 " + display(peak) + "℃（阈值 " + tempMax + "℃）。"
                    + "矩阵扇出派发将在最近 " + hotWindow + " 分钟内自动跳过该设备，"
                    + "降温后自动恢复，无需人工复位；若持续过热请检查散热与环境。";
            String dedup = "DEVICE_OVERHEAT:" + serial + ":" + LocalDate.now().format(DAY_WINDOW);
            for (Long adminId : adminAlertRecipients.adminUserIds()) {
                try {
                    notificationService.create(new NotificationCreateInput(
                            adminId, null, "DEVICE_OVERHEAT", title, content,
                            dedup + ":" + adminId));
                } catch (Exception e) {
                    log.warn("DEVICE_OVERHEAT 告警发送失败（接收人 id={}）: {}",
                            adminId, e.getMessage());
                }
            }
        }
    }

    private static String display(Double value) {
        if (value == null) {
            return "未上报";
        }
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    
    private static Long id(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    
    private void noteCrashEvents(Map<?, ?> map) {
        Long taskId = id(map.get("taskId"));
        if (taskId == null || !(map.get("crashes") instanceof List<?> events) || events.isEmpty()) {
            return;
        }
        StringBuilder note = new StringBuilder("跑测期间设备崩溃 ")
                .append(events.size()).append(" 次：");
        for (Object event : events) {
            if (event instanceof Map<?, ?> ev) {
                note.append('[').append(text(ev.get("kind"))).append("] ")
                        .append(text(ev.get("detail"))).append('；');
            }
        }
        note.append("失败归因请优先核对崩溃现场（三联图日志尾）。");
        String text = note.toString();
        appendTaskNote(taskId, text, text);
    }

    
    private void appendTaskNote(long taskId, String marker, String note) {
        List<String> current = jdbc.queryForList(
                "SELECT error_detail FROM tp_exec_task WHERE id = :id",
                new MapSqlParameterSource("id", taskId), String.class);
        if (current.isEmpty()) {
            return;
        }
        String detail = current.get(0);
        if (detail != null && detail.contains(marker)) {
            return;
        }
        String merged = detail == null || detail.isBlank()
                ? note : detail + "；" + note;
        if (merged.length() > 1000) {
            merged = merged.substring(0, 1000);
        }
        jdbc.update("UPDATE tp_exec_task SET error_detail = :detail, updated_at = now() "
                        + "WHERE id = :id",
                new MapSqlParameterSource("detail", merged).addValue("id", taskId));
    }

    private static Double num(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? null : Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
