package com.sixbean.testpike.core.notification.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefItem;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefToggle;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventFlags;
import com.sixbean.testpike.core.notification.domain.service.NotificationPrefService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class NotificationPrefServiceImpl implements NotificationPrefService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public NotificationPrefServiceImpl(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    @Override
    public List<NotificationPrefItem> listPrefs(long userId) {
        Map<String, Object[]> rows = jdbcTemplate.query(
                "SELECT event_type, enabled, to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') "
                        + "FROM tp_notification_pref WHERE user_id = ?",
                rs -> {
                    Map<String, Object[]> m = new HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString(1), new Object[]{rs.getInt(2), rs.getString(3)});
                    }
                    return m;
                }, userId);
        List<NotificationPrefItem> items = new ArrayList<>();
        for (Map.Entry<String, NotificationEventFlags> e : NotificationEventDict.all().entrySet()) {
            NotificationEventFlags flags = e.getValue();
            Object[] row = rows.get(e.getKey());
            boolean enabled = row != null
                    ? ((Integer) row[0]) == 1
                    : flags.defaultEnabled();
            items.add(new NotificationPrefItem(e.getKey(), enabled, flags.forced(),
                    !flags.defaultEnabled(), row == null ? null : (String) row[1]));
        }
        return items;
    }

    @Override
    @Transactional
    public void updatePrefs(long userId, List<NotificationPrefToggle> toggles) {
        if (toggles == null || toggles.isEmpty()) {
            throw new ServiceException("偏好批量保存不能为空列表——请至少保留一项开关再保存（入口：「通知中心」的接收偏好设置）", BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> seen = new HashSet<>();
        Map<String, Object> changes = new LinkedHashMap<>();
        for (NotificationPrefToggle toggle : toggles) {
            String eventType = toggle == null ? null : toggle.eventType();
            if (eventType == null || eventType.isBlank() || !NotificationEventDict.isKnown(eventType)) {
                throw new ServiceException("未知通知事件类型: " + eventType + "；请从「通知中心」接收偏好列出的事件清单中选择", BizErrorCode.VALIDATION_ERROR);
            }
            if (!seen.add(eventType)) {
                throw new ServiceException("同一批偏好不能重复包含事件: " + eventType + "；请删除重复项后重新保存（事件开关位于「通知中心」接收偏好，每个事件只保存一次）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!toggle.enabled() && NotificationEventDict.isForced(eventType)) {
                throw new ServiceException("安全通知不可退订: " + eventType + "；该类事件请保持开启，无需修改（事件清单见「通知中心」接收偏好）", BizErrorCode.VALIDATION_ERROR);
            }
            changes.put(eventType, toggle.enabled());
        }
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO tp_notification_pref (user_id, event_type, enabled, updated_at)
                    VALUES (?, ?, ?, now())
                    ON CONFLICT (user_id, event_type)
                    DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = now()
                    """, userId, e.getKey(), (Boolean) e.getValue() ? 1 : 0);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("prefs", changes);
        auditLogService.record(new AuditLogInput(userId, null, "notification",
                String.valueOf(userId), "update", null, after));
    }

    @Override
    public boolean shouldGenerate(long userId, String eventType) {
        if (NotificationEventDict.isForced(eventType)) {
            return true;
        }
        if (!NotificationEventDict.isKnown(eventType)) {
            return true;
        }
        Integer enabled = jdbcTemplate.query(
                "SELECT enabled FROM tp_notification_pref WHERE user_id = ? AND event_type = ?",
                rs -> rs.next() ? rs.getInt(1) : null, userId, eventType);
        if (enabled != null) {
            return enabled == 1;
        }
        return NotificationEventDict.isDefaultEnabled(eventType);
    }
}
