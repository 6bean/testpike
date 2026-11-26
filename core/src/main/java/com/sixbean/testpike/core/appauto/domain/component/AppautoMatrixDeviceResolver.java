package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * <ul>
 * </ul>
 */
@Component
public class AppautoMatrixDeviceResolver {

    
    private static final Map<String, List<String>> KIND_TO_DEVICE_TYPES = Map.of(
            "EMULATOR", List.of("EMULATOR"),
            "REAL", List.of("ADB", "USB", "POS", "PDA"),
            "SIMULATOR", List.of(),
            "DEVTOOLS_IDE", List.of());

    /**
     * Device classes that belong to one platform and to no other. POS and PDA were added to
     * tp_appauto_device.device_type as retail fleets that share the REAL device class with
     * phones, so the kind mapping alone would let a checkout run address a phone and a phone
     * run occupy a scarce terminal. A platform named here is served only by the device_type
     * values it owns; every other platform loses those machines from its pool. Phone
     * platforms (ANDROID/IOS/HARMONY_NEXT/MP) are deliberately absent: they keep the
     * two-map above minus the owned types.
     */
    private static final Map<String, List<String>> PLATFORM_OWNED_DEVICE_TYPES = Map.of(
            "POS", List.of("POS"),
            "PDA", List.of("PDA"));

    /** Union of the owned types, derived from the table above so the two can never drift. */
    private static final List<String> PLATFORM_OWNED_TYPES = PLATFORM_OWNED_DEVICE_TYPES.values()
            .stream().flatMap(List::stream).distinct().sorted().toList();

    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoConfigReader appautoConfigReader;
    private final AppautoDeviceLabelFilter labelFilter;

    public AppautoMatrixDeviceResolver(NamedParameterJdbcTemplate jdbc,
                                       AppautoConfigReader appautoConfigReader,
                                       AppautoDeviceLabelFilter labelFilter) {
        this.jdbc = jdbc;
        this.appautoConfigReader = appautoConfigReader;
        this.labelFilter = labelFilter;
    }

    
    public Map<String, Object> resolve(long projectId, String deviceKindCode, int ordinal) {
        return resolve(projectId, deviceKindCode, ordinal, null);
    }

    
    public Map<String, Object> resolve(long projectId, String deviceKindCode, int ordinal,
                                       Long reservationId) {
        return resolve(projectId, deviceKindCode, ordinal, reservationId, null);
    }

    /**
     * Platform-aware addressing (retail edge fleet split, design 5.11 B8). When platformCode
     * names a class that owns its machines, the candidate types narrow to that class; when it
     * names any other platform, the owned types leave the pool, so phone runs and checkout /
     * handheld runs can never occupy each other's machines. A null or blank platformCode is an
     * unclassified envelope: the pool stays as the device-kind mapping gives it, which keeps
     * every caller that does not carry a platform yet working exactly as before.
     */
    public Map<String, Object> resolve(long projectId, String deviceKindCode, int ordinal,
                                       Long reservationId, String platformCode) {
        return resolve(projectId, deviceKindCode, ordinal, reservationId, platformCode, null);
    }

    /**
     * Label-group addressing (fleet binding, design 5.11 P5c): a non-blank groupLabel narrows
     * the candidate pool to the tp_appauto_device rows carrying that label, so a run binds a
     * device group instead of naming machines and the fleet can grow or shrink by labelling.
     * The containment judgement is the same single source the device list filter uses
     * (AppautoDeviceLabelFilter, JSONB array contains — no second predicate). A group with no
     * reachable machine returns null, which keeps the no-lease envelope semantics: machines
     * outside the group are never borrowed as a fallback. A null or blank groupLabel is the
     * unbound surface and leaves the pool untouched.
     */
    public Map<String, Object> resolve(long projectId, String deviceKindCode, int ordinal,
                                       Long reservationId, String platformCode,
                                       String groupLabel) {
        Object[] mesh = reservationId == null ? null : lockToReservation(reservationId);
        if (reservationId != null && mesh == null) {
            return null;
        }
        List<String> types = KIND_TO_DEVICE_TYPES.get(deviceKindCode == null
                ? "" : deviceKindCode.trim().toUpperCase(java.util.Locale.ROOT));
        if (types == null || types.isEmpty()) {
            return null;
        }
        String platform = platformCode == null
                ? "" : platformCode.trim().toUpperCase(java.util.Locale.ROOT);
        if (!platform.isEmpty()) {
            List<String> owned = PLATFORM_OWNED_DEVICE_TYPES.get(platform);
            types = owned == null
                    ? types.stream().filter(t -> !PLATFORM_OWNED_TYPES.contains(t)).toList()
                    : types.stream().filter(owned::contains).toList();
            if (types.isEmpty()) {
                return null;   // this platform has no machine reachable through that device kind
            }
        }
        MapSqlParameterSource params = new MapSqlParameterSource(
                "projectId", projectId).addValue("types", types)
                .addValue("hotWindowMinutes",
                        appautoConfigReader.deviceHotWindowMinutes());
        String reservationDeviceFilter = "";
        if (mesh != null) {
            reservationDeviceFilter = " AND id = :reservationDevice\n";
            params.addValue("reservationDevice", mesh[0]);
        }
        String groupFilter = "";
        if (groupLabel != null && !groupLabel.isBlank()) {
            groupFilter = labelFilter.containmentPredicate();
            params.addValue(labelFilter.paramName(), labelFilter.containsValue(groupLabel));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, serial FROM tp_appauto_device
                WHERE (project_id = :projectId OR project_id IS NULL) AND device_type IN (:types)
                  AND health_state <> 'QUARANTINED'
                  AND (last_overheat_at IS NULL
                       OR last_overheat_at < now() - make_interval(mins => :hotWindowMinutes))
                """ + reservationDeviceFilter + groupFilter + """
                ORDER BY id
                """, params);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> picked = rows.get(Math.floorMod(ordinal, rows.size()));
        return Map.of("deviceId", picked.get("id"), "serial", picked.get("serial"));
    }

    
    private Object[] lockToReservation(Long reservationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT device_id, state, starts_at, ends_at FROM tp_appauto_reservation WHERE id = :rid",
                new MapSqlParameterSource("rid", reservationId));
        if (rows.isEmpty()) {
            throw new ServiceException(
                    "绑定的设备预约不存在或已被删除（预约号 " + reservationId
                            + "）——请先在「设备预约日历」页确认预约有效，或取消绑定后重新扇出",
                    BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> r = rows.get(0);
        if (!"ACTIVE".equals(r.get("state"))) {
            return null;
        }
        java.sql.Timestamp starts = (java.sql.Timestamp) r.get("starts_at");
        java.sql.Timestamp ends = (java.sql.Timestamp) r.get("ends_at");
        LocalDateTime now = LocalDateTime.now();
        if (starts.toLocalDateTime().isAfter(now)) {
            return null;
        }
        LocalDateTime deadline = ends.toLocalDateTime()
                .minusMinutes(appautoConfigReader.reservationOverrunGraceMinutes());
        return !now.isBefore(deadline) ? null
                : new Object[]{r.get("device_id")};
    }
}
