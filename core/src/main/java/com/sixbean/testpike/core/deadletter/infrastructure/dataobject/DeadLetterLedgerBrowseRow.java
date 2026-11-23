package com.sixbean.testpike.core.deadletter.infrastructure.dataobject;

import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerRow;

/**
 * Mutable row object for BeanPropertyRowMapper over tp_mq_dead_letter .
 * <p>Same two-piece pattern as the training-sample ledger: a dataobject bound from
 * JDBC columns, converted to the api-layer record via {@link #toInfo} so the DO
 * never leaves the infrastructure/domain layers (layering red line).
 */
public class DeadLetterLedgerBrowseRow {

    private long id;
    private String messageFingerprint;
    private String queueName;
    private String origExchange;
    private String origRoutingKey;
    private String payloadJson;
    private String failureClass;
    private String failureReason;
    private int replayRound;
    private String status;
    private String voidReason;
    private String operator;
    private String createdAtUtc;
    private String updatedAtUtc;

    public DeadLetterLedgerRow toInfo() {
        return new DeadLetterLedgerRow(id, messageFingerprint, queueName, origExchange,
                origRoutingKey, payloadJson, failureClass, failureReason, replayRound,
                status, voidReason, operator, createdAtUtc, updatedAtUtc);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getMessageFingerprint() {
        return messageFingerprint;
    }

    public void setMessageFingerprint(String messageFingerprint) {
        this.messageFingerprint = messageFingerprint;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getOrigExchange() {
        return origExchange;
    }

    public void setOrigExchange(String origExchange) {
        this.origExchange = origExchange;
    }

    public String getOrigRoutingKey() {
        return origRoutingKey;
    }

    public void setOrigRoutingKey(String origRoutingKey) {
        this.origRoutingKey = origRoutingKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = failureClass;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getReplayRound() {
        return replayRound;
    }

    public void setReplayRound(int replayRound) {
        this.replayRound = replayRound;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(String createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    public String getUpdatedAtUtc() {
        return updatedAtUtc;
    }

    public void setUpdatedAtUtc(String updatedAtUtc) {
        this.updatedAtUtc = updatedAtUtc;
    }
}
