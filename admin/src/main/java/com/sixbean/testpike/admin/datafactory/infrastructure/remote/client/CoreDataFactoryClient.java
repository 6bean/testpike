package com.sixbean.testpike.admin.datafactory.infrastructure.remote.client;

import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryCreateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRecordView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunResultView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryUpdateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataPoolItemView;
import com.sixbean.testpike.admin.datafactory.api.dto.GenFunctionGroupView;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public interface CoreDataFactoryClient {

    DataFactoryRecordView create(DataFactoryCreateRequest request);

    /**
 * The optional sort pair forwards to the core column whitelist (single gate). */
    PageView<DataFactoryRecordView> list(long projectId, String keyword, Integer type, int page, int size,
                                         String sortBy, String sortDir);

    DataFactoryRecordView get(long id);

    void update(long id, DataFactoryUpdateRequest request);

    void delete(long id);

    DataFactoryRunResultView run(long id);

    PageView<DataFactoryRunView> runs(long id, long projectId, int page, int size);

    List<DataFactoryRunView> recentRuns(long id, long projectId, int limit);


    
    List<GenFunctionGroupView> genkitCatalog();


    PageView<DataPoolItemView> listPoolItems(long projectId, Long envId, String type, Integer status,
                                             int page, int size);

    void updatePoolItemStatus(long projectId, long id, int status);

    void deletePoolItem(long projectId, long id);
}
