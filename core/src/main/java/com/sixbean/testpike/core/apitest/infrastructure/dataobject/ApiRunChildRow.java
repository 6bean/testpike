package com.sixbean.testpike.core.apitest.infrastructure.dataobject;

import java.util.Date;

/**
 * Child-run carrier for the collection orchestrator (tp_apitest_run filtered by
 * run_set_id; BeanPropertyRowMapper carrier — the dispatch stamp drives the
 * never-re-send guard).
 */
public class ApiRunChildRow {

    private long id;
    private long requestId;
    private int status;
    private Date dispatchedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public Date getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Date dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
