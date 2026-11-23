package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;


@Component
public class DeviceAutoIngestor {

    private static final Logger log = LoggerFactory.getLogger(DeviceAutoIngestor.class);

    private final AppautoDeviceService appautoDeviceService;
    private final NamedParameterJdbcTemplate jdbc;

    public DeviceAutoIngestor(AppautoDeviceService appautoDeviceService,
                              NamedParameterJdbcTemplate jdbc) {
        this.appautoDeviceService = appautoDeviceService;
        this.jdbc = jdbc;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ingest(List<?> rows, String deviceProjectScope) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Long projectId = resolveScopeProject(deviceProjectScope);
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            if (!truthy(map.get("creatable"))) {
                continue;
            }
            String serial = text(map.get("serial"));
            if (serial.isBlank()) {
                continue;
            }
            AppautoDeviceInput input = new AppautoDeviceInput(
                    fallback(text(map.get("model")), serial),
                    serial,
                    text(map.get("deviceType")),
                    blankToNull(map.get("adbAddress")),
                    blankToNull(map.get("model")),
                    null, null, null,
                    autoLabels(map),
                    blankToNull(map.get("platform")));
            try {
                long deviceId = appautoDeviceService.register(projectId, input);
                // An online heartbeat fact is also the health evidence for
                // executor-attached devices (USB/emulator): route it through the
                // single recordProbeResult source so GREEN/last_seen movement
                // stays in one place and its quarantine guard keeps
                // QUARANTINED profiles fail-closed (no second health channel).
                appautoDeviceService.recordProbeResult(deviceId, true);
            } catch (RuntimeException e) {
                log.warn("设备自动建档跳过（serial={}）: {}", serial, e.getMessage());
            }
        }
    }

    
    private Long resolveScopeProject(String scope) {
        String raw = text(scope);
        if (raw.isEmpty()) {
            return null;
        }
        long candidate;
        try {
            candidate = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        if (candidate <= 0) {
            return null;
        }
        List<Long> found = jdbc.queryForList("SELECT id FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", candidate), Long.class);
        return found.isEmpty() ? null : found.get(0);
    }

    
    private static List<String> autoLabels(Map<?, ?> map) {
        List<String> labels = new ArrayList<>();
        String hostOs = text(map.get("hostOs")).toLowerCase(Locale.ROOT);
        if (!hostOs.isBlank()) {
            labels.add(hostOs);
        }
        String platform = text(map.get("platform"));
        if (!platform.isBlank()) {
            labels.add("platform:" + platform.trim().toUpperCase(Locale.ROOT));
        }
        labels.add("source:auto");
        return labels;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String blankToNull(Object value) {
        String s = text(value);
        return s.isEmpty() ? null : s;
    }

    private static String fallback(String primary, String secondary) {
        return primary.isBlank() ? secondary : primary;
    }
}
