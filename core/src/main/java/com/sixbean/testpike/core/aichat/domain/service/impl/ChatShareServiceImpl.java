package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatExportView;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareInfo;
import com.sixbean.testpike.core.aichat.api.dto.ChatShareSnapshotView;
import com.sixbean.testpike.core.aichat.domain.component.ChatShareTtlWindow;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionExportService;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.ChatShareService;
import com.sixbean.testpike.core.aichat.infrastructure.dataobject.ChatShareRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dialog snapshot shares over tp_ai_chat_share .
 * <p>Creation is copy, not view: {@link ChatSessionExportService#renderMarkdown}
 * is the single source of what may leave a session (ownership re-check,
 * masking chain, system plumbing dropped, unsealed turns excluded) and its
 * output is frozen into the row together with the title. A public touch reads
 * only this row — never the live ledger — which is what makes "the link shows
 * what was shared at share time, forever unchanged" structural instead of a
 * promise.
 * <p>Lifecycle is lazy: expiry deletes the row on the next touch (no second
 * scheduler owning the same lifecycle), revocation is an owner-side state
 * flip that keeps the audit row. The door is fail-closed on every shape: the
 * token must parse as a UUID (400), and unknown/expired/revoked all answer
 * one 404 sentence so probing the public URL cannot enumerate live tokens.
 */
@Service
public class ChatShareServiceImpl implements ChatShareService {

    /** One refusal sentence for every "this link serves nothing" state. */
    private static final String LINK_UNAVAILABLE = "分享链接不存在、已过期或已被撤回";

    private static final String SELECT_COLUMNS = """
            SELECT id, share_token::text AS share_token, session_id, owner_user_id, title,
                   snapshot_markdown, turn_count, expires_at, revoked, created_at
            FROM tp_ai_chat_share
            """;

    private final NamedParameterJdbcTemplate named;
    private final ChatSessionService sessionService;
    private final ChatSessionExportService exportService;

    public ChatShareServiceImpl(NamedParameterJdbcTemplate named,
                                ChatSessionService sessionService,
                                ChatSessionExportService exportService) {
        this.named = named;
        this.sessionService = sessionService;
        this.exportService = exportService;
    }

    @Override
    @Transactional
    public ChatShareInfo create(long userId, long sessionId, int ttlHours) {
        // Owner check first: a stranger is refused by name before a single
        // turn of someone else's dialog is rendered (AF-g horizontal posture).
        sessionService.requireOwned(sessionId, userId);
        ChatExportView frozen = exportService.renderMarkdown(userId, sessionId);
        if (frozen.turnCount() == 0) {
            throw new ServiceException("这段对话还没有问答内容，聊几句后再分享",
                    BizErrorCode.VALIDATION_ERROR);
        }
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusHours(ChatShareTtlWindow.clamp(ttlHours));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("userId", userId)
                .addValue("title", frozen.title())
                .addValue("markdown", frozen.markdown())
                .addValue("turnCount", frozen.turnCount())
                .addValue("expiresAt", expiresAt);
        Long id = named.queryForObject("""
                INSERT INTO tp_ai_chat_share (session_id, owner_user_id, title,
                        snapshot_markdown, turn_count, expires_at)
                VALUES (:sessionId, :userId, :title, :markdown, :turnCount, :expiresAt)
                RETURNING id
                """, params, Long.class);
        return requireRow(id).toInfo();
    }

    @Override
    public ChatShareSnapshotView openByToken(String shareToken) {
        // No @Transactional on purpose: the self-destruct DELETE below must
        // survive the ServiceException that follows it — inside a transaction
        // the runtime exception would roll the delete straight back.
        UUID token = parseToken(shareToken);
        ChatShareRow row;
        try {
            row = named.queryForObject(SELECT_COLUMNS + " WHERE share_token = CAST(:token AS uuid)",
                    new MapSqlParameterSource("token", token.toString()),
                    new BeanPropertyRowMapper<>(ChatShareRow.class));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // Spring's queryForObject throws on no row (house precedent:
            // DocumentServiceImpl's named-404 wrap) — translate before it rides out.
            throw new ServiceException(LINK_UNAVAILABLE, BizErrorCode.NOT_FOUND);
        }
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            // Self-destruct on touch: an expired copy has no reason to keep
            // sitting on disk where a future reader could still find the row.
            named.update("DELETE FROM tp_ai_chat_share WHERE id = :id",
                    new MapSqlParameterSource("id", row.getId()));
            throw new ServiceException(LINK_UNAVAILABLE, BizErrorCode.NOT_FOUND);
        }
        if (row.getRevoked() == 1) {
            throw new ServiceException(LINK_UNAVAILABLE, BizErrorCode.NOT_FOUND);
        }
        return new ChatShareSnapshotView(row.getTitle(), row.getSnapshotMarkdown(),
                row.getTurnCount(), row.getExpiresAt());
    }

    @Override
    @Transactional
    public ChatShareInfo revoke(long userId, long shareId) {
        ChatShareRow row = requireRow(shareId);
        if (row.getOwnerUserId() != userId) {
            throw new ServiceException("该分享链接不属于当前用户，已拒绝操作",
                    BizErrorCode.FORBIDDEN);
        }
        if (row.getRevoked() == 0) {
            named.update("UPDATE tp_ai_chat_share SET revoked = 1 WHERE id = :id",
                    new MapSqlParameterSource("id", shareId));
        }
        return requireRow(shareId).toInfo();
    }

    @Override
    public List<ChatShareInfo> listForOwner(long userId, long sessionId) {
        // Ownership first, through the session door's single check — a stranger
        // listing someone else's shares is refused by name before any row reads.
        sessionService.requireOwned(sessionId, userId);
        return named.query(SELECT_COLUMNS
                        + " WHERE session_id = :sessionId AND owner_user_id = :userId"
                        + " AND expires_at > now()"
                        + " ORDER BY created_at DESC",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("userId", userId),
                new BeanPropertyRowMapper<>(ChatShareRow.class))
                .stream().map(ChatShareRow::toInfo).toList();
    }

    private ChatShareRow requireRow(long shareId) {
        try {
            return named.queryForObject(SELECT_COLUMNS + " WHERE id = :id",
                    new MapSqlParameterSource("id", shareId),
                    new BeanPropertyRowMapper<>(ChatShareRow.class));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ServiceException("分享链接不存在或已过期", BizErrorCode.NOT_FOUND);
        }
    }

    /** Shape gate before any query: a non-UUID string is client noise (400),
     *  never a database touch — keeps garbage crawls off the index. */
    private static UUID parseToken(String shareToken) {
        try {
            return UUID.fromString(shareToken == null ? "" : shareToken.trim());
        } catch (IllegalArgumentException e) {
            throw new ServiceException("分享链接格式不正确", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
