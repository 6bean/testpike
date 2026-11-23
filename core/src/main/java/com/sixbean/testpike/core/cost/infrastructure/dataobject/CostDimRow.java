package com.sixbean.testpike.core.cost.infrastructure.dataobject;

import java.math.BigDecimal;


public class CostDimRow {

    private String value;
    private BigDecimal amount;
    private long calls;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public long getCalls() {
        return calls;
    }

    public void setCalls(long calls) {
        this.calls = calls;
    }
}
