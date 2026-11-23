package com.sixbean.testpike.core.judge.infrastructure.dataobject;


public class ScorecardAggregateRow {

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

    
    public Integer avgFinalScore() {
        return scored == null || scored == 0 || avgFinal == null ? null
                : (int) Math.round(avgFinal);
    }

    
    public Double passRate() {
        return scored == null || scored == 0 || passCount == null ? null
                : Math.round(passCount * 100.0 / scored) / 100.0;
    }

    
    public Double latencyAvgMs() {
        return scored == null || scored == 0 || latencyAvg == null ? null
                : (double) Math.round(latencyAvg);
    }

    
    public Long tokensPerSample() {
        return scored == null || scored == 0 || tokensTotal == null ? null
                : tokensTotal / scored;
    }
}
