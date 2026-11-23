package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "llm.search")
public class KnowledgeSearchProperties {

    
    private int rrfK = 60;

    
    private int initialTopKProject = 20;

    
    private int initialTopKGlobal = 10;

    
    private double reweightExcellent = 1.2;

    
    private double reweightSpec = 1.1;

    
    private double reweightFeedback = 0.8;

    
    private double langBonus = 0.05;

    
    private String fusionMode = "rrf";

    
    private double globalWeight = 0.3;

    
    private Integer caseGenTopK;
    
    private Double caseGenThreshold;
    
    private Double similarityThreshold;

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public int getInitialTopKProject() {
        return initialTopKProject;
    }

    public void setInitialTopKProject(int initialTopKProject) {
        this.initialTopKProject = initialTopKProject;
    }

    public int getInitialTopKGlobal() {
        return initialTopKGlobal;
    }

    public void setInitialTopKGlobal(int initialTopKGlobal) {
        this.initialTopKGlobal = initialTopKGlobal;
    }

    public double getReweightExcellent() {
        return reweightExcellent;
    }

    public void setReweightExcellent(double reweightExcellent) {
        this.reweightExcellent = reweightExcellent;
    }

    public double getReweightSpec() {
        return reweightSpec;
    }

    public void setReweightSpec(double reweightSpec) {
        this.reweightSpec = reweightSpec;
    }

    public double getReweightFeedback() {
        return reweightFeedback;
    }

    public void setReweightFeedback(double reweightFeedback) {
        this.reweightFeedback = reweightFeedback;
    }

    public double getLangBonus() {
        return langBonus;
    }

    public void setLangBonus(double langBonus) {
        this.langBonus = langBonus;
    }

    public String getFusionMode() {
        return fusionMode;
    }

    public void setFusionMode(String fusionMode) {
        this.fusionMode = fusionMode;
    }

    public double getGlobalWeight() {
        return globalWeight;
    }

    public void setGlobalWeight(double globalWeight) {
        this.globalWeight = globalWeight;
    }

    public Integer getCaseGenTopK() {
        return caseGenTopK;
    }

    public void setCaseGenTopK(Integer caseGenTopK) {
        this.caseGenTopK = caseGenTopK;
    }

    public Double getCaseGenThreshold() {
        return caseGenThreshold;
    }

    public void setCaseGenThreshold(Double caseGenThreshold) {
        this.caseGenThreshold = caseGenThreshold;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(Double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
}
