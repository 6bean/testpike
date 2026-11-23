package com.sixbean.testpike.core.aichat.infrastructure.dataobject;

import com.sixbean.testpike.core.aichat.api.dto.ChatShareInfo;

/**
 * tp_ai_chat_share row object . Mutable carrier only because
 * BeanPropertyRowMapper needs a no-arg constructor plus setters; it never
 * leaves the infrastructure layer — {@link #toInfo} is the only exit.
 */
public class ChatShareRow {

    private Long id;
    private String shareToken;
    private Long sessionId;
    private Long ownerUserId;
    private String title;
    private String snapshotMarkdown;
    private Integer turnCount;
    private java.time.LocalDateTime expiresAt;
    private Integer revoked;
    private java.time.LocalDateTime createdAt;

    /** Owner-side view: carries the token (the owner may copy the link). */
    public ChatShareInfo toInfo() {
        return new ChatShareInfo(id, sessionId, shareToken, title, turnCount,
                expiresAt, revoked, createdAt);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSnapshotMarkdown() {
        return snapshotMarkdown;
    }

    public void setSnapshotMarkdown(String snapshotMarkdown) {
        this.snapshotMarkdown = snapshotMarkdown;
    }

    public Integer getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(Integer turnCount) {
        this.turnCount = turnCount;
    }

    public java.time.LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(java.time.LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getRevoked() {
        return revoked;
    }

    public void setRevoked(Integer revoked) {
        this.revoked = revoked;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
