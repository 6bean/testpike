package com.sixbean.testpike.core.notification.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.api.dto.NotificationInfo;
import com.sixbean.testpike.core.notification.domain.component.FeishuWebhookChannel;
import com.sixbean.testpike.core.notification.domain.component.WebhookChannel;
import com.sixbean.testpike.core.notification.domain.service.NotificationPrefService;

import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class NotificationServiceImpl implements NotificationService {

    private static final String COLUMNS = """
            id, user_id, project_id, event_type, channel, title, content, status, read_flag,
            to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), dedup_key
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WebhookChannel webhookChannel;
    private final FeishuWebhookChannel feishuWebhookChannel;
    private final NotificationPrefService notificationPrefService;

    public NotificationServiceImpl(JdbcTemplate jdbcTemplate, WebhookChannel webhookChannel,
                                   FeishuWebhookChannel feishuWebhookChannel,
                                   NotificationPrefService notificationPrefService) {
        this.jdbcTemplate = jdbcTemplate;
        this.webhookChannel = webhookChannel;
        this.feishuWebhookChannel = feishuWebhookChannel;
        this.notificationPrefService = notificationPrefService;
    }

    @Override
    @Transactional
    public long create(NotificationCreateInput input) {
        if (input.userId() <= 0) {
            throw new ServiceException("通知接收人不能为空——userId 需为有效用户（账号可在「用户管理」核对）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.eventType() == null || input.eventType().isBlank()) {
            throw new ServiceException("事件类型不能为空——请为 eventType 从已登记的事件类型中选择（清单见「通知中心」的接收偏好设置）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.title() == null || input.title().isBlank() || input.title().length() > 200) {
            throw new ServiceException("通知标题不能为空且不超过 200 字——请补充或精简标题，格式：一句话说清发生了什么（示例「用例批量生成完成，12 条待评审」）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.content() == null || input.content().isBlank()) {
            throw new ServiceException("通知内容不能为空——请补充正文后再发送，格式：发生了什么+影响对象+下一步（示例「商品中心接口 Mock 空间命中失败率 12%，请查看仿真空间端点状态」）", BizErrorCode.VALIDATION_ERROR);
        }
        if (!notificationPrefService.shouldGenerate(input.userId(), input.eventType())) {
            return 0L;
        }
        if (input.dedupKey() != null && !input.dedupKey().isBlank()) {
            Long existing = jdbcTemplate.query(
                    "SELECT id FROM tp_notification WHERE dedup_key = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, input.dedupKey());
            if (existing != null) {
                return existing;
            }
        }
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_notification (user_id, project_id, event_type, channel, title, content, dedup_key)
                VALUES (?, ?, ?, '站内', ?, ?, ?) RETURNING id
                """, Long.class, input.userId(), input.projectId(), input.eventType(),
                input.title(), input.content(), input.dedupKey());
        webhookChannel.sendAsync(input);
        feishuWebhookChannel.sendAsync(input, id);
        return id;
    }

    @Override
    public boolean existsByDedupKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key = ?", Integer.class, dedupKey);
        return count != null && count > 0;
    }

    @Override
    public NotificationInfo get(long id) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_notification WHERE id = ?",
                this::mapRow, id).stream().findFirst().orElse(null);
    }

    @Override
    public PageResult<NotificationInfo> listByUser(long userId, Integer readFlag, int page, int size,
                                                   String eventType, String sortBy, String sortDir) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder where = new StringBuilder("WHERE user_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (readFlag != null) {
            where.append(" AND read_flag = ?");
            args.add(readFlag);
        }
        if (eventType != null && !eventType.isBlank()) {
            where.append(" AND event_type = ?");
            args.add(eventType);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification " + where, Long.class, args.toArray(new Object[0]));
        long offset = (long) (safePage - 1) * safeSize;
        Object[] queryArgs = new Object[args.size() + 2];
        for (int i = 0; i < args.size(); i++) {
            queryArgs[i] = args.get(i);
        }
        queryArgs[args.size()] = safeSize;
        queryArgs[args.size() + 1] = offset;
        List<NotificationInfo> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tp_notification " + where
                        + orderClause(sortBy, sortDir) + " LIMIT ? OFFSET ?",
                this::mapRow, queryArgs);
        return new PageResult<>(list, total == null ? 0 : total);
    }

    /** Physical columns the notification list may order by; input never reaches SQL raw. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "event_type", "event_type",
            "title", "title",
            "read_flag", "read_flag",
            "created_at", "created_at",
            "id", "id");

    /**
     * ORDER BY builder: a null/blank sort key keeps the historical id DESC
     * newest-first order; a whitelisted key orders by that column with the
     * row id as tie-breaker so paging stays deterministic under equal values.
     * An off-list key is refused fail-closed by the shared whitelist gate.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "站内信");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    @Transactional
    public void markRead(long id, long userId) {
        jdbcTemplate.update(
                "UPDATE tp_notification SET read_flag = 1 WHERE id = ? AND user_id = ?", id, userId);
    }

    @Override
    @Transactional
    public int markAllRead(long userId) {
        return jdbcTemplate.update(
                "UPDATE tp_notification SET read_flag = 1 WHERE user_id = ? AND read_flag = 0", userId);
    }

    private NotificationInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationInfo(
                rs.getLong("id"), rs.getLong("user_id"),
                (Long) rs.getObject("project_id"), rs.getString("event_type"),
                rs.getString("channel"), rs.getString("title"), rs.getString("content"),
                rs.getInt("status"), rs.getInt("read_flag"), rs.getString(10),
                rs.getString("dedup_key"));
    }
}
