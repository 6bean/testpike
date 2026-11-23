package com.sixbean.testpike.core.sample.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;
import com.sixbean.testpike.core.sample.api.dto.WordingAdoptionStat;
import com.sixbean.testpike.core.sample.api.dto.WordingPair;

import java.util.List;
import java.util.Map;


public interface TrainingSampleService {

    
    PageResult<TrainingSampleItem> list(Long projectId, String sampleType, int page, int size);

    /**
 * Sample ledger page with an optional server-side sort pair ( segment
 * 5.6). The pair is enforced against the core column whitelist — an
 * off-list key is refused fail-closed before any database read. A null
 * pair keeps the historical id DESC default, so this overload is a pure
 * extension of the four-argument entry above.
 *
 * @param sortBy whitelist key (id / case_no / project_id / sample_type /
 * source_version / created_at); null or blank = default order
 * @param sortDir asc for ascending, anything else falls back to desc
 */
    PageResult<TrainingSampleItem> list(Long projectId, String sampleType, int page, int size,
                                        String sortBy, String sortDir);

    
    List<WordingPair> listWordingPairs(long projectId, int limit);

    
    void updateAdoption(long sampleId, int adopted);

    
    List<TrainingSampleItem> listByIds(List<Long> ids);

    
    Map<Long, TrainingSampleItem> latestDiffByCaseIds(List<Long> caseIds);

    
    List<WordingAdoptionStat> summaryWordingAdoption();
}
