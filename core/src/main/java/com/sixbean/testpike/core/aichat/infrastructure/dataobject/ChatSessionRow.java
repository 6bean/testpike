package com.sixbean.testpike.core.aichat.infrastructure.dataobject;

import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;

/**
 * tp_ai_chat_session row object . Mutable carrier only because
 * BeanPropertyRowMapper needs a no-arg constructor plus setters; it never
 * leaves the infrastructure layer — {@link #toInfo} is the only exit.
 */
public class ChatSessionRow {

    private Long id;
    private Long projectId;
    private Long userId;
    private String title;
    private Integer source;
    private String contextJson;
    private Integer status;
    private Long lastSeq;
    private Long totalTokens;

    public ChatSessionInfo toInfo() {
        return new ChatSessionInfo(id, projectId, userId, title, source, contextJson,
                status, lastSeq, totalTokens);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getSource() {
        return source;
    }

    public void setSource(Integer source) {
        this.source = source;
    }

    public String getContextJson() {
        return contextJson;
    }

    public void setContextJson(String contextJson) {
        this.contextJson = contextJson;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(Long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public Long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens) {
        this.totalTokens = totalTokens;
    }
}
