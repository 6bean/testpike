package com.sixbean.testpike.admin.sample.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sample.api.dto.TrainingSampleView;


public interface TrainingSampleCoreClient {

    /**
 * The optional sort pair is forwarded to the core whitelist gate. */
    PageView<TrainingSampleView> listSamples(Long projectId, String sampleType, int page, int size,
                                             String sortBy, String sortDir);

    
    void updateAdoption(long id, int adopted);
}
