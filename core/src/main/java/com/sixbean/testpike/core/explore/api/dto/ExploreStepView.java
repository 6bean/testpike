package com.sixbean.testpike.core.explore.api.dto;


public class ExploreStepView {

    private Integer seq;
    private String stepAction;
    private String stepResult;
    private String screenshotKey;
    private String domSnapshotKey;
    private String llmThinkMd;

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getStepAction() {
        return stepAction;
    }

    public void setStepAction(String stepAction) {
        this.stepAction = stepAction;
    }

    public String getStepResult() {
        return stepResult;
    }

    public void setStepResult(String stepResult) {
        this.stepResult = stepResult;
    }

    public String getScreenshotKey() {
        return screenshotKey;
    }

    public void setScreenshotKey(String screenshotKey) {
        this.screenshotKey = screenshotKey;
    }

    public String getDomSnapshotKey() {
        return domSnapshotKey;
    }

    public void setDomSnapshotKey(String domSnapshotKey) {
        this.domSnapshotKey = domSnapshotKey;
    }

    public String getLlmThinkMd() {
        return llmThinkMd;
    }

    public void setLlmThinkMd(String llmThinkMd) {
        this.llmThinkMd = llmThinkMd;
    }
}
