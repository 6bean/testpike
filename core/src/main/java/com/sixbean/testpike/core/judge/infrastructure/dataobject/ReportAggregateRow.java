package com.sixbean.testpike.core.judge.infrastructure.dataobject;


public class ReportAggregateRow {

    private Integer scored;
    private Double avgFinal;
    private Long tokensTotal;
    private Double latencyAvg;
    private Integer passCount;

    
    public Integer getScored() {
        return scored;
    }

    public void setScored(Integer scored) {
        this.scored = scored;
    }

    
    public Double getAvgFinal() {
        return avgFinal;
    }

    public void setAvgFinal(Double avgFinal) {
        this.avgFinal = avgFinal;
    }

    
    public Long getTokensTotal() {
        return tokensTotal;
    }

    public void setTokensTotal(Long tokensTotal) {
        this.tokensTotal = tokensTotal;
    }

    
    public Double getLatencyAvg() {
        return latencyAvg;
    }

    public void setLatencyAvg(Double latencyAvg) {
        this.latencyAvg = latencyAvg;
    }

    
    public Integer getPassCount() {
        return passCount;
    }

    public void setPassCount(Integer passCount) {
        this.passCount = passCount;
    }

    
    public Double avgExact() {
        return scored == null || scored == 0 || avgFinal == null
                ? null : Math.round(avgFinal * 100.0) / 100.0;
    }
}
