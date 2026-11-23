package com.sixbean.testpike.core.repair.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.repair.domain.component.RepairOutcome;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairItem;

import java.sql.Timestamp;


public class ScriptRepairRow {

    private Long id;
    private Long projectId;
    private Long caseId;
    private Long sourceTaskId;
    private Long trialTaskId;
    private Integer round;
    private String proposalSummary;
    private Integer outcome;
    private String failNote;
    private Timestamp createdAt;

    
    public ScriptRepairItem toInfo() {
        return new ScriptRepairItem(id, projectId, caseId, sourceTaskId, trialTaskId,
                round, proposalSummary, outcome, RepairOutcome.label(outcome), failNote,
                ViewFormats.formatTs(createdAt));
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

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public Long getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(Long sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public Long getTrialTaskId() {
        return trialTaskId;
    }

    public void setTrialTaskId(Long trialTaskId) {
        this.trialTaskId = trialTaskId;
    }

    public Integer getRound() {
        return round;
    }

    public void setRound(Integer round) {
        this.round = round;
    }

    public String getProposalSummary() {
        return proposalSummary;
    }

    public void setProposalSummary(String proposalSummary) {
        this.proposalSummary = proposalSummary;
    }

    public Integer getOutcome() {
        return outcome;
    }

    public void setOutcome(Integer outcome) {
        this.outcome = outcome;
    }

    public String getFailNote() {
        return failNote;
    }

    public void setFailNote(String failNote) {
        this.failNote = failNote;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
