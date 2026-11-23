package com.sixbean.testpike.admin.sched.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sched.api.dto.SchedulerHealthView;

import java.util.Set;


public interface SchedulerCoreClient {

    
    String runNow(String job, Long operatorId);

    
    Set<String> jobs();

    /**
     * Paged health read. keyword filters by job name or plain-word description
     * (blank = all); sortBy/sortDir carries the core whitelist key (blank =
     * catalog registration order).
     */
    PageView<SchedulerHealthView> health(String keyword, int page, int size,
                                         String sortBy, String sortDir);

    /**
     * Historical three-argument form: keeps the default catalog order for
     * callers that do not ask for a sort.
     */
    default PageView<SchedulerHealthView> health(String keyword, int page, int size) {
        return health(keyword, page, size, null, null);
    }
}
