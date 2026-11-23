package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;

import java.util.List;


public interface AppautoReservationService {

    
    AppautoReservationView create(AppautoReservationInput input);

    
    AppautoReservationView get(long id);

    
    AppautoReservationView release(long id, String reason);

    
    AppautoReservationView cancel(long id, String reason);

    
    AppautoReservationView approve(long id, long approverUserId);

    
    AppautoReservationView reject(long id, long approverUserId, String reason);

    
    List<AppautoReservationView> list(Long deviceId, Long projectId, Long batchId,
                                      boolean includeTerminal);

    
    AppautoReservationView bindBatch(long id, Long batchId);

    
    int sweepExpired();
}
