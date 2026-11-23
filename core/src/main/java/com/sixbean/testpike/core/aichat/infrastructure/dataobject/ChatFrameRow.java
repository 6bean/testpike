package com.sixbean.testpike.core.aichat.infrastructure.dataobject;

import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;

/**
 * tp_ai_chat_frame row object . Mutable carrier for
 * BeanPropertyRowMapper reflection only; exits to {@link #toInfo}.
 */
public class ChatFrameRow {

    private Long id;
    private Long sessionId;
    private Long messageId;
    private Long seq;
    private String eventType;
    private String payload;
    private String metaJson;

    public ChatFrame toInfo() {
        return new ChatFrame(id, sessionId, messageId, seq,
                ChatFrameType.fromWireName(eventType), payload, metaJson);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getMetaJson() {
        return metaJson;
    }

    public void setMetaJson(String metaJson) {
        this.metaJson = metaJson;
    }
}
