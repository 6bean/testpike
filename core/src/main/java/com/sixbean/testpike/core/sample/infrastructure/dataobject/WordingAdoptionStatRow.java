package com.sixbean.testpike.core.sample.infrastructure.dataobject;

import com.sixbean.testpike.core.sample.api.dto.WordingAdoptionStat;


public class WordingAdoptionStatRow {

    private long projectId;
    private String projectName;
    private long wordingRejects;
    private long adoptedDiffs;

    
    public WordingAdoptionStat toInfo() {
        return new WordingAdoptionStat(projectId, projectName, wordingRejects, adoptedDiffs);
    }

    public long getProjectId() {
        return projectId;
    }

    public void setProjectId(long projectId) {
        this.projectId = projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public long getWordingRejects() {
        return wordingRejects;
    }

    public void setWordingRejects(long wordingRejects) {
        this.wordingRejects = wordingRejects;
    }

    public long getAdoptedDiffs() {
        return adoptedDiffs;
    }

    public void setAdoptedDiffs(long adoptedDiffs) {
        this.adoptedDiffs = adoptedDiffs;
    }
}
