package com.sixbean.testpike.admin.suites.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.suites.api.dto.ScheduleFireView;

import java.time.Instant;
import java.util.List;


public interface CoreScheduleClient {

    /** Trigger ledger paging (project isolation, kind/status filters, whitelisted sort — null keeps fire_time DESC). */
    PageView<ScheduleFireView> listFires(long projectId, String kind, String status, int page, int size,
                                         String sortBy, String sortDir);

    /**
     * Next-fire preview (B-8): the next {@code count} theoretical trigger instants
     * for the cron, ascending. Arithmetic is single-sourced in core's
     * ScheduleCronTimes — the bridge forwards and never recomputes (red line 6).
     */
    List<Instant> previewCron(String cron, int count);
}
