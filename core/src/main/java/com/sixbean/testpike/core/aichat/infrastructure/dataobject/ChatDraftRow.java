package com.sixbean.testpike.core.aichat.infrastructure.dataobject;

import com.sixbean.testpike.core.aichat.api.dto.ChatDraftInfo;

/**
 * tp_ai_chat_draft row object (draft-card read projection). Mutable carrier
 * for BeanPropertyRowMapper reflection only; exits to {@link #toInfo()}.
 * {@code messageId} keeps the fold keyed to its own message row.
 */
public class ChatDraftRow {

    private Long id;
    private Long messageId;
    private String objectType;
    private String summary;
    private String payloadJson;
    private Integer status;
    private Long targetId;
    private Integer baseVersion;
    private Integer appliedVersion;
    private Boolean rolledBack;

    public ChatDraftInfo toInfo() {
        return new ChatDraftInfo(id, objectType, summary, payloadJson,
                status == null ? 0 : status, targetId, baseVersion, appliedVersion,
                Boolean.TRUE.equals(rolledBack));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public Integer getAppliedVersion() {
        return appliedVersion;
    }

    public void setAppliedVersion(Integer appliedVersion) {
        this.appliedVersion = appliedVersion;
    }

    public Boolean getRolledBack() {
        return rolledBack;
    }

    public void setRolledBack(Boolean rolledBack) {
        this.rolledBack = rolledBack;
    }
}
