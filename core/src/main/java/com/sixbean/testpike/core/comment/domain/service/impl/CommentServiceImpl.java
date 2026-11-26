package com.sixbean.testpike.core.comment.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.comment.api.dto.CommentCreateInput;
import com.sixbean.testpike.core.comment.api.dto.CommentInfo;
import com.sixbean.testpike.core.comment.api.dto.CommentUnreadItem;
import com.sixbean.testpike.core.comment.domain.component.CommentHostDict;
import com.sixbean.testpike.core.comment.domain.service.CommentService;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@Service
public class CommentServiceImpl implements CommentService {

    
    static final int MAX_CONTENT = 2000;

    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;

    public CommentServiceImpl(JdbcTemplate jdbcTemplate,
                              NamedParameterJdbcTemplate namedJdbc,
                              NotificationService notificationService,
                              AfterCommitExecutor afterCommitExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = namedJdbc;
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    public long create(long userId, CommentCreateInput input) {
        validateHost(input.hostType());
        if (input.hostId() == null || input.hostId() <= 0) {
            throw new ServiceException("宿主 ID 无效——请从「评审工作台」或「用例管理」页面内提交评论"
                    + "（参数 hostId，示例：从宿主页进入评论区的记录编号）", BizErrorCode.VALIDATION_ERROR);
        }
        String content = input.content() == null ? "" : input.content().strip();
        if (content.isEmpty()) {
            throw new ServiceException("评论内容不能为空——请先在评论输入框填写内容再提交"
                    + "（参数 content，示例：一句话说明问题即可）", BizErrorCode.VALIDATION_ERROR);
        }
        if (content.length() > MAX_CONTENT) {
            throw new ServiceException("评论内容不能超过 " + MAX_CONTENT + " 字——请精简后重新提交，或拆成多条"
                    + "（示例：单条控制在字数上限以内）", BizErrorCode.VALIDATION_ERROR);
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_comment (host_type, host_id, source, author_id, content)
                VALUES (?, ?, 1, ?, ?) RETURNING id
                """, Long.class, input.hostType(), input.hostId(), userId, content);
        long commentId = id == null ? -1L : id;
        List<Long> mentionIds = resolveMentionTargets(userId, input.mentions());
        if (!mentionIds.isEmpty()) {
            afterCommitExecutor.execute(() ->
                    mentionIds.forEach(target -> notifyMention(target, userId, input, commentId, content)));
        }
        return commentId;
    }

    @Override
    public List<CommentInfo> list(String hostType, long hostId) {
        validateHost(hostType);
        return jdbcTemplate.query("""
                SELECT c.id, c.host_type, c.host_id, c.source, c.author_id, u.username,
                       c.content, c.ref_key,
                       to_char(c.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_comment c
                LEFT JOIN tp_user u ON u.id = c.author_id
                WHERE c.host_type = ? AND c.host_id = ?
                ORDER BY c.id
                """, (rs, n) -> new CommentInfo(rs.getLong("id"), rs.getString("host_type"),
                rs.getLong("host_id"), rs.getInt("source"),
                rs.getObject("author_id", Long.class), rs.getString("username"),
                rs.getString("content"), rs.getString("ref_key"), rs.getString("created_at")),
                hostType, hostId);
    }

    @Override
    public List<CommentUnreadItem> unreadCounts(long userId, String hostType, List<Long> hostIds) {
        validateHost(hostType);
        if (hostIds == null || hostIds.isEmpty()) {
            return List.of();
        }
        return namedJdbc.query("""
                SELECT c.host_id, COUNT(*) AS unread
                FROM tp_comment c
                LEFT JOIN tp_comment_read r
                       ON r.user_id = :userId AND r.host_type = c.host_type AND r.host_id = c.host_id
                WHERE c.host_type = :hostType AND c.host_id IN (:hostIds)
                  AND c.id > COALESCE(r.last_read_comment_id, 0)
                GROUP BY c.host_id
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("hostType", hostType)
                        .addValue("hostIds", hostIds),
                (rs, n) -> new CommentUnreadItem(hostType, rs.getLong("host_id"), rs.getInt("unread")));
    }

    @Override
    public void markRead(long userId, String hostType, long hostId) {
        validateHost(hostType);
        jdbcTemplate.update("""
                INSERT INTO tp_comment_read (user_id, host_type, host_id, last_read_comment_id)
                SELECT ?, ?, ?, COALESCE(MAX(c.id), 0) FROM tp_comment c
                WHERE c.host_type = ? AND c.host_id = ?
                ON CONFLICT (user_id, host_type, host_id)
                DO UPDATE SET last_read_comment_id = GREATEST(tp_comment_read.last_read_comment_id,
                        EXCLUDED.last_read_comment_id), updated_at = now()
                """, userId, hostType, hostId, hostType, hostId);
    }

    private void validateHost(String hostType) {
        if (!CommentHostDict.isKnown(hostType)) {
            throw new ServiceException("评论宿主类型无效（支持：评审工作台/用例/批次报告/缺陷）"
                    + "——请从对应业务页面（如「评审工作台」「用例管理」）进入评论，勿直接拼接口参数"
                    + "（参数 hostType，示例：review）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private List<Long> resolveMentionTargets(long authorId, List<Long> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>(mentions);
        distinct.remove(authorId);
        if (distinct.isEmpty()) {
            return List.of();
        }
        List<Long> existing = namedJdbc.queryForList(
                "SELECT id FROM tp_user WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", distinct), Long.class);
        return existing.stream().filter(distinct::contains).toList();
    }

    
    private void notifyMention(long targetUserId, long authorId, CommentCreateInput input,
                               long commentId, String content) {
        try {
            String authorName = jdbcTemplate.query(
                    "SELECT username FROM tp_user WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : "有人", authorId);
            notificationService.create(new NotificationCreateInput(targetUserId, null, "MENTION",
                    "「" + CommentHostDict.label(input.hostType()) + "」中提到了你",
                    authorName + " 在" + CommentHostDict.label(input.hostType())
                            + " #" + input.hostId() + " 的评论中提到了你：" + abbreviate(content),
                    "comment:" + commentId + ":mention:" + targetUserId));
        } catch (RuntimeException e) {
            log.warn("MENTION 通知失败（评论 #{} → 用户 {}）不阻断主链：{}", commentId, targetUserId, e.getMessage());
        }
    }

    
    private String abbreviate(String content) {
        String firstLine = content.lines().findFirst().orElse(content);
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "…";
    }
}
