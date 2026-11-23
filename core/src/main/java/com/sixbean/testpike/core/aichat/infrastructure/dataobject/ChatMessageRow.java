package com.sixbean.testpike.core.aichat.infrastructure.dataobject;

import com.sixbean.testpike.core.aichat.api.dto.ChatMessageInfo;

import java.util.List;

/**
 * tp_ai_chat_message row object . Mutable carrier for
 * BeanPropertyRowMapper reflection only; exits to {@link #toInfo}.
 */
public class ChatMessageRow {

    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private String reasoning;
    private String status;
    private Long seqFrom;
    private Long seqTo;
    private Integer promptTokens;
    private Integer completionTokens;
    private java.time.LocalDateTime createdAt;
    private Integer feedback;
    private String feedbackReason;
    /** Draft cards folded onto this message by the read path (never a column). */
    private List<ChatDraftRow> drafts;
    /** Session goal-plan tail folded onto the newest sealed assistant row by
 * the read path , never a column — the ledger row is the
 * source, this is a read projection that may legitimately be absent). */
    private com.sixbean.testpike.core.aichat.api.dto.ChatGoalPlanInfo goalPlan;
    /** Credential-screenshot marker folded by the read path ( piece
 * E): true when one of this message's frames booked credLedger, i.e.
 * the turn carried a screenshot whose original pixels are already
 * purged. Never a column — the frame ledger is the source. */
    private boolean credLedger;

    public boolean isCredLedger() {
        return credLedger;
    }

    public void setCredLedger(boolean credLedger) {
        this.credLedger = credLedger;
    }

    public ChatMessageInfo toInfo() {
        return new ChatMessageInfo(id, sessionId, role, content, reasoning, status,
                seqFrom, seqTo, promptTokens == null ? 0 : promptTokens,
                completionTokens == null ? 0 : completionTokens, createdAt,
                feedback, feedbackReason,
                drafts == null ? List.of() : drafts.stream().map(ChatDraftRow::toInfo).toList(),
                goalPlan, credLedger);
    }

    public com.sixbean.testpike.core.aichat.api.dto.ChatGoalPlanInfo getGoalPlan() {
        return goalPlan;
    }

    public void setGoalPlan(com.sixbean.testpike.core.aichat.api.dto.ChatGoalPlanInfo goalPlan) {
        this.goalPlan = goalPlan;
    }

    public List<ChatDraftRow> getDrafts() {
        return drafts;
    }

    public void setDrafts(List<ChatDraftRow> drafts) {
        this.drafts = drafts;
    }

    public Integer getFeedback() {
        return feedback;
    }

    public void setFeedback(Integer feedback) {
        this.feedback = feedback;
    }

    public String getFeedbackReason() {
        return feedbackReason;
    }

    public void setFeedbackReason(String feedbackReason) {
        this.feedbackReason = feedbackReason;
    }

    public java.time.LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.LocalDateTime createdAt) {
        this.createdAt = createdAt;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getSeqFrom() {
        return seqFrom;
    }

    public void setSeqFrom(Long seqFrom) {
        this.seqFrom = seqFrom;
    }

    public Long getSeqTo() {
        return seqTo;
    }

    public void setSeqTo(Long seqTo) {
        this.seqTo = seqTo;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }
}
