package com.sixbean.testpike.core.datafactory.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryQuery;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryUpdateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataPoolItemInfo;
import com.sixbean.testpike.core.datafactory.api.dto.GenFunctionGroupInfo;

import java.util.List;


public interface DataFactoryService {

    
    DataFactoryInfo create(long operatorId, DataFactoryCreateInput input);

    /**
 * <p>The query's optional sort pair resolves against the core column
 * whitelist in SQL , so the ordered window spans all rows
 * in the project instead of reshuffling one pageful; an off-list key is
 * refused with a human 400 naming the whitelist. */
    PageResult<DataFactoryInfo> list(long operatorId, DataFactoryQuery query);

    
    DataFactoryInfo get(long operatorId, long id);

    /**
 * Raw optimistic version of a data-factory row (AM batch
 * the dialog door re-anchors edit proposals onto the live value without
 * an operator context — card emission has none). Null when the row is
 * gone; project/ownership stays re-checked by the landing strategy.
 */
    Integer currentVersion(long id);

    
    void update(long operatorId, long id, DataFactoryUpdateInput input);

    
    void delete(long operatorId, long id);

 
    DataFactoryRunResult run(long operatorId, long id);

    
    PageResult<DataFactoryRunInfo> runs(long operatorId, long projectId, long factoryId, int page, int size);

    
    List<DataFactoryRunInfo> recentRuns(long operatorId, long projectId, long factoryId, int limit);


    
    List<GenFunctionGroupInfo> genkitCatalog();


    
    PageResult<DataPoolItemInfo> listPoolItems(long operatorId, long projectId, Long envId,
                                               String type, Integer status, int page, int size);

    
    void updatePoolItemStatus(long operatorId, long projectId, long id, int status);

    
    void deletePoolItem(long operatorId, long projectId, long id);


    
    void occupy(long operatorId, long projectId, long itemId, long lockedBy);

    
    void release(long operatorId, long projectId, long itemId);

    
    int releaseExpired();

    
    int cleanInvalid();
}
