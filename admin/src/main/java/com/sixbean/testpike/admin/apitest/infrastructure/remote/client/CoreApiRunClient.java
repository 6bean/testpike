package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.admin.apitest.api.dto.RunCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunView;

import java.util.List;


public interface CoreApiRunClient {

    
    RunView createRun(RunCreateRequest request);

    
    RunView getRun(long id);

    
    List<RunView> listRunsByRequest(long requestId);

    /** Compare two runs' recorded response snapshots (tri-state decision and
     * diff vocabulary live in core; a 400 for "no snapshot" passes through). */
    ResponseCompareView compareRuns(long leftRunId, long rightRunId);

    
    void deleteRun(long id);

    
    void clearRuns(long requestId);
}
